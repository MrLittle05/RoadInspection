package com.example.roadinspection.domain.location

import android.content.Context
import android.location.Location
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener

class AmapLocationProvider(
    private val context: Context,
    private val onLocationResult: (Location) -> Unit
) : LocationUpdateProvider {

    private var locationClient: AMapLocationClient? = null

    init {
        // 初始化定位客户端
        locationClient = AMapLocationClient(context)

        // 配置定位参数
        val locationOption = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isNeedAddress = true // 👈 改为 true，让定位直接返回地址
            interval = 1000 // 依然保持1秒定位一次
        }

        locationClient?.setLocationOption(locationOption)

        // 设置回调监听
        locationClient?.setLocationListener { amapLocation ->
            if (amapLocation != null && amapLocation.errorCode == 0) {
                // 1. 创建标准 Location 对象
                val location = Location("amap").apply {
                    latitude = amapLocation.latitude
                    longitude = amapLocation.longitude
                    accuracy = amapLocation.accuracy
                    time = amapLocation.time
                    speed = amapLocation.speed
                    elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    // 将地址字符串存入 Bundle，传给 LocationProvider

                    // 2. 将高德地址存入 extras，这样 DashboardUpdater 才能拿到
                    val bundle = android.os.Bundle()
                    bundle.putString("address", amapLocation.address)
                    extras = bundle
                }

                // 3. 重要：调用回调，通知 LocationProvider 数据更新了
                onLocationResult(location)

                android.util.Log.d("AmapLog", "数据已传出: ${amapLocation.address}")
            } else if (amapLocation != null) {
                // 如果失败，打印错误码（这对排查小米问题至关重要）
                android.util.Log.e("AmapLog", "定位失败码: ${amapLocation.errorCode}, 信息: ${amapLocation.errorInfo}")
            }
        }
    }

    override fun startLocationUpdates() {
        locationClient?.startLocation()
    }

    override fun stopLocationUpdates() {
        locationClient?.stopLocation()
    }
}