package com.example.roadinspection.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkManagerConfig {

    private const val WORK_NAME = "RoadInspectionUpload"

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
            // .setRequiresBatteryNotLow(true) // 可选：电量充足
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
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            uploadRequest
        )
    }
}