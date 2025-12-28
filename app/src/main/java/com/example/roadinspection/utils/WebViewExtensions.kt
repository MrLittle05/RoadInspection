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