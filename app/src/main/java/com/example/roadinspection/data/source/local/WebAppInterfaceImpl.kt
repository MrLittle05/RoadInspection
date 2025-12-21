package com.example.roadinspection.data.source.local

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * WebAppInterface 接口的具体实现类 (Class)。
 * 它负责处理所有来自 JavaScript 的具体调用逻辑。
 * @param context 安卓应用上下文，用于显示 Toast 等系统服务。
 * @param selectImageLauncher ActivityResultLauncher，用于启动相册选择器。
 * @param onImageSaved 回调函数，在图片保存后调用
 */
class WebAppInterfaceImpl(
    private val context: Context,
    private val selectImageLauncher: ActivityResultLauncher<String>,
    private val imageCapture: ImageCapture?,
    private val onImageSaved: (Uri) -> Unit
) : WebAppInterface { // 实现 WebAppInterface 接口

    @JavascriptInterface
    override fun startInspection() {
        showToast("开始巡检：Native 收到指令")
        // TODO: 启动 GPS 监听服务
    }

    @JavascriptInterface
    override fun stopInspection() {
        showToast("停止巡检：数据已保存")
        // TODO: 停止服务，保存数据库
    }

    @JavascriptInterface
    override fun manualCapture() {
        showToast("正在抓拍...")
        takePhoto()
    }

    private fun takePhoto() {
        // 1. 创建文件名和存储位置
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RoadInspection")
            }
        }

        // 2. 创建一个 OutputFileOptions 对象
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // 3. 调用 takePicture
        imageCapture?.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    showToast("拍照失败: ${exc.message}")
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: return
                    showToast("照片保存成功: $savedUri")
                    // 调用回调函数，将 savedUri 传递给 MainActivity
                    onImageSaved(savedUri)
                }
            }
        )
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
