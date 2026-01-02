package com.example.roadinspection.domain.location

import android.content.Context
import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
// 假设你的 KalmanFilter 在这个包下，如果不是请修改 import
import com.example.roadinspection.util.KalmanFilter

/**
 * 接口定义 (保持在类外部，确保可见性)
 */
interface LocationUpdateProvider {
    fun startLocationUpdates()
    fun stopLocationUpdates()
}

class LocationProvider(private val context: Context) {

    // 1. UI 状态流
    private val _locationState = MutableStateFlow<Location?>(null)
    val locationFlow: StateFlow<Location?> = _locationState.asStateFlow()

    private val _totalDistance = MutableStateFlow(0f)
    val totalDistance = _totalDistance.asStateFlow()

    // 2. 核心组件
    private var locationUpdateProvider: LocationUpdateProvider? = null

    // 初始化卡尔曼滤波器 (假设你有这个类)
    private val kalmanFilter = KalmanFilter()

    // 3. 算法状态变量
    var isRecordingDistance = false
    private var lastValidLocation: Location? = null
    private var warmUpCounter = 0 // 预热计数器，过滤刚开始定位时的不稳定点

    init {
        // 定义回调：AmapLocationProvider 拿到原始数据后，交给 processLocation 处理
        val onLocationResult: (Location) -> Unit = { rawLocation ->
            processLocation(rawLocation)
        }

        // 初始化高德定位 (不再区分 GMS/HMS)
        locationUpdateProvider = AmapLocationProvider(context, onLocationResult)
    }

    /**
     * 核心算法处理 (HEAD 的逻辑 - 已融合)
     * 负责：过滤陈旧数据 -> 卡尔曼滤波平滑 -> 复制地址信息 -> 更新 UI
     */
    private fun processLocation(rawLocation: Location) {
        // 1. 过滤陈旧数据 (>10秒前的缓存不要)
        // 注意：AmapLocationProvider 需要确保设置了 elapsedRealtimeNanos，否则这里可能误判
        // 如果 AMap 没返回纳秒时间，这里建议改用 System.currentTimeMillis() - rawLocation.time
        val locationAgeNs = SystemClock.elapsedRealtimeNanos() - rawLocation.elapsedRealtimeNanos
        if (locationAgeNs > 10_000_000_000L) return

        if (rawLocation.accuracy > 20.0f) return

        // 2. 卡尔曼滤波处理 (平滑经纬度，减少 GPS 抖动)
        kalmanFilter.process(
            latMeasurement = rawLocation.latitude,
            lngMeasurement = rawLocation.longitude,
            accuracy = rawLocation.accuracy,
            timestampMs = rawLocation.time,
            currentSpeed = rawLocation.speed // 如果高德没返回速度，这里可能是 0
        )

        // 3. 构建平滑后的 Location 对象
        val filteredLocation = Location("KalmanFilter").apply {
            latitude = kalmanFilter.getLat()
            longitude = kalmanFilter.getLng()
            accuracy = rawLocation.accuracy
            time = rawLocation.time
            speed = rawLocation.speed
            bearing = rawLocation.bearing
            altitude = rawLocation.altitude
            elapsedRealtimeNanos = rawLocation.elapsedRealtimeNanos

            // [关键合并点] 复制原始数据中的地址信息
            // 确保 DashboardUpdater 能拿到 AmapLocationProvider 塞进来的地址
            if (rawLocation.extras != null) {
                extras = rawLocation.extras
            }
        }

        // 4. 更新 UI Flow (Webview 接收到的是平滑后的坐标)
        _locationState.value = filteredLocation

        // 5. 如果正在记录，进行距离计算
        if (isRecordingDistance) {
            updateDistance(filteredLocation)
        }
    }

    /**
     * 防漂移距离计算逻辑 (已融合)
     */
    private fun updateDistance(filteredCurrent: Location) {
        // 1. 预热期过滤 (刚启动的前 5 个点通常不准)
        if (warmUpCounter > 0) {
            warmUpCounter--
            return
        }

        // 2. 静止漂移过滤 (速度极小时不计算距离，防止红绿灯时乱跳)
        if (filteredCurrent.hasSpeed() && filteredCurrent.speed < 0.5f) {
            return
        }

        // 3. 初始化上一点
        if (lastValidLocation == null) {
            lastValidLocation = filteredCurrent
            return
        }

        val previous = lastValidLocation!!
        val timeDeltaMs = abs(filteredCurrent.time - previous.time)

        // 4. 时间跳变保护 (如果两点间隔超过 10秒，说明可能定位断过，重置逻辑)
        if (timeDeltaMs > 10_000) {
            lastValidLocation = filteredCurrent
            warmUpCounter = 5 // 重新预热
            return
        }

        val distanceDelta = previous.distanceTo(filteredCurrent) // 单位：米

        // 5. 最小移动门限 (移动超过 0.5米 才算数)
        if (distanceDelta > 0.5f) {
            // 6. 异常速度过滤 (防飞点)
            // 如果计算出的速度 > 40m/s (144km/h) 且当前 GPS 速度并不支持该速度，视为飞点
            val calculatedSpeed = if (timeDeltaMs > 0) distanceDelta / (timeDeltaMs / 1000.0) else 0.0

            if (calculatedSpeed > 40.0 && (!filteredCurrent.hasSpeed() || filteredCurrent.speed < 30.0)) {
                // 这是一个飞点，直接忽略，但更新 lastValidLocation 以保持时间连续性
                lastValidLocation = filteredCurrent
                return
            }

            // 7. 累加距离 (转换为千米)
            // 注意：你的原逻辑是 += distanceDelta (米)，这里我除以 1000f 转为千米，适配 UI
            _totalDistance.value += distanceDelta / 1000f

            lastValidLocation = filteredCurrent
        }
    }

    // ================== 公共控制方法 ==================

    fun startLocationUpdates() {
        locationUpdateProvider?.startLocationUpdates()
    }

    fun stopLocationUpdates() {
        locationUpdateProvider?.stopLocationUpdates()
    }

    fun startRecordingDistance() {
        isRecordingDistance = true
        _totalDistance.value = 0f
        lastValidLocation = null
        warmUpCounter = 5 // 开启巡检时，前5个点丢弃，防止漂移
        // 如果 KalmanFilter 需要重置，可以在这里调用 kalmanFilter.reset()
    }

    fun stopRecordingDistance() {
        isRecordingDistance = false
    }

    fun resetDistanceCounter() {
        _totalDistance.value = 0f
        lastValidLocation = null
        kalmanFilter.reset()
        warmUpCounter = 0
        isRecordingDistance = true
    }

    fun stopDistanceCounter() {
        isRecordingDistance = false
    }
}