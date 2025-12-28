package com.example.roadinspection.domain.location

import android.content.Context
import android.location.Location
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
            isNeedAddress = true // 👈 改为 true，让定位直接返回地址
            interval = 1000 // 依然保持1秒定位一次
        }

        locationClient?.setLocationOption(locationOption)

        // 设置回调监听
        locationClient?.setLocationListener { amapLocation ->
            if (amapLocation != null && amapLocation.errorCode == 0) {
                val location = Location("amap").apply {
                    latitude = amapLocation.latitude
                    longitude = amapLocation.longitude
                    accuracy = amapLocation.accuracy
                    time = amapLocation.time
                    speed = amapLocation.speed

                    // 将地址字符串存入 Bundle，传给 LocationProvider
                    val bundle = android.os.Bundle()
                    bundle.putString("address", amapLocation.address)
                    extras = bundle
                }

                // 关键：调用这个回调，数据才会进入 LocationProvider 的 flow
                onLocationResult(location)
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