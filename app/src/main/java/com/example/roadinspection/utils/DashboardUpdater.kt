package com.example.roadinspection.utils

import android.util.Log
import android.webkit.WebView
import com.example.roadinspection.data.model.HighFrequencyData
import com.example.roadinspection.domain.location.GpsSignalProvider
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.network.NetworkStatusProvider
import com.example.roadinspection.data.repository.InspectionRepository
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * 负责将业务层的数据（位置、GPS、网络）分发给 WebView 前端的更新器。
 *
 * 该类作为 Presenter 层与 View (WebView) 的桥梁，采用响应式流 (Kotlin Flow) 设计。
 * 它并行监听各个 Provider 的数据流，经过处理和节流后，通过 JSBridge 调用前端方法。
 *
 * 主要功能：
 * 1. 整合位置与距离数据，高频刷新仪表盘。
 * 2. 监听地址变化，低频推送到前端（自动去重）。
 * 3. 监听环境状态（GPS 信号、网络信号）。
 *
 * @property webView 用于执行 JS 代码的 WebView 实例。
 * @property locationProvider 提供经纬度、时间、extras（地址）等位置信息。
 * @property gpsSignalProvider 提供 GPS 卫星信号强度信息。
 * @property networkStatusProvider 提供移动网络信号强度信息。
 */
class DashboardUpdater(
    private val webView: WebView,
    private val locationProvider: LocationProvider,
    private val gpsSignalProvider: GpsSignalProvider,
    private val networkStatusProvider: NetworkStatusProvider,
    private val repository: InspectionRepository
) {

    /**
     * 运行数据监听任务的协程作用域。
     * 绑定主线程 (Main)，并使用 [SupervisorJob] 确保子任务异常不会导致整个作用域崩溃。
     */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 用于序列化数据对象为 JSON 字符串。
     */
    private val gson = Gson()

    companion object {
        private const val TAG = "DashboardUpdater"
    }

    /**
     * 启动所有并行的数据监听任务。
     *
     * 该方法会先取消之前可能存在的任务，然后重新启动以下流的监听：
     * * [startHighFrequencyDataUpdates] - 仪表盘数据
     * * [startAddressUpdates] - 地址信息
     * * [startGpsStatusUpdates] - GPS 信号
     * * [startNetworkStatusUpdates] - 网络信号
     *
     * 最后调用 [LocationProvider.startLocationUpdates]
     * [GpsSignalProvider.startGpsSignalUpdates] 激活定位硬件。
     */
    fun start() {
        scope.coroutineContext.cancelChildren()

        // 1. 高频任务：位置 + 距离 更新
        startHighFrequencyDataUpdates()

        // 2. 低频任务：地址更新
        startAddressUpdates()

        // 3. 低频任务：GPS 信号强度更新
        startGpsStatusUpdates()

        // 4. 低频任务：网络状态更新
        startNetworkStatusUpdates()

        // 4. 地址
        locationProvider.startLocationUpdates()

        gpsSignalProvider.startGpsSignalUpdates()

        // 5. 待上传图片数量
        startInspectionCountUpdates()
    }

    /**
     * 停止所有数据更新任务并取消协程。
     * 通常在 Activity/Fragment 的 onDestroy 中调用。
     */
    fun stop() {
        scope.coroutineContext.cancelChildren()
    }

    /**
     * 启动高频数据更新任务。
     *
     * 将位置流 ([LocationProvider.getLocationFlow]) 和距离流 ([LocationProvider.getDistanceFlow]) 合并。
     *
     * **前端交互：**
     * 调用 `window.JSBridge.updateDashboard(json)`
     *
     * @see HighFrequencyData
     */
    private fun startHighFrequencyDataUpdates() {
        scope.launch {
            combine(
                locationProvider.getLocationFlow(),
                locationProvider.getDistanceFlow()
            ) { location, totalDistance ->
                if (location != null) {
                    HighFrequencyData(
                        timeDiff = location.time - System.currentTimeMillis(),
                        lat = location.latitude,
                        lng = location.longitude,
                        totalDistance = totalDistance
                    )
                } else {
                    null
                }
            }.collectLatest { data ->
                data?.let {
                    Log.v(TAG, "📡 高频数据更新: Lat=${it.lat}, Lng=${it.lng}, Dist=${it.totalDistance}m")
                    val json = gson.toJson(it)
                    webView.evaluateJavascript("window.JSBridge.updateDashboard('$json')", null)
                }
            }
        }
    }

    /**
     * 启动地址更新任务。
     *
     * 从位置流中提取 `extras` 里的地址信息。
     * 使用 [distinctUntilChanged] 操作符进行流式去重，仅当地址字符串发生实质变化时才通知前端。
     *
     * **前端交互：**
     * 调用 `window.JSBridge.updateAddress(addressString)`
     */
    private fun startAddressUpdates() {
        scope.launch {
            locationProvider.getLocationFlow()
                .filterNotNull()
                .map { location ->
                    val address = location.extras?.getString("address") ?: "获取地址失败"
                    Log.d(TAG, "流中获取到的地址: $address")
                    address
                }
                .distinctUntilChanged() // KDoc: 仅当下游数据与上一次发射的数据不同时才通过
                .collectLatest { address ->
                    Log.d(TAG, "发送给前端的新地址: $address")
                    webView.evaluateJavascript("window.JSBridge.updateAddress('$address')", null)
                }
        }
    }

    /**
     * 启动 GPS 信号强度更新任务。
     *
     * **前端交互：**
     * 调用 `window.JSBridge.updateGpsLevel(level)`
     */
    private fun startGpsStatusUpdates() {
        scope.launch {
            gpsSignalProvider.getGpsLevelFlow()
                .collect { level ->
                    Log.d(TAG, "🛰️ GPS 信号等级: $level")
                    val script = "window.JSBridge.updateGpsLevel($level)"
                    webView.evaluateJavascript(script, null)
                }
        }
    }

    /**
     * 启动网络信号强度更新任务。
     *
     * 仅当信号等级发生变化时才通知前端。
     *
     * **前端交互：**
     * 调用 `window.JSBridge.updateNetLevel(level)`
     */
    private fun startNetworkStatusUpdates() {
        scope.launch {
            networkStatusProvider.networkStatusFlow
                .map { it.signalLevel }
                .distinctUntilChanged()
                .collect { level ->
                    Log.d(TAG, "🛰️ 网络信号等级: $level")
                    val script = "window.JSBridge.updateNetLevel($level)"
                    webView.evaluateJavascript(script, null)
                }
        }
    }

    private fun startInspectionCountUpdates() {
        scope.launch {
            repository.unfinishedCount.collect { count ->
                Log.d(TAG, "推送给前端待上传数: $count")
                val jsCode = "javascript:window.updateUploadCount($count)"
                webView.evaluateJavascript(jsCode, null)
                }
        }
    }

//    private fun updateUploadCount(count: Int) {
//        // 这里的 "updateUploadCount" 是前端网页里定义好的 JS 方法名
//        val jsCode = "javascript:window.updateUploadCount($count)"
//
//        // 必须在主线程更新 UI
//        webView.post {
//            webView.evaluateJavascript(jsCode, null)
//        }
//
//        Log.d(TAG, "推送给前端待上传数: $count")
//    }
}