package com.example.roadinspection.data.source.remote

import android.content.Context
import android.util.Log
import com.alibaba.sdk.android.oss.ClientConfiguration
import com.alibaba.sdk.android.oss.ClientException
import com.alibaba.sdk.android.oss.OSSClient
import com.alibaba.sdk.android.oss.ServiceException
import com.alibaba.sdk.android.oss.callback.OSSCompletedCallback
import com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider
import com.alibaba.sdk.android.oss.model.PutObjectRequest
import com.alibaba.sdk.android.oss.model.PutObjectResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OssHelper {

    private const val TAG = "OssHelper"

    @Volatile
    private var ossClient: OSSClient? = null

    /**
     * 遇到不可恢复的错误时，重置客户端，强制下次重建连接
     */
    private fun invalidateClient() {
        synchronized(this) {
            try {
                // 尝试关闭旧连接池
                // 注意：OSSClient 没有 shutdown 方法，但置空可以让 GC 回收资源
                ossClient = null
                Log.w(TAG, "⚠️ 检测到严重连接错误，已重置 OSSClient 单例")
            } catch (e: Exception) {
                Log.e(TAG, "重置客户端失败", e)
            }
        }
    }

    /**
     * 确保 OSSClient 已初始化。
     * 如果已存在则更新凭证；如果不存在则创建。
     */
    private fun ensureClient(context: Context, credentials: StsCredentials, endpoint: String) {
        val credentialProvider = OSSStsTokenCredentialProvider(
            credentials.accessKeyId,
            credentials.accessKeySecret,
            credentials.stsToken
        )

        if (ossClient == null) {
            synchronized(this) {
                if (ossClient == null) {
                    Log.d(TAG, "⚡ 初始化 OSSClient (TCP连接池)...")

                    // ✅ 优化配置：降低内存占用，防止 Low Memory
                    val conf = ClientConfiguration().apply {
                        connectionTimeout = 30 * 1000 // 增加连接超时
                        socketTimeout = 30 * 1000     // 增加读取超时
                        maxErrorRetry = 2
                        maxConcurrentRequest = 2      // 📉 关键：降低并发数 (默认是5)，减少内存压力
                        isCheckCRC64 = false          // 📉 关键：关闭 CRC64 校验，减少 CPU/内存消耗，避免流读取冲突
                    }

                    ossClient = OSSClient(context.applicationContext, endpoint, credentialProvider, conf)
                }
            }
        } else {
            // 复用连接，但更新 Token
            ossClient!!.updateCredentialProvider(credentialProvider)
        }
    }

    /**
     * ✅ 新增：显式关闭连接池，释放内存。
     * 必须在 Worker 结束时调用。
     */
    fun shutdown() {
        if (ossClient != null) {
            synchronized(this) {
                if (ossClient != null) {
                    Log.w(TAG, "🛑 关闭 OSSClient，释放连接池与内存资源")
                    // 取消所有挂起的请求，并关闭内部 OkHttpClient 线程池
                    // 注意：阿里 SDK 的 API 可能是 api.cancelAll() 或者直接置空，
                    // 由于 SDK 内部是通过 OkHttp 管理的，我们主要做引用断开。
                    // 遗憾的是 OSSClient 没有公开 shutdown() 方法，
                    // 我们只能依靠 GC，或者保留实例但在下次重新 new (如果必须强杀)。
                    // 但通常，置空引用并让 Worker 进程结束是释放内存的最佳方式。
                    ossClient = null
                }
            }
        }
    }

    suspend fun uploadImage(
        context: Context,
        localPath: String,
        taskId: String,
        credentials: StsCredentials
    ): String = suspendCancellableCoroutine { continuation ->

        val cleanPath = if (localPath.startsWith("file://")) localPath.substring(7) else localPath
        val file = File(cleanPath)

        if (!file.exists() || file.length() == 0L) {
            if (continuation.isActive) {
                continuation.resumeWithException(java.io.FileNotFoundException("文件不存在: $cleanPath"))
            }
            return@suspendCancellableCoroutine
        }

        // 1. 初始化或复用
        val cleanRegion = credentials.region.removePrefix("oss-")
        val endpoint = "https://oss-${cleanRegion}.aliyuncs.com"
        ensureClient(context, credentials, endpoint)

        // 2. 构建请求
        val objectKey = "tasks/$taskId/${file.name}"
        val put = PutObjectRequest(credentials.bucket, objectKey, cleanPath)

        Log.d(TAG, "🚀 上传开始: ${file.name}")

        val task = ossClient!!.asyncPutObject(put, object : OSSCompletedCallback<PutObjectRequest, PutObjectResult> {
            override fun onSuccess(request: PutObjectRequest?, result: PutObjectResult?) {
                val url = "https://${credentials.bucket}.oss-${cleanRegion}.aliyuncs.com/$objectKey"
                if (continuation.isActive) continuation.resume(url)
            }

            override fun onFailure(request: PutObjectRequest?, clientEx: ClientException?, serviceEx: ServiceException?) {
                val errorMsg = clientEx?.message ?: serviceEx?.rawMessage ?: "未知错误"
                Log.e(TAG, "❌ 上传失败: $errorMsg")

                // [新增] 关键逻辑：检测到流关闭或Socket错误，立即自杀重置
                if (errorMsg.contains("Stream Closed", ignoreCase = true) ||
                    errorMsg.contains("Socket", ignoreCase = true) ||
                    errorMsg.contains("Connection", ignoreCase = true)) {
                    invalidateClient()
                }

                if (continuation.isActive) continuation.resumeWithException(Exception(errorMsg))
            }
        })

        continuation.invokeOnCancellation {
            if (!task.isCompleted) task.cancel()
        }
    }
}