package com.example.roadinspection.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.AppDatabase
import com.example.roadinspection.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 专门用于处理任务删除同步的后台 Worker。
 * 与 UploadWorker 分离，确保删除操作不被繁重的图片上传阻塞。
 */
class DeleteWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository = InspectionRepository(AppDatabase.getDatabase(context).inspectionDao())
    private val api = NetworkModule.api

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "🗑️ 后台删除任务启动...")

        try {
            // 1. 获取所有本地已标记为删除 (-1) 的任务
            val pendingTasks = repository.getPendingDeleteTasks()

            if (pendingTasks.isEmpty()) {
                return@withContext Result.success()
            }

            var allSuccess = true

            for (task in pendingTasks) {
                Log.d(TAG, "正在同步删除: ${task.title} (${task.taskId})")

                try {
                    // 2. 调用后端接口
                    val response = api.deleteTask(task.taskId, task.inspectorId)

                    // 3. 处理响应
                    // code == 200: 后端成功执行了软删除
                    // code == 404: 后端找不到这个任务（可能已经被删了），也视为成功
                    if (response.isSuccess || response.code == 404) {
                        Log.i(TAG, "✅ 服务器确认删除，执行本地物理清理: ${task.taskId}")
                        repository.finalizeDeletion(task.taskId)
                    } else {
                        Log.w(TAG, "❌ 删除失败: ${response.message}")
                        allSuccess = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 网络异常: ${task.taskId}", e)
                    allSuccess = false
                }
            }

            if (allSuccess) Result.success() else Result.retry()

        } catch (e: Exception) {
            Log.e(TAG, "DeleteWorker 致命错误", e)
            Result.failure()
        }
    }

    companion object {
        const val TAG = "DeleteWorker"
    }
}