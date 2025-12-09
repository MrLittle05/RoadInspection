package com.example.roadinspection.data.source.local

import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher

/**
 * WebAppInterface 接口的具体实现类 (Class)。
 * 它负责处理所有来自 JavaScript 的具体调用逻辑。
 * @param context 安卓应用上下文，用于显示 Toast 等系统服务。
 * @param selectImageLauncher ActivityResultLauncher，用于启动相册选择器。
 */
class WebAppInterfaceImpl(
    private val context: Context,
    private val selectImageLauncher: ActivityResultLauncher<String>
) : WebAppInterface { // 实现 WebAppInterface 接口

    override fun startInspection() {
        showToast("开始巡检：Native 收到指令")
        // TODO: 启动 GPS 监听服务
    }

    override fun stopInspection() {
        showToast("停止巡检：数据已保存")
        // TODO: 停止服务，保存数据库
    }

    override fun manualCapture() {
        showToast("正在抓拍...")
        // TODO: 调用相机 API
    }

    override fun selectImage() {
        selectImageLauncher.launch("image/*")
    }

    override fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
