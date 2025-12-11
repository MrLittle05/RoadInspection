package com.example.roadinspection.domain.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一个可复用的位置服务提供者。
 * 使用 Flow 向外提供持续的位置更新和 GPS 信号等级。
 * @param context 应用上下文
 */
class LocationProvider(private val context: Context) { // <-- 已将 context 保存为属性

    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // StateFlow for Location
    private val _locationState = MutableStateFlow<Location?>(null)
    val locationFlow: StateFlow<Location?> = _locationState

    // StateFlow for GPS Signal Level
    private val _gpsLevelState = MutableStateFlow(0)
    val gpsLevelFlow: StateFlow<Int> = _gpsLevelState

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            _locationState.value = locationResult.lastLocation
        }
    }

    // GNSS status callback to determine signal strength
    private val gnssStatusCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                super.onSatelliteStatusChanged(status)
                var satellitesWithGoodSignal = 0
                for (i in 0 until status.satelliteCount) {
                    // A C/N0 of > 30 dBHz is generally considered good signal
                    if (status.getCn0DbHz(i) > 30) {
                        satellitesWithGoodSignal++
                    }
                }
                // Simple mapping to a 0-4 scale
                _gpsLevelState.value = when {
                    satellitesWithGoodSignal >= 10 -> 4
                    satellitesWithGoodSignal >= 7 -> 3
                    satellitesWithGoodSignal >= 4 -> 2
                    satellitesWithGoodSignal > 0 -> 1
                    else -> 0
                }
            }
        }
    } else {
        null
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdateDelayMillis(1000)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            
            // 为不同安卓版本使用不同的 API 注册 GNSS 状态回调
            if (gnssStatusCallback != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android R (API 30) 及以上版本使用带 Executor 的现代 API
                    locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), gnssStatusCallback)
                } else {
                    // Android N (API 24) 到 Q (API 29) 使用已弃用的 API
                    locationManager.registerGnssStatusCallback(gnssStatusCallback)
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        // 取消注册 GNSS 状态回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        }
    }
}
