package com.example.roadinspection.ui.bridge

import android.webkit.JavascriptInterface

/**
 * 定义了暴露给 WebView 的所有原生方法的契约 (Interface)。
 * JavaScript 端可以通过 window.AndroidNative 调用这些方法。
 * 这个接口只定义了“能做什么”，而不关心“如何做”。
 */
interface AndroidNativeApi {

    @JavascriptInterface
    fun startInspection(title: String?, currentUserId: String)

    @JavascriptInterface
    fun stopInspection()

    @JavascriptInterface
    fun manualCapture()

    @JavascriptInterface
    fun openGallery(type: String)

    @JavascriptInterface
    fun setZoom(value: Float)

    @JavascriptInterface
    fun showToast(msg: String)

    @JavascriptInterface
    fun fetchTasks(userId: String)

    @JavascriptInterface
    fun fetchRecords(taskId: String)
}