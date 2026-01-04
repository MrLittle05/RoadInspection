package com.example.roadinspection.domain.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 专门负责监听 GPS 卫星信号强度的监视器
 * 职责单一：只通过 GnssStatus 告诉 UI 现在的信号是几格
 */
class GpsSignalProvider(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _gpsLevelState = MutableStateFlow(0)
    private val gpsLevelFlow = _gpsLevelState.asStateFlow()

    fun getGpsLevelFlow(): StateFlow<Int> = gpsLevelFlow

    fun startGpsSignalUpdates() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.registerGnssStatusCallback(
                    ContextCompat.getMainExecutor(context),
                    gnssStatusCallback
                )
            } else {
                // 旧版本 API
                locationManager.registerGnssStatusCallback(
                    gnssStatusCallback,
                    Handler(Looper.getMainLooper())
                )
            }
        }
    }

    fun stopGpsSignalUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        }
    }

    private val gnssStatusCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var satellitesWithGoodSignal = 0
                val count = status.satelliteCount

                for (i in 0 until count) {
                    // CN0DbHz > 30 通常被认为是可用信号
                    if (status.getCn0DbHz(i) > 30) {
                        satellitesWithGoodSignal++
                    }
                }

                // 保持你原有的评级逻辑
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
}