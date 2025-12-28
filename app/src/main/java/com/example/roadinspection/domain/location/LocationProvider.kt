package com.example.roadinspection.domain.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.roadinspection.utils.KalmanLatLong
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 道路巡检核心定位服务 (修复版)
 * 包含：卡尔曼滤波、预热保护、后台断层保护
 */
class LocationProvider(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    // 实例化卡尔曼滤波器
    private val kalmanFilter = KalmanLatLong(baseQ_metres_per_second = 4.0f)

    private val _locationState = MutableStateFlow<Location?>(null)
    val locationFlow = _locationState.asStateFlow()

    private val _gpsLevelState = MutableStateFlow(0)
    val gpsLevelFlow = _gpsLevelState.asStateFlow()

    private val _distanceState = MutableStateFlow(0.0)
    val distanceFlow = _distanceState.asStateFlow()

    private var lastValidLocation: Location? = null
    var isRecordingDistance = false

    // [新增] 预热计数器：忽略冷启动或恢复后的前10个点
    private var warmUpCounter = 0
    private val WARM_UP_COUNT = 10

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        // [关键修复] 每次开始定位都强制预热，防止刚打开时的乱跳
        warmUpCounter = WARM_UP_COUNT

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdateDelayMillis(1000)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    fun resetDistanceCounter() {
        _distanceState.value = 0.0
        lastValidLocation = null
        kalmanFilter.reset()
        // [关键修复] 重置里程时也要预热
        warmUpCounter = WARM_UP_COUNT
        isRecordingDistance = true
    }

    fun stopDistanceCounter() {
        isRecordingDistance = false
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val rawLocation = locationResult.lastLocation ?: return

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
            }

            // 更新 UI
            _locationState.value = filteredLocation

            if (isRecordingDistance) {
                updateDistance(filteredLocation)
            }
        }
    }

    private fun updateDistance(filteredCurrent: Location) {
        // [关键修复 1] 预热期保护：只消耗计数，坚决不更新锚点
        if (warmUpCounter > 0) {
            warmUpCounter--
            return
        }

        // [关键修复 2] 静止过滤：从 0.2 提高到 0.5 (1.8km/h)
        if (filteredCurrent.hasSpeed() && filteredCurrent.speed < 0.5f) {
            return
        }

        if (lastValidLocation == null) {
            lastValidLocation = filteredCurrent
            return
        }

        val previous = lastValidLocation!!
        val timeDeltaMs = abs(filteredCurrent.time - previous.time)

        // [关键修复 3] 断层保护
        if (timeDeltaMs > 10_000) {
            lastValidLocation = filteredCurrent
            // 刚从后台回来，数据可能不稳，追加一点点预热
            warmUpCounter = 5
            return
        }

        val distanceDelta = previous.distanceTo(filteredCurrent)

        if (distanceDelta > 0.5f) {
            // [关键修复 4] 瞬移保护
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