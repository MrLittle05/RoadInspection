package com.example.roadinspection.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.AppDatabase
import com.example.roadinspection.data.source.local.StsCacheManager
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
        try {
            val userId = TokenManager.currentUserId
            if (userId.isNullOrEmpty()) {
                Log.e(TAG, "用户未登录，跳过同步")
                return@withContext Result.failure()
            }

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

            // 2.0 智能获取阿里云 STS Token
            if (!StsCacheManager.isValid()) {
                Log.d(TAG, "STS Token 不存在或已过期，向后端重新请求...")
                val stsResponse = api.getStsToken()

                // 如果 Token 获取失败，整个批次都无法进行，直接 Retry
                if (!stsResponse.isSuccess || stsResponse.data == null) {
                    Log.e(TAG, "❌ 无法获取 OSS 凭证，终止本次同步: ${stsResponse.message}")
                    // 返回 retry，等待网络好了下次再试
                    return@withContext Result.retry()
                }

                // expireInSeconds 由后端设置
                StsCacheManager.save(stsResponse.data, 1800)
                Log.i(TAG, "✅ STS Token 获取成功，保存到本地缓存: ${stsResponse.data}")
            } else {
                Log.d(TAG, "⚡ 命中本地 STS Token 缓存，直接复用")
            }


            val credentials = StsCacheManager.getCredentials() ?: run {
                Log.e(TAG, "❌ 组装 STS 凭证异常，终止同步")
                return@withContext Result.retry()
            }

            // 定义一个熔断标志位。一旦发生网络严重错误，设为 true 并停止后续所有上传
            var abortSync = false

            // 循环处理，直到没有待处理记录 (防止一次查太多 OOM)
            while (true) {
                // 每次拉取新批次前，先检查是否需要熔断
                if (abortSync) {
                    Log.w(TAG, "⚠️ 检测到之前发生严重上传错误，停止后续批次处理")
                    break
                }

                // 2.1 批量拉取未完成记录 (State != 2)
                val records = repository.getBatchUnfinishedRecords(limit = 5)
                if (records.isEmpty()) break // 没数据了，跳出循环

                for (record in records) {
                    // [新增] 循环内部检查：如果刚才报错了，不要再试这一批剩下的了，直接跳出
                    if (abortSync) break

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
                            Log.e(TAG, "OSS 上传异常: $e")
                            // 关键点：捕获异常后，不要 continue！
                            // 因为出现 Stream Closed 通常意味着连接池已废，继续试只会疯狂刷日志。
                            // 动作：标记熔断 -> 跳出循环 -> 返回 Retry 让系统过会儿（比如1分钟后）再重启 Worker
                            abortSync = true
                            break
                        }
                    }

                    // --- Phase B: 提交后端 (State 1 -> 2) ---
                    val serverUrl = currentRecord.serverUrl
                    if (currentRecord.syncStatus == 1 && serverUrl != null) {
                        Log.d(TAG, "提交元数据到后端: ${currentRecord.recordId}")
                        Log.d(TAG, "IRI: ${currentRecord.iri}")
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

                            // 如果是限流，或者服务器 500 崩溃，立即熔断！不要再白费力气了
                            if (res.code == 429 || res.code >= 500) {
                                Log.w(TAG, "触发后端限流或服务异常，触发熔断，等待系统稍后重试")
                                abortSync = true
                                break
                            }
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
                    val hasUnsynced = repository.hasUnsyncedRecords(task.taskId)
                    if (hasUnsynced) {
                        Log.w(TAG, "🚧 任务 [${task.taskId}] 还有未同步的记录，暂不更新任务状态为 2，等待下一轮")
                        continue
                    }

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
        } finally {
            OssHelper.shutdown()
        }
    }

    companion object {
        const val TAG = "UploadWorker"
    }
}