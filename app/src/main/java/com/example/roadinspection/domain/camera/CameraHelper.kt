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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // 引入协程作用域，专门用于并发处理耗时的图片解码和 WebP 压缩任务
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                    // 使用协程切回主线程
                    helperScope.launch(Dispatchers.Main) { onError(msg) }
                }

                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        // 1. 【极速操作】：把画面数据立刻拷进内存 (ByteArray)
                        val buffer = imageProxy.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                        // 2. 【核心修复】：秒关 ImageProxy！把相机缓冲区还给底层，准备拍下一张！
                        imageProxy.close()

                        // 3. 【异步卸载】：把这堆字节数据丢给 IO 线程池去慢慢解码和压缩
                        helperScope.launch {
                            try {
                                // 这里的逻辑全部在后台多线程并发执行，不会卡住 cameraExecutor
                                val bitmap = decodeAndRotateBitmap(bytes, rotationDegrees)
                                if (bitmap != null) {
                                    val savedUri = saveBitmapAsWebP(bitmap)

                                    // 成功：切回主线程返回结果
                                    withContext(Dispatchers.Main) { onSuccess(savedUri) }
                                } else {
                                    withContext(Dispatchers.Main) { onError("Failed to decode bitmap") }
                                }
                            } catch (e: Exception) {
                                Log.e("CameraHelper", "Process failed", e)
                                withContext(Dispatchers.Main) { onError("Image processing failed: ${e.message}") }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CameraHelper", "Extraction failed", e)
                        imageProxy.close() // 兜底：发生异常也必须释放相机内存
                        helperScope.launch(Dispatchers.Main) { onError("Extraction failed: ${e.message}") }
                    }
                }
            }
        )
    }

    /**
     * 将 ByteArray 转换为 Bitmap 并根据 Exif 修正方向 (纯内存运算，丢在后台执行)
     */
    private fun decodeAndRotateBitmap(bytes: ByteArray, rotationDegrees: Int): Bitmap? {
        val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        if (rotationDegrees == 0) return originalBitmap

        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())

        return Bitmap.createBitmap(
            originalBitmap,
            0, 0,
            originalBitmap.width, originalBitmap.height,
            matrix,
            true
        )
    }

    /**
     * 将 Bitmap 压缩为 WebP 保存原图，同时使用 JPEG 极速生成一张极小尺寸的缩略图
     * (高耗时 I/O 操作，已丢在 helperScope 后台执行)
     */
    private fun saveBitmapAsWebP(bitmap: Bitmap): Uri {
        val storageDir = File(context.getExternalFilesDir(null), "Pictures/RoadInspection")
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        // 1. 生成基础文件名 (不含后缀)
        val baseFileName = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.CHINA)
            .format(System.currentTimeMillis())

        // 定义原图(WebP)和缩图(JPEG)的文件对象
        val originalFile = File(storageDir, "$baseFileName.webp")
        val thumbFile = File(storageDir, "${baseFileName}_thumb.jpg")

        // 2. 保存高画质原图 (维持 WebP，节省大量存储空间)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        FileOutputStream(originalFile).use { stream ->
            bitmap.compress(format, 90, stream)
        }

        // 3. 生成并保存极小尺寸的缩略图 (改用 JPEG，编码速度极快)
        val targetWidth = 300
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetHeight = (targetWidth / aspectRatio).toInt()

        // Android 原生内存缩放 API
        val thumbBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

        FileOutputStream(thumbFile).use { stream ->
            // 缩略图直接用 JPEG 格式，80 品质即可，瞬间完成
            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        }

        // 释放缩图占用的内存 (防止连续拍照 OOM)
        if (thumbBitmap != bitmap) {
            thumbBitmap.recycle()
        }

        // 依然返回原图的 Uri 给调用方，保证数据库里存的 localPath 还是原图路径
        return Uri.fromFile(originalFile)
    }
}