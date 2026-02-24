package com.example.roadinspection.data.repository

import com.example.roadinspection.data.source.local.AppDatabase
import com.example.roadinspection.data.source.local.InspectionDao
import com.example.roadinspection.data.source.local.InspectionRecord
import com.example.roadinspection.data.source.local.InspectionTask
import com.example.roadinspection.data.source.local.TokenManager
import com.example.roadinspection.data.source.remote.LogoutReq
import com.example.roadinspection.data.source.remote.TaskDto
import com.example.roadinspection.data.source.remote.RecordDto
import com.example.roadinspection.data.source.remote.UpdateProfileReq
import com.example.roadinspection.data.source.remote.UserDto
import com.example.roadinspection.di.NetworkModule.api
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 巡检数据仓库 (Repository)。
 *
 * **架构角色：**
 * 位于 ViewModel 与 数据源 (DAO/Network) 之间。
 * 作为“单一真实信源 (SSOT)”，负责协调数据的获取、存储和同步逻辑。
 *
 * **主要职责：**
 * 1. 初始化并持有 [AppDatabase] 实例。
 * 2. 封装 DAO 操作，为上层业务提供简洁的 suspend 函数或 Flow 数据流。
 * 3. (未来扩展) 协调本地数据库与网络请求之间的逻辑（如触发 WorkManager）。
 *
 * @param dao 巡检模块的数据访问对象，所有实际的 CRUD 操作均委托给此对象执行。
 */
class InspectionRepository(private val dao: InspectionDao) {

    // -------------------------------------------------------------------------
    // Region: 供调用的业务方法
    // -------------------------------------------------------------------------

    /**
     * 创建并开启一个新的巡检任务。
     *
     * @param title 任务标题 (例如 "2023-12-30 中山路巡检")
     * @return 新生成的任务 ID (UUID 字符串)，供后续拍照时关联使用。
     */
    suspend fun createTask(title: String, currentUserId: String): String {

        val task = InspectionTask(title = title, inspectorId = currentUserId)
        dao.insertTask(task)
        return task.taskId
    }

    /**
     * 结束指定的巡检任务。
     * 更新任务的结束时间和完成状态。
     *
     * @param taskId 任务 UUID
     */
    suspend fun finishTask(taskId: String) {
        dao.finishTask(taskId = taskId, endTime = System.currentTimeMillis())
    }

    /**
     * 获取所有尚未同步到服务器的任务 (syncState = 0)。
     * WorkManager 将遍历此列表，调用 /api/task/create 接口。
     *
     * @param userId 当前用户 ID
     */
    suspend fun getUnsyncedTasks(userId: String): List<InspectionTask> = dao.getUnsyncedTasks(userId)

    /**
     * 获取本地已完成，但服务器状态仍为“进行中”的任务 (isFinished = 1 AND syncState = 1)。
     * WorkManager 将遍历此列表，调用 /api/task/finish 接口。
     *
     * @param userId 当前用户 ID
     */
    suspend fun getFinishedButNotSyncedTasks(userId: String): List<InspectionTask> = dao.getFinishedButNotSyncedTasks(userId)

    /**
     * 更新任务的同步状态。
     *
     * - 当 /api/task/create 成功后，将状态从 0 更新为 1。
     * - 如果该任务在上传时已经是完成状态（且使用了携带 endTime 的优化接口），直接更新为 2。
     * - 当 /api/task/finish 成功后，将状态从 1 更新为 2。
     *
     * @param taskId 任务 UUID
     * @param newState 新的同步状态 (1=已同步, 2=已终结)
     */
    suspend fun updateTaskSyncState(taskId: String, newState: Int) {
        dao.updateTaskSyncState(taskId, newState)
    }

    /**
     * 根据 ID 获取特定巡检任务详情。
     * 用于断点续传时恢复任务现场 (Checkpoint)，读取已保存的里程和时长。
     *
     * @param taskId 任务 UUID
     * @return [InspectionTask] 实体，如果找不到则返回 null
     */
    suspend fun getTaskById(taskId: String): InspectionTask? {
        return dao.getTaskById(taskId)
    }

    /**
     * 获取所有历史巡检任务列表。
     *
     * @param userId 当前用户 ID
     * @return [Flow] 数据流。当数据库新增任务或状态改变时，UI 会自动刷新。
     */
    fun getAllTasks(userId: String): Flow<List<InspectionTask>> = dao.getAllTasks(userId)

    /**
     * 保存任务进度缓存
     */
    suspend fun saveTaskCheckpoint(taskId: String, distance: Float, duration: Long) {
        dao.updateTaskCheckpoint(taskId, distance, duration)
    }

    /**
     * 获取任务的恢复状态数据 (用于注入前端)。
     *
     * @param taskId 任务 ID
     * @return 包含 distance, seconds, isPaused 等字段的 Map
     */
    suspend fun getTaskState(taskId: String): Map<String, Any> {
        val task = dao.getTaskById(taskId) ?: return emptyMap()

        return mapOf(
            "distance" to task.currentDistance,
            "seconds" to task.currentDuration
        )
    }

    /**
     * 保存一条巡检记录（照片及位置信息）。
     *
     * **注意：** 此时数据仅写入本地数据库，状态标记为 `PENDING` (待上传)。
     * 后续的上传操作由 WorkManager 在后台异步处理。
     *
     * @param record 组装好的记录实体对象
     */
    suspend fun saveRecord(record: InspectionRecord) {
        dao.insertRecord(record)
    }

    /**
     * 更新巡检记录。
     * 用于 WorkManager 上传成功后，回填 serverUrl 和 syncStatus。
     *
     * @param record 包含最新状态和 URL 的记录对象（必须包含正确的 id）
     */
    suspend fun updateRecord(record: InspectionRecord) {
        dao.updateRecord(record)
    }

    /**
     * 获取指定任务下的所有照片记录。
     * 用于在任务详情页按时间顺序展示轨迹或照片流。
     *
     * @param taskId 任务 UUID
     */
    fun getRecordsByTask(taskId: String): Flow<List<InspectionRecord>> = dao.getRecordsByTask(taskId)

    /**
     * 获取指定任务下，特定状态的记录列表。
     * @param status 0=Pending, 1=ImgUploaded, 2=Synced
     */
    fun getRecordsByTaskAndStatus(taskId: String, status: Int): Flow<List<InspectionRecord>> {
        return dao.getRecordsByTaskAndStatus(taskId, status)
    }

    /**
     * 获取一批待上传的记录。
     *
     * @param limit 单次批处理数量，默认为 10。
     * 如果网络环境好（WiFi），可以传大一点（如 20）；
     * 如果内存紧张或网络差，可以传小一点（如 5）。
     */
    suspend fun getBatchUnfinishedRecords(limit: Int = 10): List<InspectionRecord> {
        return dao.getBatchUnfinishedRecords(limit)
    }

    /**
     * 获取当前待上传（未同步完成）的记录总数。
     * 用于 UI 显示红色角标或进度提示 (例如 "待上传: 5")。
     */
    val unfinishedCount: Flow<Int> = dao.getUnfinishedCount()

    /**
     * 执行本地存储清理策略：保留数据库记录，仅删除物理图片文件。
     *
     * **核心逻辑：**
     * 1. 识别并解析 file:// 格式的 URI，确保 File 对象能定位到物理文件。
     * 2. 执行删除操作。
     * 3. **关键修正**：仅在文件被成功删除或文件本来就不存在时，才标记数据库为已清理。
     *
     * @param retentionDays 本地图片保留天数。
     * @return 成功删除的物理文件数量。
     */
    suspend fun clearExpiredFiles(retentionDays: Int): Int {
        val threshold = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)

        // 获取需要清理图片文件的记录
        val records = dao.getRecordsToClean(threshold)
        if (records.isEmpty()) return 0

        val cleanedIds = mutableListOf<String>()
        var deletedCount = 0

        records.forEach { record ->
            try {
                var isPhysicallyDeleted = false

                val normalizedPath = record.localPath
                    .replaceFirst(Regex("^file:///?"), "/") // 处理 file:// 和 file:/// 两种变体
                    .replaceFirst(Regex("^content://.*"), "") // 忽略 content:// (无法直接删除)


                if (normalizedPath.isNotEmpty() && normalizedPath.startsWith("/")) {
                    val file = File(normalizedPath)
                    if (file.exists()) {
                        // 2. 尝试删除
                        if (file.delete()) {
                            deletedCount++
                            isPhysicallyDeleted = true
                        } else {
                            // 删除失败（如权限问题），此时绝对不能更新数据库！
                            // 留待下次任务重试，或者记录日志排查
                            android.util.Log.w("InspectionRepo", "无法删除文件: $normalizedPath")
                            isPhysicallyDeleted = false
                        }
                    } else {
                        // 文件本来就不存在（可能用户手动删了），视为清理成功
                        isPhysicallyDeleted = true
                    }
                }

                // 3. 只有确认物理文件已消失，才更新数据库
                if (isPhysicallyDeleted) {
                    cleanedIds.add(record.recordId)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. 批量更新数据库状态
        if (cleanedIds.isNotEmpty()) {
            dao.clearLocalPaths(cleanedIds)
        }

        return deletedCount
    }

    /**
     * 【智能同步】从网络拉取最新任务列表并合并到本地。
     *
     * **合并策略：**
     * 1. 采用 "Smart Merge" 策略，优先保护本地未同步 (Dirty) 的修改。
     * 2. 仅更新那些本地状态为 "已同步(SyncState=2)" 或 "新下载" 的任务。
     *
     * @throws Exception 网络请求失败时抛出，由调用方捕获日志，不影响本地数据显示。
     */
    suspend fun syncTasksFromNetwork(userId: String) {
        // 1. 发起网络请求
        val response = api.fetchTasks(userId)

        if (response.isSuccess && response.data != null) {
            val networkTasks = response.data.map { it.toEntity() }

            // 2. 交给 DAO 进行事务级智能合并
            // 性能优化：DAO 内部会过滤掉本地正在修改的任务
            dao.smartMergeTasks(networkTasks)
        } else {
            throw RuntimeException("Sync tasks failed: ${response.message}")
        }
    }

    /**
     * 【智能同步】从网络拉取指定任务的照片记录。
     *
     * 触发一次“拉取 + 合并”流程。
     * UI 调用此方法时，不会阻塞当前显示，因为数据是通过 Flow 更新的。
     */
    suspend fun syncRecordsFromNetwork(taskId: String) {
        try {
            // 1. 发起网络请求
            val response = api.fetchRecords(taskId)

            if (response.isSuccess && response.data != null) {
                val networkRecords = response.data.map { it.toEntity() }

                // 2. 调用 DAO 进行智能合并
                dao.smartMergeRecords(taskId, networkRecords)

                // 合并完成后，Room 会自动通知 getRecordsByTask 的 Flow 发射新数据
            }
        } catch (e: Exception) {
            // 网络错误是预料之中的（如离线模式），打印日志即可，不要崩 UI
            android.util.Log.w("InspectionRepo", "后台同步记录失败: ${e.message}")
        }
    }

    /**
     * 更新个人资料
     */
    suspend fun updateProfile(userId: String, newUsername: String?, newPassword: String?): UserDto? {
        // 构建请求体
        val req = UpdateProfileReq(newUsername, newPassword)

        // 发起请求
        val response = api.updateProfile(userId, req)

        if (response.isSuccess) {
            return response.data
        } else {
            throw Exception(response.message)
        }
    }

    /**
     * ✅ 用户退出登录
     * 1. 尝试通知服务器注销 (Best Effort)
     * 2. 无论成功失败，都必须清除本地 Token
     */
    suspend fun logoutRemote() {
        val refreshToken = TokenManager.refreshToken
        if (!refreshToken.isNullOrEmpty()) {
            try {
                // 调用后端注销接口
                api.logout(LogoutReq(refreshToken))
            } catch (e: Exception) {
                // 网络失败也不要在意，重点是本地要清掉
                android.util.Log.w("Repo", "注销请求发送失败: ${e.message}")
            }
        }
        // 清除本地凭证
       TokenManager.clearTokens()
    }

    // =========================================================================
    // Region: 删除任务相关逻辑
    // =========================================================================

    /**
     * [UI 调用] 标记任务为删除状态。
     * 这会立即从 UI 列表中移除该任务，并触发后台同步删除。
     */
    suspend fun markTaskForDeletion(taskId: String) {
        dao.markTaskAsDeleted(taskId)
    }

    /**
     * [Worker 调用] 获取所有待同步删除的任务。
     */
    suspend fun getPendingDeleteTasks(): List<InspectionTask> {
        return dao.getPendingDeleteTasks()
    }

    /**
     * [Worker 调用] 物理删除任务。
     * 当服务器确认删除（或软删除）成功后，本地彻底清除数据以释放空间。
     */
    suspend fun finalizeDeletion(taskId: String) {
        val filePaths = dao.getLocalPathsByTaskId(taskId)

        // 2. 执行数据库物理删除
        dao.deleteTask(taskId)

        // 3. 清理图片
        if (filePaths.isNotEmpty()) {
            deleteLocalPictures(filePaths)
        }
    }
}

/**
 * 将网络传输对象转换为本地数据库实体
 * 这里注入本地专属逻辑：SyncState = 2 (已同步)
 */
private fun TaskDto.toEntity(): InspectionTask {
    return InspectionTask(
        taskId = this.taskId,
        title = this.title,
        startTime = this.startTime,
        endTime = this.endTime,
        inspectorId = this.inspectorId,
        isFinished = this.isFinished,
        // 【关键】由客户端决定：既然是从网上下来的，那肯定是已同步的
        syncState = if (this.isFinished) 2 else 1
    )
}

/**
 * 将网络记录转换为本地记录
 * 这里注入本地专属逻辑：SyncStatus = 2, LocalPath = ""
 */
private fun RecordDto.toEntity(): InspectionRecord {
    return InspectionRecord(
        recordId = this.recordId,
        taskId = this.taskId,
        localPath = "",
        serverUrl = this.serverUrl,
        syncStatus = 2,
        captureTime = this.captureTime,
        latitude = this.rawLat,
        longitude = this.rawLng,
        address = this.address,
        iri = this.iri,
        pavementDistress = this.pavementDistress
    )
}

/**
 * 辅助方法：批量删除物理文件
 * 复用 clearExpiredFiles 中的路径清洗逻辑
 */
private fun deleteLocalPictures(paths: List<String>) {
    var deletedCount = 0
    for (rawPath in paths) {
        try {
            // 清洗路径：移除 file:// 前缀
            val normalizedPath = rawPath
                .replaceFirst(Regex("^file:///?"), "/")
                .replaceFirst(Regex("^content://.*"), "") // content:// 无法直接通过 File 删除，跳过

            if (normalizedPath.isNotEmpty() && normalizedPath.startsWith("/")) {
                val file = File(normalizedPath)
                if (file.exists()) {
                    if (file.delete()) {
                        deletedCount++
                    } else {
                        android.util.Log.w("InspectionRepo", "文件删除失败 (权限或占用): $normalizedPath")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("InspectionRepo", "清理文件异常: ${e.message}")
        }
    }
    if (deletedCount > 0) {
        android.util.Log.i("InspectionRepo", "已清理任务关联图片: $deletedCount 张")
    }
}