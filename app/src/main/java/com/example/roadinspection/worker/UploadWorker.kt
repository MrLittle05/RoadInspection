package com.example.roadinspection.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.AppDatabase
import com.example.roadinspection.data.source.local.TokenManager
import com.example.roadinspection.data.source.remote.CreateTaskReq
import com.example.roadinspection.data.source.remote.FinishTaskReq
import com.example.roadinspection.data.source.remote.OssHelper
import com.example.roadinspection.data.source.remote.SubmitRecordReq
import com.example.roadinspection.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台上传任务 Worker。
 *
 * **职责状态机：**
 * 1. **同步任务 (Task Sync)**: 将本地新建的任务信息同步给后端。
 * 2. **同步记录 (Record Sync)**:
 * - Phase A: 上传图片到 OSS (State 0 -> 1)
 * - Phase B: 提交元数据到后端 (State 1 -> 2)
 * 3. **结单同步 (Task Finish)**: 将任务的结束状态同步给后端。
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    // 初始化 Repository (Worker 无法直接依赖注入，手动获取)
    private val repository = InspectionRepository(AppDatabase.getDatabase(context).inspectionDao())

    // 获取网络接口
    private val api = NetworkModule.api

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "🚀 后台同步任务开始执行...")
        val userId = TokenManager.currentUserId ?: return@withContext Result.failure()

        try {
            // ==============================================================
            // STEP 1: 同步新建的任务 (Create Task)
            // ==============================================================
            val unsyncedTasks = repository.getUnsyncedTasks(userId)
            for (task in unsyncedTasks) {
                Log.d(TAG, "同步新任务: ${task.title} (${task.taskId})")

                // 构造请求，如果有 endTime 也带上 (支持离线结束)
                val req = CreateTaskReq(
                    taskId = task.taskId,
                    title = task.title,
                    inspectorId = task.inspectorId,
                    startTime = task.startTime,
                    endTime = task.endTime //
                )

                val response = api.createTask(req)
                if (response.isSuccess) {
                    // 如果本地已经是完成状态，直接跳到状态 2 (Finalized)，否则状态 1 (Synced)
                    val newState = if (task.isFinished) 2 else 1
                    repository.updateTaskSyncState(task.taskId, newState) //
                } else {
                    Log.e(TAG, "任务同步失败: ${response.message}")
                    return@withContext Result.retry() // 遇到错误稍后重试
                }
            }

            // ==============================================================
            // STEP 2: 同步图片记录 (Record Sync Loop)
            // ==============================================================
            // 循环处理，直到没有待处理记录 (防止一次查太多 OOM)
            while (true) {
                // 2.1 批量拉取未完成记录 (State != 2)
                val records = repository.getBatchUnfinishedRecords(limit = 5) //
                if (records.isEmpty()) break // 没数据了，跳出循环

                // 2.2 获取阿里云 STS Token (这就叫"一次获取，批量使用")
                // 如果 Token 获取失败，整个批次都无法进行，直接 Retry
                val stsResponse = api.getStsToken() //
                if (!stsResponse.isSuccess || stsResponse.data == null) {
                    Log.e(TAG, "STS Token 获取失败")
                    return@withContext Result.retry()
                }
                val credentials = stsResponse.data

                for (record in records) {
                    var currentRecord = record

                    // --- Phase A: 上传 OSS (State 0 -> 1) ---
                    if (currentRecord.syncStatus == 0) {
                        try {
                            Log.d(TAG, "开始上传图片到 OSS: ${currentRecord.localPath}")
                            val ossUrl = OssHelper.uploadImage(
                                applicationContext,
                                currentRecord.localPath,
                                currentRecord.taskId,
                                credentials
                            ) //

                            // 更新本地状态为 1 (IMAGE_UPLOADED) 并保存 URL
                            currentRecord = currentRecord.copy(
                                serverUrl = ossUrl,
                                syncStatus = 1
                            )
                            repository.updateRecord(currentRecord) //
                        } catch (e: Exception) {
                            Log.e(TAG, "OSS 上传异常: ${e.message}")
                            // 单张图片失败不阻断整个循环，但标记 Worker 为 Retry
                            continue
                        }
                    }

                    // --- Phase B: 提交后端 (State 1 -> 2) ---
                    val serverUrl = currentRecord.serverUrl
                    if (currentRecord.syncStatus == 1 && serverUrl != null) {
                        Log.d(TAG, "提交元数据到后端: ${currentRecord.recordId}")
                        val req = SubmitRecordReq(
                            recordId = currentRecord.recordId,
                            taskId = currentRecord.taskId,
                            serverUrl = serverUrl,
                            latitude = currentRecord.latitude,
                            longitude = currentRecord.longitude,
                            address = currentRecord.address,
                            captureTime = currentRecord.captureTime,
                            iri= currentRecord.iri,
                            pavementDistress = currentRecord.pavementDistress
                        )

                        val res = api.submitRecord(req)
                        if (res.isSuccess) {
                            // 最终完成：State -> 2 (SYNCED)
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
            val tasksToFinish = repository.getFinishedButNotSyncedTasks(userId)
            for (task in tasksToFinish) {
                if (task.endTime != null) {
                    Log.d(TAG, "同步任务结束状态: ${task.taskId}")
                    val res = api.finishTask(FinishTaskReq(task.taskId, task.endTime))
                    if (res.isSuccess) {
                        repository.updateTaskSyncState(task.taskId, 2) // 标记为最终一致
                    }
                }
            }

            Log.i(TAG, "✅ 所有同步任务执行完毕")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker 执行异常", e)
            Result.retry() // 遇到任何未捕获异常（如网络超时），请求系统稍后重试
        }
    }

    companion object {
        const val TAG = "UploadWorker"
    }
}