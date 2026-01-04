package com.example.roadinspection.data.source.remote

import android.content.Context
import android.util.Log
import com.alibaba.sdk.android.oss.ClientConfiguration
import com.alibaba.sdk.android.oss.ClientException
import com.alibaba.sdk.android.oss.OSSClient
import com.alibaba.sdk.android.oss.ServiceException
import com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider
import com.alibaba.sdk.android.oss.model.PutObjectRequest
import com.alibaba.sdk.android.oss.callback.OSSCompletedCallback
import com.alibaba.sdk.android.oss.model.PutObjectResult
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 阿里云 OSS 上传辅助类。
 *
 * **职责：**
 * 1. 初始化 OSS 客户端 (使用 STS 临时凭证)。
 * 2. 执行文件上传操作。
 * 3. 生成公网可访问的图片 URL。
 */
object OssHelper {

    private const val TAG = "OssHelper"

    /**
     * 上传图片到阿里云 OSS (协程挂起函数)。
     *
     * @param context Android 上下文
     * @param localPath 本地文件绝对路径
     * @param taskId 关联的任务 ID (用于生成云端文件夹路径)
     * @param credentials 从后端 /api/oss/sts 获取的临时凭证 [cite: 29]
     * @return 上传成功后的完整网络 URL (serverUrl)
     * @throws Exception 上传失败时抛出异常
     */
    suspend fun uploadImage(
        context: Context,
        localPath: String,
        taskId: String,
        credentials: StsCredentials
    ): String = suspendCancellableCoroutine { continuation ->

        val file = File(localPath)
        if (!file.exists()) {
            continuation.resumeWithException(IllegalArgumentException("文件不存在: $localPath"))
            return@suspendCancellableCoroutine
        }

        // 1. 配置认证信息 (使用 STS Token)
        val credentialProvider = OSSStsTokenCredentialProvider(
            credentials.accessKeyId,
            credentials.accessKeySecret,
            credentials.stsToken
        )

        // 2. 配置网络参数
        val conf = ClientConfiguration().apply {
            connectionTimeout = 15 * 1000 // 连接超时 15秒
            socketTimeout = 15 * 1000     // Socket 超时 15秒
            maxConcurrentRequest = 5      // 最大并发数
        }

        // 3. 创建 OSSClient 实例
        // endpoint 示例: https://oss-cn-shanghai.aliyuncs.com
        val endpoint = "https://${credentials.region}.aliyuncs.com"
        val oss = OSSClient(context, endpoint, credentialProvider, conf)

        // 4. 构建 ObjectKey (云端文件名)
        // 格式: images/{taskId}/{timestamp}_{fileName}
        val objectKey = "images/$taskId/${System.currentTimeMillis()}_${file.name}"

        // 5. 构建上传请求
        val put = PutObjectRequest(credentials.bucket, objectKey, localPath)

        Log.d(TAG, "开始上传: $localPath -> $objectKey")

        // 6. 异步执行上传
        val task = oss.asyncPutObject(put, object:
            OSSCompletedCallback<PutObjectRequest, PutObjectResult> {

            override fun onSuccess(request: PutObjectRequest?, result: PutObjectResult?) {
                Log.d(TAG, "上传成功: ETag=${result?.eTag}")

                // 7. 拼接公网访问 URL
                // 格式: https://{bucket}.{region}.aliyuncs.com/{objectKey}
                // 注意：如果 bucket 是私有的，这里还需要进行签名。假设目前是公共读或需后端签名，
                // 这里先返回基础 URL 供后端存储。
                val url = "https://${credentials.bucket}.${credentials.region}.aliyuncs.com/$objectKey"

                // 恢复协程，返回 URL
                if (continuation.isActive) {
                    continuation.resume(url)
                }
            }

            override fun onFailure(request: PutObjectRequest?, clientException: ClientException?, serviceException: ServiceException?) {
                val errorMsg = clientException?.message ?: serviceException?.rawMessage ?: "未知错误"
                Log.e(TAG, "上传失败: $errorMsg")

                // 恢复协程，抛出异常
                if (continuation.isActive) {
                    continuation.resumeWithException(Exception("OSS上传失败: $errorMsg"))
                }
            }
        })

        // 支持协程取消时中断上传任务
        continuation.invokeOnCancellation {
            task.cancel()
        }
    }
}