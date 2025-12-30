package com.example.roadinspection.data.repository

import android.content.Context
import com.example.roadinspection.data.source.local.AppDatabase
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
 * @param context 应用上下文，用于初始化数据库。
 */
class InspectionRepository(context: Context) {

    /**
     * 本地数据库实例 (Singleton)。
     * 通过 [AppDatabase.getDatabase] 获取，确保线程安全且全局唯一。
     */
    private val database = AppDatabase.getDatabase(context)

    /**
     * 巡检模块的数据访问对象 (DAO)。
     * 所有实际的 CRUD 操作均委托给此对象执行。
     */
    private val dao = database.inspectionDao()

    // -------------------------------------------------------------------------
    // Region: 供 ViewModel 调用的业务方法
    // -------------------------------------------------------------------------

    /**
     * 创建并开启一个新的巡检任务。
     *
     * @param title 任务标题 (例如 "2023-12-30 中山路巡检")
     * @return 新生成的任务 ID (UUID 字符串)，供后续拍照时关联使用。
     */
    suspend fun createTask(title: String): String {
        val task = InspectionTask(title = title)
        dao.insertTask(task)
        return task.taskId
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
     * 结束指定的巡检任务。
     * 更新任务的结束时间和完成状态。
     *
     * @param taskId 任务 UUID
     */
    suspend fun finishTask(taskId: String) {
        dao.finishTask(taskId, System.currentTimeMillis())
    }

    /**
     * 获取所有历史巡检任务列表。
     *
     * @return [Flow] 数据流。当数据库新增任务或状态改变时，UI 会自动刷新。
     */
    fun getAllTasks(): Flow<List<InspectionTask>> = dao.getAllTasks()

    /**
     * 获取指定任务下的所有照片记录。
     * 用于在任务详情页按时间顺序展示轨迹或照片流。
     *
     * @param taskId 任务 UUID
     */
    fun getRecordsByTask(taskId: String): Flow<List<InspectionRecord>> = dao.getRecordsByTask(taskId)

    /**
     * 获取当前待上传（未同步完成）的记录总数。
     * 用于 UI 显示红色角标或进度提示 (例如 "待上传: 5")。
     */
    val unfinishedCount: Flow<Int> = dao.getUnfinishedCount()
}