package com.example.roadinspection.data.repository

import android.content.Context
import com.example.roadinspection.data.source.local.AppDatabase
import com.example.roadinspection.data.source.local.InspectionDao
import com.example.roadinspection.data.source.local.InspectionRecord
import com.example.roadinspection.data.source.local.InspectionTask
import kotlinx.coroutines.flow.Flow

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
    suspend fun createTask(title: String): String {
        //TODO: 从全局 Session获取当前登录人的 ID
        val currentUserId = "user_default"

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
     */
    suspend fun getUnsyncedTasks(): List<InspectionTask> = dao.getUnsyncedTasks()

    /**
     * 获取本地已完成，但服务器状态仍为“进行中”的任务 (isFinished = 1 AND syncState = 1)。
     * WorkManager 将遍历此列表，调用 /api/task/finish 接口。
     */
    suspend fun getFinishedButNotSyncedTasks(): List<InspectionTask> = dao.getFinishedButNotSyncedTasks()

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
     * 获取所有历史巡检任务列表。
     *
     * @return [Flow] 数据流。当数据库新增任务或状态改变时，UI 会自动刷新。
     */
    fun getAllTasks(): Flow<List<InspectionTask>> = dao.getAllTasks()

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
}