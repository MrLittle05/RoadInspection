package com.example.roadinspection.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import androidx.work.PeriodicWorkRequestBuilder

object WorkManagerConfig {

    /**
     * 调度一次上传任务。
     * 建议在以下时机调用：
     * 1. 每次拍照保存后。
     * 2. 用户点击“手动同步”按钮时。
     * 3. App 启动时。
     */
    fun scheduleUpload(context: Context) {
        // 1. 定义约束条件：必须有网络连接
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 2. 构建任务请求
        val uploadRequest = OneTimeWorkRequest.Builder(UploadWorker::class.java)
            .setConstraints(constraints)
            // 3. 设置指数退避策略：失败后等待 1 分钟，然后 2 分钟，4 分钟...
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                1, TimeUnit.MINUTES
            )
            .build()

        // 4. 加入队列 (使用 KEEP 策略：如果已有任务在跑，就保留原来的，不重复添加)
        WorkManager.getInstance(context).enqueueUniqueWork(
            "RoadInspectionUpload",
            ExistingWorkPolicy.KEEP,
            uploadRequest
        )
    }


    /**
     * 调度每日清理任务
     * 建议在 MainActivity 的 onCreate 或 Application 初始化中调用一次
     */
    fun scheduleDailyCleanup(context: Context) {
        val cleanupRequest = PeriodicWorkRequestBuilder<CleanupWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "DailyCleanup",
            ExistingPeriodicWorkPolicy.KEEP, // 如果已存在任务，则不重复添加
            cleanupRequest
        )
    }

    /**
     * 调度删除同步任务。
     * 建议在用户点击删除并确认后立即调用。
     */
    fun scheduleDeletion(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val deleteRequest = OneTimeWorkRequest.Builder(DeleteWorker::class.java)
            .setConstraints(constraints)
            // 删除操作通常需要尽快执行，可以使用 Expedited (加急)
            // 注意：Android 12+ 使用 Expedited 需要额外配置前台服务通知，这里简化为普通请求
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10, TimeUnit.SECONDS // 失败后较快重试
            )
            .build()

        // 使用 APPEND_OR_REPLACE 策略：
        // 如果已有删除任务在跑，就把新的追加到后面，确保按顺序执行
        WorkManager.getInstance(context).enqueueUniqueWork(
            "InspectionTaskDelete",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            deleteRequest
        )
    }
}