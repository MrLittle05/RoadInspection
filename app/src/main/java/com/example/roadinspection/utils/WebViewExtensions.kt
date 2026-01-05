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