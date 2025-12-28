package com.example.roadinspection.domain.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.example.roadinspection.utils.KalmanLatLong
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback as GmsLocationCallback
import com.google.android.gms.location.LocationRequest as GmsLocationRequest
import com.google.android.gms.location.LocationResult as GmsLocationResult
import com.google.android.gms.location.LocationServices as GmsLocationServices
import com.google.android.gms.location.Priority as GmsPriority
import com.huawei.hms.api.HuaweiApiAvailability
import com.huawei.hms.location.LocationCallback as HmsLocationCallback
import com.huawei.hms.location.LocationRequest as HmsLocationRequest
import com.huawei.hms.location.LocationResult as HmsLocationResult
import com.huawei.hms.location.LocationServices as HmsLocationServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 道路巡检核心定位服务
 * 结合了 HEAD 的 Kalman 滤波算法与 master 的 HMS/GMS 多渠道支持
 */
class LocationProvider(private val context: Context) {

    // 实例化卡尔曼滤波器 (来自 HEAD)
    private val kalmanFilter = KalmanLatLong(baseQ_metres_per_second = 4.0f)

    private val _locationState = MutableStateFlow<Location?>(null)
    val locationFlow = _locationState.asStateFlow()

    private val _distanceState = MutableStateFlow(0.0)
    val distanceFlow = _distanceState.asStateFlow()

    private var lastValidLocation: Location? = null
    var isRecordingDistance = false

    // 预热计数器 (来自 HEAD)
    private var warmUpCounter = 0
    private val WARM_UP_COUNT = 10

    // 抽象定位提供者 (来自 master)
    private val locationUpdateProvider: LocationUpdateProvider

    init {
        // 定义统一的回调入口：无论来自 GMS 还是 HMS，都走这里
        val onLocationResult: (Location) -> Unit = { rawLocation ->
            processLocation(rawLocation)
        }

        locationUpdateProvider = if (isGmsAvailable()) {
            GmsLocationProvider(context, onLocationResult)
        } else if (isHmsAvailable()) {
            HmsLocationProvider(context, onLocationResult)
        } else {
            object : LocationUpdateProvider {
                override fun startLocationUpdates() {}
                override fun stopLocationUpdates() {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        // [关键修复] 每次开始定位都强制预热
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
     * 核心处理逻辑 (来自 HEAD 的算法)
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
        }

        // 更新 UI
        _locationState.value = filteredLocation

        if (isRecordingDistance) {
            updateDistance(filteredLocation)
        }
    }

    private fun updateDistance(filteredCurrent: Location) {
        // 预热期保护
        if (warmUpCounter > 0) {
            warmUpCounter--
            return
        }

        // 静止过滤
        if (filteredCurrent.hasSpeed() && filteredCurrent.speed < 0.5f) {
            return
        }
        if (lastValidLocation == null) {
            lastValidLocation = filteredCurrent
            return
        }
        val previous = lastValidLocation!!
        val timeDeltaMs = abs(filteredCurrent.time - previous.time)

        // 断层保护
        if (timeDeltaMs > 10_000) {
            lastValidLocation = filteredCurrent
            warmUpCounter = 5
            return
        }

        val distanceDelta = previous.distanceTo(filteredCurrent)

        if (distanceDelta > 0.5f) {
            // 瞬移保护
            val calculatedSpeed = if (timeDeltaMs > 0) distanceDelta / (timeDeltaMs / 1000.0) else 0.0
            if (calculatedSpeed > 40.0 && (!filteredCurrent.hasSpeed() || filteredCurrent.speed < 30.0)) {
                lastValidLocation = filteredCurrent
                return
            }

            _distanceState.value += distanceDelta
            lastValidLocation = filteredCurrent
        }
    }

    // --- Master 分支的 GMS/HMS 辅助类 ---

    private fun isGmsAvailable(): Boolean {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private fun isHmsAvailable(): Boolean {
        return HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(context) == com.huawei.hms.api.ConnectionResult.SUCCESS
    }
}

// 定义接口 (来自 Master)
private interface LocationUpdateProvider {
    fun startLocationUpdates()
    fun stopLocationUpdates()
}

// GMS 实现 (来自 Master)
private class GmsLocationProvider(
    private val context: Context,
    private val onLocationResult: (Location) -> Unit
) : LocationUpdateProvider {
    private val fusedLocationClient = GmsLocationServices.getFusedLocationProviderClient(context)
    private val locationCallback = object : GmsLocationCallback() {
        override fun onLocationResult(locationResult: GmsLocationResult) {
            locationResult.lastLocation?.let(onLocationResult)
        }
    }

    @SuppressLint("MissingPermission")
    override fun startLocationUpdates() {
        val locationRequest = GmsLocationRequest.Builder(GmsPriority.PRIORITY_HIGH_ACCURACY, 1000)
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

    override fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}

// HMS 实现 (来自 Master)
private class HmsLocationProvider(
    private val context: Context,
    private val onLocationResult: (Location) -> Unit
) : LocationUpdateProvider {
    private val fusedLocationClient = HmsLocationServices.getFusedLocationProviderClient(context)
    private val locationCallback = object : HmsLocationCallback() {
        override fun onLocationResult(locationResult: HmsLocationResult) {
            locationResult.lastLocation?.let(onLocationResult)
        }
    }

    @SuppressLint("MissingPermission")
    override fun startLocationUpdates() {
        val locationRequest = HmsLocationRequest.create().apply {
            priority = HmsLocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 1000
            fastestInterval = 500
        }
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}