package com.example.roadinspection.utils

import android.webkit.WebView
import com.example.roadinspection.data.model.HighFrequencyData
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.network.NetworkStatusProvider
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 负责持续向 WebView 更新仪表盘数据。
 * 它现在通过不同的 JS 方法独立更新各类数据。
 */
class DashboardUpdater(
    private val webView: WebView,
    private val locationProvider: LocationProvider,
    private val gpsSignalUpdater: GPSSignalUpdater,
    private val networkStatusProvider: NetworkStatusProvider
) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var updateJob: Job? = null
    private val gson = Gson()

    /**
     * 启动所有独立的监听任务。
     */
    fun start() {
        stop()
        updateJob = scope.launch {
            // 任务1：监听位置和距离，调用 updateDashboard
            launch {
                locationProvider.locationFlow.collect { location ->
                    location?.let {
                        val data = HighFrequencyData(
                            lat = it.latitude,
                            lng = it.longitude,
                            timeDiff = it.time - System.currentTimeMillis()
                        )
                        val jsonData = gson.toJson(data)
                        val script = "window.JSBridge.updateDashboard('$jsonData')"
                        webView.evaluateJavascript(script, null)
                    }
                }
            }

            // 任务2：监听GPS信号，调用 updateGpsSignal
            launch {
                gpsSignalUpdater.gpsLevelFlow.collect { gpsLevel ->
                    val script = "window.JSBridge.updateGpsSignal($gpsLevel)"
                    webView.evaluateJavascript(script, null)
                }
            }

            // 任务3：监听网络信号，调用 updateNetSignal
            launch {
                networkStatusProvider.networkStatusFlow.collect { networkStatus ->
                    val script = "window.JSBridge.updateNetSignal(${networkStatus.signalLevel})"
                    webView.evaluateJavascript(script, null)
                }
            }
        }

        // 启动所有需要手动启动的服务
        locationProvider.startLocationUpdates()
        gpsSignalUpdater.start()
    }

    /**
     * 停止所有数据流的监听。
     */
    fun stop() {
        updateJob?.cancel() // 这会取消所有在其内部启动的子任务
        updateJob = null
        // 停止所有服务以节省电量
        locationProvider.stopLocationUpdates()
        gpsSignalUpdater.stop()
    }
}
