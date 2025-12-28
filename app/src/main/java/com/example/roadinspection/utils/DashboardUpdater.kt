package com.example.roadinspection.utils

import android.webkit.WebView
import com.example.roadinspection.data.model.HighFreqDashboardData
import com.example.roadinspection.domain.location.GpsSignalProvider
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.network.NetworkStatusProvider
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 负责向 WebView 分发数据的更新器。
 * 已重构为分离高频与低频数据流，减少 Bridge 通信压力。
 */
class DashboardUpdater(
    private val webView: WebView,
    private val locationProvider: LocationProvider,
    private val gpsSignalProvider: GpsSignalProvider,
    private val networkStatusProvider: NetworkStatusProvider
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val gson = Gson()

    /**
     * 启动并行的数据监听任务，更新前端仪表盘数据
     */
    fun start() {
        // 清理旧任务（如果有）
        scope.coroutineContext.cancelChildren()

        // 1. 高频任务：位置 + 距离 + 时间
        startHighFrequencyUpdates()

        // 2. 低频任务：GPS 信号强度
        startGpsStatusUpdates()

        // 3. 低频任务：网络状态
        startNetworkStatusUpdates()

        // 确保位置服务已开启
        locationProvider.startLocationUpdates()
    }

    /**
     * 停止所有更新任务
     */
    fun stop() {
        scope.coroutineContext.cancelChildren()
    }

    private fun startHighFrequencyUpdates() {
        scope.launch {
            combine(
                locationProvider.locationFlow,
                locationProvider.distanceFlow
            ) { location, totalDist ->
                if (location != null) {
                    val data = HighFreqDashboardData(
                        timeDiff = location.time - System.currentTimeMillis(),
                        lat = location.latitude,
                        lng = location.longitude,
                        totalDistance = totalDist
                    )
                    val jsonData = gson.toJson(data)
                    "window.JSBridge.updateDashboard('$jsonData')"
                } else {
                    null
                }
            }.collectLatest { script ->
                script?.let { webView.evaluateJavascript(it, null) }
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
                .map { it.signalLevel } // 这里只提取强度，如果 JS 需要类型(5G/WIFI)，可改为传递完整对象
                .distinctUntilChanged() // 只有网络信号状态变化时才触发
                .collect { level ->
                    val script = "window.JSBridge.updateNetLevel($level)"
                    webView.evaluateJavascript(script, null)
                }
        }
    }
}