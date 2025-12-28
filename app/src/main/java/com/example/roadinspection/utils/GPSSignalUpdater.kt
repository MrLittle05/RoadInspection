package com.example.roadinspection.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一个专门负责提供 GPS 信号强度等级的服务。
 * (Kotlin 版本)
 */
@RequiresApi(Build.VERSION_CODES.N)
class GPSSignalUpdater(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // StateFlow for GPS Signal Level
    private val _gpsLevelState = MutableStateFlow(0)
    val gpsLevelFlow: StateFlow<Int> = _gpsLevelState

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            super.onSatelliteStatusChanged(status)

            // 使用更简洁的 Kotlin 语法来计算拥有良好信号的卫星数量
            val satellitesWithGoodSignal = (0 until status.satelliteCount).count {
                status.getCn0DbHz(it) > 30
            }

            // 使用 when 表达式将数量映射为 0-4 的等级
            val level = when {
                satellitesWithGoodSignal >= 10 -> 4
                satellitesWithGoodSignal >= 7 -> 3
                satellitesWithGoodSignal >= 4 -> 2
                satellitesWithGoodSignal > 0 -> 1
                else -> 0
            }
            _gpsLevelState.value = level
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), gnssStatusCallback)
        } else {
            locationManager.registerGnssStatusCallback(gnssStatusCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
    }
}
