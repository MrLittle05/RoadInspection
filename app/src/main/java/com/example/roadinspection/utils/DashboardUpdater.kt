package com.example.roadinspection.utils

import android.webkit.WebView
import com.example.roadinspection.data.model.HighFrequencyData
import com.example.roadinspection.domain.location.GpsSignalProvider
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.network.NetworkStatusProvider
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.util.Log

/**
 * 负责向 WebView 分发数据的更新器。
 * 架构：保留 HEAD 的响应式流设计
 * 功能：集成 Master 的地址更新 (通过 Location Extras)
 */
class DashboardUpdater(
    private val webView: WebView,
    private val locationProvider: LocationProvider,
    private val gpsSignalProvider: GpsSignalProvider,
    private val networkStatusProvider: NetworkStatusProvider
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val gson = Gson()

    // 用于去重，防止相同地址重复发给 JS 浪费性能
    private var lastSentAddress: String? = null

    /**
     * 启动并行的数据监听任务
     */
    fun start() {
        scope.coroutineContext.cancelChildren()

        // 1. 高频任务：位置 + 距离 + 地址
        startLocationDataUpdates()

        // 2. 低频任务：GPS 信号强度
        startGpsStatusUpdates()

        // 3. 低频任务：网络状态
        startNetworkStatusUpdates()

        locationProvider.startLocationUpdates()
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
    }

    private fun startLocationDataUpdates() {
        scope.launch {
            combine(
                locationProvider.getLocationFlow(),
                locationProvider.getDistanceFlow()
            ) { location, totalDist ->
                if (location != null) {
                    // 1. 构建仪表盘数据
                    val data = HighFrequencyData(
                        timeDiff = location.time - System.currentTimeMillis(),
                        lat = location.latitude,
                        lng = location.longitude,
                        totalDistance = totalDist
                    )

                    // 2. 提取地址 (来自 AmapLocationProvider 放入的 Bundle)
                    val address = location.extras?.getString("address") ?: "获取地址失败"

                    Log.d("AddressCheck", "当前获取到的地址: $address")

                    Pair(gson.toJson(data), address)
                } else {
                    null
                }
            }.collectLatest { pair ->
                pair?.let { (jsonData, address) ->
                    // 发送仪表盘数据 (高频)
                    webView.evaluateJavascript("window.JSBridge.updateDashboard('$jsonData')", null)

                    // 发送地址数据 (仅当变化时发送，优化性能)
                    if (address != lastSentAddress) {
                        lastSentAddress = address
                        webView.evaluateJavascript("window.JSBridge.updateAddress('$address')", null)
                    }
                }
            }
        }
    }

    private fun startGpsStatusUpdates() {
        scope.launch {
            gpsSignalProvider.gpsLevelFlow
                .collect { level ->
                    val script = "window.JSBridge.updateGpsLevel($level)"
                    webView.evaluateJavascript(script, null)
                }
        }
    }

    private fun startNetworkStatusUpdates() {
        scope.launch {
            networkStatusProvider.networkStatusFlow
                .map { it.signalLevel }
                .distinctUntilChanged()
                .collect { level ->
                    val script = "window.JSBridge.updateNetLevel($level)"
                    webView.evaluateJavascript(script, null)
                }
        }
    }
}