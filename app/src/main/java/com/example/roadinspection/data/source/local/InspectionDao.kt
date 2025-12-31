package com.example.roadinspection.data.source.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 道路巡检数据访问对象 (DAO)。
 * 定义了所有与本地数据库交互的 CRUD 操作。
 */
@Dao
interface InspectionDao {

    // -------------------------------------------------------------------------
    // Region: 巡检任务管理 (InspectionTask)
    // -------------------------------------------------------------------------

    /**
     * 开始新巡检：插入新的巡检任务。
     *
     * @param task 新创建的巡检任务对象
     * @see OnConflictStrategy.IGNORE 防止UUID重复的极端情况下误删任务下属照片信息
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTask(task: InspectionTask)

    /**
     * 结束巡检：更新任务的结束时间和完成状态。
     *
     * @param taskId 目标任务 UUID
     * @param endTime 结束时间戳
     */
    @Query("UPDATE inspection_tasks SET end_time = :endTime, is_finished = 1 WHERE task_id = :taskId")
    suspend fun finishTask(taskId: String, endTime: Long)

    /**
     * 获取当前用户的所有巡检任务列表。
     *
     * @return [Flow] 数据流。当数据库发生变更时，UI 会自动接收到最新的列表。
     */
    @Query("SELECT * FROM inspection_tasks ORDER BY start_time DESC")
    fun getAllTasks(): Flow<List<InspectionTask>>

    /**
     * 根据 ID 获取单个任务详情。
     *
     * @param taskId 任务 UUID
     * @return 任务对象，若不存在则返回 null
     */
    @Query("SELECT * FROM inspection_tasks WHERE task_id = :taskId")
    suspend fun getTaskById(taskId: String): InspectionTask?

    /**
     * 查找当前用户的所有尚未同步到服务器的任务
     *
     * @return 待同步到服务器的任物列表
     */
    @Query("SELECT * FROM inspection_tasks WHERE sync_state = 0")
    suspend fun getUnsyncedTasks(): List<InspectionTask>

    /**
     * 更新任务同步状态 (0 -> 1, 或 1 -> 2)
     *
     * @param taskId 任务 UUID
     * @param newState 任务更新后的状态
     */
    @Query("UPDATE inspection_tasks SET sync_state = :newState WHERE task_id = :taskId")
    suspend fun updateTaskSyncState(taskId: String, newState: Int)

    /**
     * 查出本地已停止，但服务器还不知道的任务
     *
     * @return 待更新停止时间及同步状态的任务列表
     */
    @Query("SELECT * FROM inspection_tasks WHERE is_finished = 1 AND sync_state = 1")
    suspend fun getFinishedButNotSyncedTasks(): List<InspectionTask>

    // -------------------------------------------------------------------------
    // Region: 巡检记录管理 (InspectionRecord)
    // -------------------------------------------------------------------------

    /**
     * 拍照保存：插入一条具体的图片记录。
     *
     * @param record 图片记录对象
     * @return 生成的自增行 ID (rowId)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: InspectionRecord): Long

    /**
     * 照片上传阿里云后：更新记录信息。
     * 主要用于 WorkManager 在后台上传成功后，回填 `serverUrl` 并更新 `syncStatus`。
     */
    @Update
    suspend fun updateRecord(record: InspectionRecord)

    /**
     * 获取指定任务下的所有照片记录。
     * 用于在任务详情页按时间顺序展示轨迹或照片流。
     *
     * @param taskId 关联的任务 UUID
     */
    @Query("SELECT * FROM inspection_records WHERE task_id = :taskId ORDER BY capture_time ASC")
    fun getRecordsByTask(taskId: String): Flow<List<InspectionRecord>>

    /**
     * 根据任务 ID 和 同步状态 筛选记录。
     * 用于 UI 的筛选 Tab 功能 (例如只看“未完成”的)。
     */
    @Query("SELECT * FROM inspection_records WHERE task_id = :taskId AND sync_status = :status ORDER BY capture_time ASC")
    fun getRecordsByTaskAndStatus(taskId: String, status: Int): Flow<List<InspectionRecord>>

    // -------------------------------------------------------------------------
    // Region: 后台同步专用 (WorkManager)
    // -------------------------------------------------------------------------

    /**
     * 批量获取未完成同步的巡检记录。
     *
     * **查询逻辑：** 筛选 `sync_status != 2 (SYNCED)` 的记录。
     * WorkManager 应采用循环处理机制：处理完一批 -> 再次查询 -> 直至列表为空。
     *
     * @param limit 单次拉取的最大数量 (由调用者决定)
     * @return 待处理的记录列表 (按拍摄时间升序排列)
     */
    @Query("SELECT * FROM inspection_records WHERE sync_status != 2 ORDER BY capture_time ASC LIMIT :limit")
    suspend fun getBatchUnfinishedRecords(limit: Int): List<InspectionRecord>

    /**
     * 获取当前剩余待上传的记录总数。
     * 用于 UI 显示同步进度 (例如 "待上传: 5")。
     */
    @Query("SELECT COUNT(*) FROM inspection_records WHERE sync_status != 2")
    fun getUnfinishedCount(): Flow<Int>

    // -------------------------------------------------------------------------
    // Region: 数据清理与维护
    // -------------------------------------------------------------------------

    /**
     * 删除过期的已完成数据，释放手机存储空间。
     *
     * @param expirationTime 过期时间截止点 (例如 3 天前的时间戳)
     * @return 被删除的行数
     */
    @Query("DELETE FROM inspection_records WHERE sync_status = 2 AND capture_time < :expirationTime")
    suspend fun deleteExpiredRecords(expirationTime: Long): Int

    /**
     * 删除指定任务。
     *
     * **注意：** 由于 Entity 中配置了 `CASCADE`，调用此方法会级联删除该任务下
     * `inspection_records` 表中所有的关联照片记录。
     *
     * @param taskId 要删除的任务 UUID
     */
    @Query("DELETE FROM inspection_tasks WHERE task_id = :taskId")
    suspend fun deleteTask(taskId: String)
}