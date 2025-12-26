package com.example.roadinspection.util

import android.location.Location
import android.webkit.WebView
import com.example.roadinspection.data.model.HighFrequencyData
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.network.NetworkStatusProvider
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.example.roadinspection.domain.location.AddressProvider
import android.content.Context

/**
 * 负责持续向 WebView 更新仪表盘数据。
 * 它现在通过不同的 JS 方法独立更新各类数据。
 */
class DashboardUpdater(
    private val webView: WebView,
    private val locationProvider: LocationProvider,
    private val gpsSignalUpdater: GPSSignalUpdater,
    private val networkStatusProvider: NetworkStatusProvider,
    private val context: Context // 需要传入Context来初始化 AddressProvider
) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var updateJob: Job? = null
    private val gson = Gson()
    private val addressProvider = AddressProvider(context)

    // 定义一个变量存上次查地址的时间
    private var lastAddressRequestTime: Long = 0
    // 定义一个变量存当前的地址缓存 (避免查不到时显示空)
    private var cachedAddress: String = "正在获取地址..."

    // 设置地址更新间隔 (毫秒)，这里设为 4000ms (4秒)
    private val ADDRESS_UPDATE_INTERVAL = 4000L

    fun start() {
        stop()
        updateJob = scope.launch {
            // 任务1：监听位置 -> 1. 每次都更新经纬度 2. 只有到时间了才更新地址
            launch {
                locationProvider.locationFlow.collect { location ->
                    location?.let { loc ->
                        val currentTime = System.currentTimeMillis()

                        // =================================================
                        // 逻辑核心：只有当 (当前时间 - 上次时间 > 间隔) 时，才去请求高德
                        // =================================================
                        if (currentTime - lastAddressRequestTime > ADDRESS_UPDATE_INTERVAL) {

                            // 更新时间戳
                            lastAddressRequestTime = currentTime

                            // 发起网络请求 (挂起函数，不会卡顿)
                            val newAddress = addressProvider.getAddressFromLocation(loc.latitude, loc.longitude)

                            // 如果拿到了新地址，就更新缓存
                            if (!newAddress.isNullOrEmpty()) {
                                cachedAddress = newAddress
                            }

                            // 推送地址给 WebView (每4秒推一次)
                            val addressScript = "window.JSBridge.updateAddress('$cachedAddress')"
                            webView.evaluateJavascript(addressScript, null)
                        }

                        // =================================================
                        // 经纬度数据：不管地址查没查，每一次都必须更新！(1秒1次)
                        // =================================================
                        val data = HighFrequencyData(
                            lat = loc.latitude,
                            lng = loc.longitude,
                            timeDiff = loc.time - System.currentTimeMillis()
                            // 如果你想把 address 放进这里也可以： address = cachedAddress
                        )
                        val jsonData = gson.toJson(data)
                        val script = "window.JSBridge.updateDashboard('$jsonData')"
                        webView.evaluateJavascript(script, null)
                    }
                }
            }

            // 任务2：监听GPS信号 (保持不变)
            launch {
                gpsSignalUpdater.gpsLevelFlow.collect { gpsLevel ->
                    val script = "window.JSBridge.updateGpsSignal($gpsLevel)"
                    webView.evaluateJavascript(script, null)
                }
            }

            // 任务3：监听网络信号 (保持不变)
            launch {
                networkStatusProvider.networkStatusFlow.collect { networkStatus ->
                    val script = "window.JSBridge.updateNetSignal(${networkStatus.signalLevel})"
                    webView.evaluateJavascript(script, null)
                }
            }
        }

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
