package com.example.roadinspection

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher

/**
 * 对应文档 "3. 接口定义：JS -> Java"
 * 此类的方法将被注入到 window.AndroidNative 对象中
 */
class WebAppInterface(
    private val context: Context,
    private val selectImageLauncher: ActivityResultLauncher<String>
) {

    @JavascriptInterface
    fun startInspection() {
        showToast("开始巡检：Native 收到指令")
        // TODO: 启动 GPS 监听服务
    }

    @JavascriptInterface
    fun stopInspection() {
        showToast("停止巡检：数据已保存")
        // TODO: 停止服务，保存数据库
    }

    @JavascriptInterface
    fun manualCapture() {
        showToast("正在抓拍...")
        // TODO: 调用相机 API
    }

    @JavascriptInterface
    fun selectImage() {
        selectImageLauncher.launch("image/*")
    }

    @JavascriptInterface
    fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

}