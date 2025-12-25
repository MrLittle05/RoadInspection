package com.example.roadinspection.data.source.local

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.example.roadinspection.domain.inspection.InspectionManager

/**
 * WebAppInterface 接口的具体实现类 (Class)。
 * 它负责处理所有来自 JavaScript 的具体调用逻辑。
 */
class WebAppInterfaceImpl(
    private val inspectionManager: InspectionManager,
    private val context: Context,
    private val selectImageLauncher: ActivityResultLauncher<String>,
    private val onImageSaved: (Uri) -> Unit,
) : WebAppInterface {

    @JavascriptInterface
    override fun startInspection() {
        showToast("开始巡检：Native 收到指令")
        inspectionManager.startInspection()
    }

    @JavascriptInterface
    override fun stopInspection() {
        showToast("停止巡检：数据已保存")
        inspectionManager.stopInspection()
    }

    @JavascriptInterface
    override fun manualCapture() {
        inspectionManager.manualCapture { savedUri ->
            onImageSaved(savedUri)
            showToast("手动拍照成功")
            Log.d("WebAppInterface", "收到回调：手动拍照成功！URI: $savedUri")
        }
    }

    @JavascriptInterface
    override fun openGallery(type: String) {
        when (type) {
            "all" -> {
                selectImageLauncher.launch("image/*")
            }

            "route" -> {
                showToast("该功能暂未实现")
            }

            else -> {
                showToast("未知的相册类型: $type")
            }
        }
    }

    @JavascriptInterface
    override fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

}