package com.example.roadinspection.util

import android.location.Location
import android.webkit.WebView
import com.example.roadinspection.data.model.DashboardData
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.network.NetworkStatus
import com.example.roadinspection.domain.network.NetworkStatusProvider
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

/**
 * 负责持续向 WebView 更新仪表盘数据。
 * @param webView 需要接收数据的 WebView 实例。
 * @param locationProvider 位置数据提供者。
 * @param networkStatusProvider 网络状态提供者。
 */
class DashboardUpdater(
    private val webView: WebView,
    private val locationProvider: LocationProvider,
    private val networkStatusProvider: NetworkStatusProvider
) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var updateJob: Job? = null
    private val gson = Gson()

    /**
     * 开始监听所有数据流并发送给 WebView。
     */
    fun start() {
        stop()
        updateJob = scope.launch {
            // 使用 combine 将多个 Flow 合并成一个
            combine(
                locationProvider.locationFlow,
                locationProvider.gpsLevelFlow,
                networkStatusProvider.networkStatusFlow,
                locationProvider.distanceFlow
            ) { location, gpsLevel, networkStatus, totalDist ->
                // 每当任何一个 flow 发出新数据，这里就会被调用
                location?.let {
                    // 1. 基于所有真实数据创建 DashboardData 对象
                    val realData = createDashboardData(it, gpsLevel, networkStatus, totalDist)

                    // 2. 将数据对象转换为 JSON 字符串
                    val jsonData = gson.toJson(realData)

                    // 3. 构建并执行 JavaScript 调用
                    val script = "window.JSBridge.updateDashboard('$jsonData')"
                    webView.evaluateJavascript(script, null)
                }
            }.collect() // 开始收集数据
        }
        // 启动位置监听 (网络监听是自动启动的)
        locationProvider.startLocationUpdates()
    }

    /**
     * 停止发送数据并停止位置监听。
     */
    fun stop() {
        updateJob?.cancel()
        updateJob = null
        // 停止位置监听以节省电量
        locationProvider.stopLocationUpdates()
    }

    // --- 私有辅助方法 ---

    private fun createDashboardData(location: Location, gpsLevel: Int, networkStatus: NetworkStatus, distance: Double): DashboardData {
        return DashboardData(
            timeDiff = location.time - System.currentTimeMillis(),
            lat = location.latitude,
            lng = location.longitude,
            netType = networkStatus.networkType,
            netLevel = networkStatus.signalLevel,
            gpsLevel = gpsLevel,
            totalDistance = distance,
            // isInspecting = true
        )
    }
}
