package com.example.roadinspection.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository = InspectionRepository(AppDatabase.getDatabase(context).inspectionDao())

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "🧹 开始执行本地图片存储清理...")

            // 策略：保留最近 1 天的数据，更早之前的已同步数据将被删除
            val retentionDays = 1
            val count = repository.clearExpiredFiles(retentionDays)

            Log.i(TAG, "✅ 清理完成，释放图片: $count 张")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ 清理失败", e)
            Result.failure()
        }
    }

    companion object {
        const val TAG = "CleanupWorker"
    }
}