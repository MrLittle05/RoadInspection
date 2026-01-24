package com.example.roadinspection.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.AppDatabase
import com.example.roadinspection.data.source.remote.CreateTaskReq
import com.example.roadinspection.data.source.remote.FinishTaskReq
import com.example.roadinspection.data.source.remote.OssHelper
import com.example.roadinspection.data.source.remote.SubmitRecordReq
import com.example.roadinspection.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 移除：不再需要 AddressProvider
// import com.example.roadinspection.domain.address.AddressProvider

class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository = InspectionRepository(AppDatabase.getDatabase(context).inspectionDao())
    private val api = NetworkModule.api

    // 移除：不需要在这里初始化 AddressProvider 了
    // private val addressProvider = AddressProvider(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "🚀 后台同步任务开始执行...")

        try {
            // ==============================================================
            // STEP 1: 同步新建的任务 (Create Task)
            // ==============================================================
            val unsyncedTasks = repository.getUnsyncedTasks()
            for (task in unsyncedTasks) {
                Log.d(TAG, "同步新任务: ${task.title} (${task.taskId})")

                val req = CreateTaskReq(
                    taskId = task.taskId,
                    title = task.title,
                    inspectorId = task.inspectorId,
                    startTime = task.startTime,
                    endTime = task.endTime
                )

                val response = api.createTask(req)
                if (response.isSuccess) {
                    val newState = if (task.isFinished) 2 else 1
                    repository.updateTaskSyncState(task.taskId, newState)
                } else {
                    Log.e(TAG, "任务同步失败: ${response.message}")
                    return@withContext Result.retry()
                }
            }

            // ==============================================================
            // STEP 2: 同步图片记录 (Record Sync Loop)
            // ==============================================================
            while (true) {
                val records = repository.getBatchUnfinishedRecords(limit = 5)
                if (records.isEmpty()) break

                // 获取 STS Token
                val stsResponse = api.getStsToken()
                if (!stsResponse.isSuccess || stsResponse.data == null) {
                    Log.e(TAG, "STS Token 获取失败")
                    return@withContext Result.retry()
                }
                val credentials = stsResponse.data

                for (record in records) {
                    var currentRecord = record

                    // 🗑️ 删除：原本这里的 "地址补全逻辑" 已经全部删除了
                    // 现在的逻辑是：如果 InspectionManager 存进来的是空字符串，这里就直接透传空字符串

                    // --- Phase A: 上传 OSS (State 0 -> 1) ---
                    if (currentRecord.syncStatus == 0) {
                        try {
                            Log.d(TAG, "开始上传图片到 OSS: ${currentRecord.localPath}")
                            val ossUrl = OssHelper.uploadImage(
                                applicationContext,
                                currentRecord.localPath,
                                currentRecord.taskId,
                                credentials
                            )

                            currentRecord = currentRecord.copy(
                                serverUrl = ossUrl,
                                syncStatus = 1
                            )
                            repository.updateRecord(currentRecord)
                        } catch (e: Exception) {
                            Log.e(TAG, "OSS 上传异常: ${e.message}")
                            continue
                        }
                    }

                    // --- Phase B: 提交后端 (State 1 -> 2) ---
                    if (currentRecord.syncStatus == 1 && currentRecord.serverUrl != null) {
                        Log.d(TAG, "提交元数据到后端: ${currentRecord.id}")

                        // 直接构造请求，address 如果是空，就发空的给后端
                        val req = SubmitRecordReq(
                            taskId = currentRecord.taskId,
                            serverUrl = currentRecord.serverUrl!!,
                            latitude = currentRecord.latitude,
                            longitude = currentRecord.longitude,
                            address = currentRecord.address, // 这里可能是 String? 或者是 ""
                            captureTime = currentRecord.captureTime
                        )

                        val res = api.submitRecord(req)
                        if (res.isSuccess) {
                            repository.updateRecord(currentRecord.copy(syncStatus = 2))
                        } else {
                            Log.w(TAG, "元数据提交失败: ${res.message}")
                        }
                    }
                }
            }

            // ==============================================================
            // STEP 3: 同步任务结束状态 (Task Finish)
            // ==============================================================
            val tasksToFinish = repository.getFinishedButNotSyncedTasks()
            for (task in tasksToFinish) {
                if (task.endTime != null) {
                    val res = api.finishTask(FinishTaskReq(task.taskId, task.endTime))
                    if (res.isSuccess) {
                        repository.updateTaskSyncState(task.taskId, 2)
                    }
                }
            }

            Log.i(TAG, "✅ 所有同步任务执行完毕")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker 执行异常", e)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "UploadWorker"
    }
}