package com.example.roadinspection.utils

import android.net.Uri
import android.webkit.WebView

/**
 * 统一封装：通知 JS 更新最新图片的逻辑。
 * 任何持有 WebView 的地方都可以直接调用 webView.notifyJsUpdatePhoto(uri)
 */
fun WebView.notifyJsUpdatePhoto(uri: Uri) {
    val script = "window.JSBridge.updateLatestPhoto('$uri')"
    this.post {
        this.evaluateJavascript(script, null)
    }
}

/**
 * 统一封装：通知 JS 更新 IRI 图表数据。
 *
 * @param iriValue 计算出的 IRI 值 (Y轴)
 * @param segmentDistance 本次计算覆盖的距离 (X轴增量，或者由前端累加)
 */
fun WebView.notifyJsUpdateIri(iriValue: Float, segmentDistance: Float) {
    // 参数1: IRI数值, 参数2: 距离段长
    val script = "window.JSBridge.updateIriData($iriValue, $segmentDistance)"
    this.post {
        this.evaluateJavascript(script, null)
    }
}

/**
 * 通用封装：安全地调用 JS 定义的全局回调函数。
 *
 * 1. 自动处理线程切换：内部使用 post，确保 evaluateJavascript 在 UI 线程执行。
 * 2. 自动构建脚本：封装了 window.method && window.method(...) 的安全调用检查。
 *
 * @param methodName JS 全局函数名 (e.g., "onTasksReceived")
 * @param jsonParams JSON 数据字符串 (e.g., "[{...}]" 或 "{...}")。
 * 如果是普通字符串，请确保已自行添加引号，建议直接传 Gson 序列化后的 JSON。
 */
fun WebView.invokeJsCallback(methodName: String, jsonParams: String) {
    // 构造脚本：先检查函数是否存在，再执行调用
    val script = "javascript:window.$methodName && window.$methodName($jsonParams)"

    // View.post 允许从任何线程（包括后台协程）调用，它会将 Runnable 发送到主线程队列
    this.post {
        this.evaluateJavascript(script, null)
    }
}