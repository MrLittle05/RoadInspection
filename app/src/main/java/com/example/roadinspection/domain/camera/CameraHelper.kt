package com.example.roadinspection.domain.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class CameraHelper(
    private val context: Context,
    private val imageCapture: ImageCapture?
) {
    // 创建一个单线程池用于后台处理图片压缩，避免阻塞主线程（UI线程）
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    fun takePhoto(
        isAuto: Boolean, // 此参数暂未使用，保留接口
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        if (imageCapture == null) {
            onError("相机未初始化")
            return
        }

        // 1. 使用 OnImageCapturedCallback 获取内存中的图片数据（而不是直接存文件）
        imageCapture.takePicture(
            cameraExecutor, // 在后台线程执行，防止卡顿
            object : ImageCapture.OnImageCapturedCallback() {

                override fun onError(exc: ImageCaptureException) {
                    val msg = "Photo capture failed: ${exc.message}"
                    Log.e("CameraHelper", msg, exc)
                    // 切回主线程回调错误信息
                    ContextCompat.getMainExecutor(context).execute {
                        onError(msg)
                    }
                }

                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        // 2. 将 ImageProxy 转为 Bitmap 并处理旋转
                        val bitmap = imageProxyToBitmap(imageProxy)

                        // 3. 关闭 imageProxy，释放相机缓冲区的引用
                        imageProxy.close()

                        if (bitmap != null) {
                            // 4. 执行 WebP 压缩并保存到私有目录
                            val savedUri = saveBitmapAsWebP(bitmap)

                            // 成功：切回主线程返回结果
                            ContextCompat.getMainExecutor(context).execute {
                                onSuccess(savedUri)
                            }
                        } else {
                            throw Exception("Failed to decode bitmap")
                        }
                    } catch (e: Exception) {
                        Log.e("CameraHelper", "Process failed", e)
                        ContextCompat.getMainExecutor(context).execute {
                            onError("Image processing failed: ${e.message}")
                        }
                        imageProxy.close() // 确保异常时也关闭
                    }
                }
            }
        )
    }

    /**
     * 将 ImageProxy 转换为 Bitmap，并根据 Exif 旋转角度修正方向
     */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // 解码为原始 Bitmap
        val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        // 获取旋转角度 (CameraX 会自动计算好需要的补偿角度)
        val rotationDegrees = image.imageInfo.rotationDegrees

        // 如果没有旋转，直接返回
        if (rotationDegrees == 0) return originalBitmap

        // 创建矩阵进行旋转
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())

        // 生成旋转后的新 Bitmap
        return Bitmap.createBitmap(
            originalBitmap,
            0, 0,
            originalBitmap.width, originalBitmap.height,
            matrix,
            true
        )
    }

    /**
     * 将 Bitmap 压缩为 WebP 并保存到应用私有目录
     */
    private fun saveBitmapAsWebP(bitmap: Bitmap): Uri {
        // 1. 准备文件：应用私有目录 /files/Pictures/RoadInspection
        // 这种路径卸载 App 后会被删除，且不需要用户额外授权
        val storageDir = File(context.getExternalFilesDir(null), "Pictures/RoadInspection")
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        val fileName = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.CHINA)
            .format(System.currentTimeMillis()) + ".webp"

        val photoFile = File(storageDir, fileName)

        // 2. 选择 WebP 格式 (适配 Android 版本)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY // Android 11+ 明确指定有损压缩
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP // 旧版本
        }

        // 3. 写入文件流
        FileOutputStream(photoFile).use { stream ->
            // 质量 90%
            bitmap.compress(format, 90, stream)
        }

        // 4. 返回文件的 Uri
        return Uri.fromFile(photoFile)
    }
}