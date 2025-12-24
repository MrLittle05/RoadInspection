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
import com.example.roadinspection.domain.location.LocationProvider
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

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
    private val locationProvider: LocationProvider,
    private val imageCapture: ImageCapture?,
    private val onImageSaved: (Uri) -> Unit,
    private val scope: CoroutineScope
) : WebAppInterface { // 实现 WebAppInterface 接口

    private val PHOTO_INTERVAL_METERS = 10.0

    private var autoCaptureJob: Job? = null

    private var lastCaptureDistance = 0.0

    @JavascriptInterface
    override fun startInspection() {
        showToast("开始巡检：Native 收到指令")
        // TODO: 启动 GPS 监听服务
        locationProvider.resetDistanceCounter()
        lastCaptureDistance = 0.0
        startAutoCaptureMonitoring()
    }

    @JavascriptInterface
    override fun stopInspection() {
        showToast("停止巡检：数据已保存")
        // TODO: 停止服务，保存数据库
        locationProvider.stopDistanceCounter()
        stopAutoCaptureMonitoring()
    }

    @JavascriptInterface
    override fun manualCapture() {
        takePhoto(isAuto = false)
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

    private fun startAutoCaptureMonitoring() {
        // 先取消旧任务，防止重复开启
        stopAutoCaptureMonitoring()

        autoCaptureJob = scope.launch {
            // 监听距离流
            locationProvider.distanceFlow.collectLatest { totalDistance ->
                // 核心判断逻辑：当前总里程 - 上次拍照里程 >= 设定间隔
                if (totalDistance - lastCaptureDistance >= PHOTO_INTERVAL_METERS) {

                    // 更新“上次拍照里程”为当前里程（这样下一次就是 10m+10m=20m 后）
                    lastCaptureDistance = totalDistance

                    // 执行拍照
                    // 注意：takePhoto 内部使用了 MainExecutor，所以这里可以直接调
                    takePhoto(isAuto = true)
                }
            }
        }
    }

    private fun stopAutoCaptureMonitoring() {
        autoCaptureJob?.cancel()
        autoCaptureJob = null
    }

    private fun takePhoto(isAuto: Boolean = false) {
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())

        // ... (ContentValues 代码保持不变) ...
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RoadInspection")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture?.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    // 自动拍照失败时，只在 Log 报错，避免弹 Toast 干扰用户
                    if (!isAuto) showToast("拍照失败: ${exc.message}")
                    else android.util.Log.e("AutoCapture", "Fail: ${exc.message}")
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: return
                    // 自动拍照成功通常不需要弹 Toast，太吵了
                    if (!isAuto) showToast("保存成功")
                    onImageSaved(savedUri)
                }
            }
        )
    }
}
