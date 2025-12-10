package com.example.roadinspection.util

import android.os.Looper
import android.webkit.WebView
import com.example.roadinspection.data.model.DashboardData
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.Date
import kotlin.random.Random

/**
 * 负责持续向 WebView 更新仪表盘数据。
 * @param webView 需要接收数据的 WebView 实例。
 */
class DashboardUpdater(private val webView: WebView) {

    // 使用 MainScope，因为更新 UI (WebView) 必须在主线程
    private val scope = CoroutineScope(Dispatchers.Main)
    private var updateJob: Job? = null
    private val gson = Gson()

    /**
     * 开始以每秒一次的频率发送数据。
     */
    fun start() {
        // 如果任务已在运行，则先停止
        stop()
        updateJob = scope.launch {
            while (isActive) {
                // 1. 生成模拟数据 (将来替换为真实数据源)
                val mockData = createMockDashboardData()

                // 2. 将数据对象转换为 JSON 字符串
                val jsonData = gson.toJson(mockData)

                // 3. 构建并执行 JavaScript 调用
                // 注意对 JSON 字符串中的特殊字符进行转义，尽管 Gson 通常会处理好
                val script = "window.JSBridge.updateDashboard('$jsonData')"
                
                // 确保在主线程中调用 evaluateJavascript
                webView.evaluateJavascript(script, null)

                // 4. 等待 1 秒
                delay(1000)
            }
        }
    }

    /**
     * 停止发送数据。
     */
    fun stop() {
        updateJob?.cancel()
        updateJob = null
    }

    // --- 私有辅助方法 ---

    private fun createMockDashboardData(): DashboardData {
        // TODO: 在这里替换为从真实服务（GPS, 网络监听器）获取的数据
        return DashboardData(
            timestamp = System.currentTimeMillis(),
            lat = 31.230391 + Random.nextDouble(-0.01, 0.01),
            lng = 121.473701 + Random.nextDouble(-0.01, 0.01),
            netType = listOf("5G", "4G", "WIFI").random(),
            netLevel = Random.nextInt(1, 5),
            gpsLevel = Random.nextInt(2, 5),
            totalDistance = 1250.5 + Random.nextDouble(0.0, 100.0),
            isInspecting = true
        )
    }
}
