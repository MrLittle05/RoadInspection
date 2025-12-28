package com.example.roadinspection.domain.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationProvider(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _locationState = MutableStateFlow<Location?>(null)
    val locationFlow: StateFlow<Location?> = _locationState

    private val _gpsLevelState = MutableStateFlow(0)
    val gpsLevelFlow: StateFlow<Int> = _gpsLevelState

    private val _distanceState = MutableStateFlow(0.0)
    val distanceFlow = _distanceState.asStateFlow()

    private var lastValidLocation: Location? = null
    var isRecordingDistance = false

    private val locationUpdateProvider: LocationUpdateProvider

    init {
        val onLocationResult: (Location) -> Unit = { location ->
            // 过滤掉小米可能返回的 0,0 伪坐标
            if (location.latitude != 0.0 || location.longitude != 0.0) {
                _locationState.value = location
                if (isRecordingDistance) {
                    updateDistance(location)
                }
            }
        }

        // ================== 修改重点 ==================
        // 不再根据 GMS/HMS 切换，全机型统一使用高德定位 SDK 作为核心引擎
        // 这样小米手机就能避开连不上的 Google 服务
        locationUpdateProvider = AmapLocationProvider(context, onLocationResult)
        // =============================================
    }

    private fun isGmsAvailable(): Boolean {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private fun isHmsAvailable(): Boolean {
        return HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(context) == com.huawei.hms.api.ConnectionResult.SUCCESS
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            super.onSatelliteStatusChanged(status)
            var satellitesWithGoodSignal = 0
            for (i in 0 until status.satelliteCount) {
                if (status.getCn0DbHz(i) > 30) {
                    satellitesWithGoodSignal++
                }
            }
            _gpsLevelState.value = when {
                satellitesWithGoodSignal >= 10 -> 4
                satellitesWithGoodSignal >= 7 -> 3
                satellitesWithGoodSignal >= 4 -> 2
                satellitesWithGoodSignal > 0 -> 1
                else -> 0
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        locationUpdateProvider.startLocationUpdates()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), gnssStatusCallback)
        } else {
            @Suppress("DEPRECATION")
            locationManager.registerGnssStatusCallback(gnssStatusCallback)
        }
    }

    fun stopLocationUpdates() {
        locationUpdateProvider.stopLocationUpdates()
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
    }

    fun resetDistanceCounter() {
        _distanceState.value = 0.0
        lastValidLocation = null
        isRecordingDistance = true
    }

    fun stopDistanceCounter() {
        isRecordingDistance = false
    }

    private fun updateDistance(current: Location) {
        if (current.hasAccuracy() && current.accuracy > 20f) {
            return
        }
        if (current.hasSpeed() && current.speed < 0.5f) {
            return
        }
        if (lastValidLocation == null) {
            lastValidLocation = current
            return
        }
        val previous = lastValidLocation!!
        val distanceDelta = previous.distanceTo(current)
        if (distanceDelta > 2.0f) {
            _distanceState.value += distanceDelta
            lastValidLocation = current
        }
    }
}

internal interface LocationUpdateProvider {
    fun startLocationUpdates()
    fun stopLocationUpdates()
}

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
