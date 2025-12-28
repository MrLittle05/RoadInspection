package com.example.roadinspection.domain.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.example.roadinspection.utils.KalmanLatLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 道路巡检核心定位服务 (Resolved)
 * 架构：HEAD (Kalman Filter + Flow)
 * 引擎：Master (Amap 高德定位)
 */
class LocationProvider(private val context: Context) {

    // 1. 实例化卡尔曼滤波器 (保留 HEAD 的算法)
    private val kalmanFilter = KalmanLatLong(baseQ_metres_per_second = 4.0f)

    private val _locationState = MutableStateFlow<Location?>(null)
    val locationFlow = _locationState.asStateFlow()

    private val _distanceState = MutableStateFlow(0.0)
    val distanceFlow = _distanceState.asStateFlow()

    private var lastValidLocation: Location? = null
    var isRecordingDistance = false

    // 预热计数器
    private var warmUpCounter = 0
    private val WARM_UP_COUNT = 10

    // 2. 使用 Master 引入的高德定位提供者
    // 这里的 LocationUpdateProvider 需要在下方定义
    private val locationUpdateProvider: LocationUpdateProvider

    init {
        // 定义统一回调：从高德拿到原始数据 -> 送入卡尔曼滤波
        val onLocationResult: (Location) -> Unit = { rawLocation ->
            // 过滤掉经纬度为 0 的无效数据
            if (rawLocation.latitude != 0.0 || rawLocation.longitude != 0.0) {
                processLocation(rawLocation)
            }
        }

        // 初始化高德定位 (Master 的改动)
        // 注意：AmapLocationProvider 必须实现 LocationUpdateProvider 接口
        locationUpdateProvider = AmapLocationProvider(context, onLocationResult)
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        warmUpCounter = WARM_UP_COUNT
        locationUpdateProvider.startLocationUpdates()
    }

    fun stopLocationUpdates() {
        locationUpdateProvider.stopLocationUpdates()
    }

    fun resetDistanceCounter() {
        _distanceState.value = 0.0
        lastValidLocation = null
        kalmanFilter.reset()
        warmUpCounter = WARM_UP_COUNT
        isRecordingDistance = true
    }

    fun stopDistanceCounter() {
        isRecordingDistance = false
    }

    /**
     * 核心算法处理 (HEAD 的逻辑)
     */
    private fun processLocation(rawLocation: Location) {
        // 1. 过滤陈旧数据 (>10秒前的缓存不要)
        val locationAgeNs = android.os.SystemClock.elapsedRealtimeNanos() - rawLocation.elapsedRealtimeNanos
        if (locationAgeNs > 10_000_000_000L) return

        // 2. 卡尔曼滤波处理
        kalmanFilter.process(
            latMeasurement = rawLocation.latitude,
            lngMeasurement = rawLocation.longitude,
            accuracy = rawLocation.accuracy,
            timestampMs = rawLocation.time,
            currentSpeed = rawLocation.speed
        )

        // 3. 构建平滑后的 Location
        val filteredLocation = Location("KalmanFilter").apply {
            latitude = kalmanFilter.getLat()
            longitude = kalmanFilter.getLng()
            accuracy = rawLocation.accuracy
            time = rawLocation.time
            speed = rawLocation.speed
            bearing = rawLocation.bearing
            altitude = rawLocation.altitude
            elapsedRealtimeNanos = rawLocation.elapsedRealtimeNanos

            // [关键合并点] 复制原始数据中的地址信息 (Master 的功能)
            // AmapLocationProvider 已经把地址塞进了 extras
            if (rawLocation.extras != null) {
                extras = rawLocation.extras
            }
        }

        // 更新 UI Flow
        _locationState.value = filteredLocation

        if (isRecordingDistance) {
            updateDistance(filteredLocation)
        }
    }

    private fun updateDistance(filteredCurrent: Location) {
        // (保留 HEAD 的防漂移距离计算逻辑)
        if (warmUpCounter > 0) {
            warmUpCounter--
            return
        }
        if (filteredCurrent.hasSpeed() && filteredCurrent.speed < 0.5f) {
            return
        }
        if (lastValidLocation == null) {
            lastValidLocation = filteredCurrent
            return
        }
        val previous = lastValidLocation!!
        val timeDeltaMs = abs(filteredCurrent.time - previous.time)

        if (timeDeltaMs > 10_000) {
            lastValidLocation = filteredCurrent
            warmUpCounter = 5
            return
        }

        val distanceDelta = previous.distanceTo(filteredCurrent)

        if (distanceDelta > 0.5f) {
            val calculatedSpeed = if (timeDeltaMs > 0) distanceDelta / (timeDeltaMs / 1000.0) else 0.0
            if (calculatedSpeed > 40.0 && (!filteredCurrent.hasSpeed() || filteredCurrent.speed < 30.0)) {
                lastValidLocation = filteredCurrent
                return
            }

            _distanceState.value += distanceDelta
            lastValidLocation = filteredCurrent
        }
    }
}

interface LocationUpdateProvider {
    fun startLocationUpdates()
    fun stopLocationUpdates()
}