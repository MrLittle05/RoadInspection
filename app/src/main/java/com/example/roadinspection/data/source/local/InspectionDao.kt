package com.example.roadinspection.data.source.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
    @Query("SELECT * FROM inspection_tasks WHERE inspector_id = :userId AND sync_state != -1 ORDER BY start_time DESC")
    fun getAllTasks(userId: String): Flow<List<InspectionTask>>

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
    @Query("SELECT * FROM inspection_tasks WHERE inspector_id = :userId AND sync_state = 0")
    suspend fun getUnsyncedTasks(userId: String): List<InspectionTask>

    /**
     * 更新任务同步状态 (0 -> 1, 或 1 -> 2)
     *
     * @param taskId 任务 UUID
     * @param newState 任务更新后的状态
     */
    @Query("UPDATE inspection_tasks SET sync_state = :newState WHERE task_id = :taskId")
    suspend fun updateTaskSyncState(taskId: String, newState: Int)

    /**
     * 查出当前用户本地已停止，但服务器还不知道的任务
     *
     * @return 待更新停止时间及同步状态的任务列表
     */
    @Query("SELECT * FROM inspection_tasks WHERE inspector_id = :userId AND is_finished = 1 AND sync_state = 1")
    suspend fun getFinishedButNotSyncedTasks(userId: String): List<InspectionTask>

    /**
     * 更新任务进度缓存 (里程 + 时长)。
     * 用于“暂停”或“退出”时保存现场，以便下次恢复。
     */
    @Query("UPDATE inspection_tasks SET current_distance = :distance, current_duration = :duration WHERE task_id = :taskId")
    suspend fun updateTaskCheckpoint(taskId: String, distance: Float, duration: Long)

    /**
     * 标记任务状态为“待删除”。
     * 用于 UI 立即响应，等待后台同步。
     */
    @Query("UPDATE inspection_tasks SET sync_state = -1 WHERE task_id = :taskId")
    suspend fun markTaskAsDeleted(taskId: String)

    /**
     * 获取所有标记为“待删除”的任务
     * 用于后台同步。
     */
    @Query("SELECT * FROM inspection_tasks WHERE sync_state = -1")
    suspend fun getPendingDeleteTasks(): List<InspectionTask>

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

    /**
     * 获取指定任务下所有非空的本地文件路径。
     * 用于在物理删除任务前，清理手机存储中的图片文件。
     */
    @Query("SELECT local_path FROM inspection_records WHERE task_id = :taskId AND local_path != ''")
    suspend fun getLocalPathsByTaskId(taskId: String): List<String>

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

    /**
     * 获取待清理本地文件的候选记录列表。
     *
     * **筛选条件 (必须同时满足)：**
     * 1. `sync_status = 2`: 必须是已完全同步到服务端的记录，防止误删未上传图片。
     * 2. `local_path != ''`: 仅筛选那些本地路径尚未被置空的记录。
     * 3. `capture_time < :expirationThreshold`: 拍摄时间早于过期阈值。
     *
     * @param expirationThreshold 过期时间戳 (截止时间点)。
     * @return 符合清理条件的 [InspectionRecord] 列表。
     */
    @Query("""
        SELECT * FROM inspection_records 
        WHERE sync_status = 2 
        AND local_path != '' 
        AND capture_time < :expirationThreshold
    """)
    suspend fun getRecordsToClean(expirationThreshold: Long): List<InspectionRecord>

    /**
     * 批量清除本地文件路径标记。
     *
     * **操作效果：**
     * 将指定记录的 `local_path` 字段更新为空字符串 `""`。
     *
     * **业务影响：**
     * 这不会删除数据库行记录。UI 层检测到 `localPath` 为空时，
     * 应降级使用 `serverUrl` 显示图片或显示“云端存储”标识。
     *
     * @param ids 需要标记为“已清理”的记录主键 (recordId) 列表。
     */
    @Query("UPDATE inspection_records SET local_path = '' WHERE record_id IN (:ids)")
    suspend fun clearLocalPaths(ids: List<String>)

    // -------------------------------------------------------------------------
    // Region: 任务智能合并 (Task Smart Merge)
    // -------------------------------------------------------------------------

    /**
     * 1. 获取本地尚未同步到后端的任务 ID 列表。
     * 定义：SyncState = 0 (即 0-新建未上传) 且本地有修改权的任务。
     */
    @Query("SELECT task_id FROM inspection_tasks WHERE sync_state = 0")
    suspend fun getUnuploadedTaskIds(): List<String>

    /**
     * 2. 获取本地已完成但没同步的任务 ID
     */
    @Query("SELECT task_id FROM inspection_tasks WHERE is_finished = 1 AND sync_state = 1")
    suspend fun getFinishedButNotSyncedTaskIds(): List<String>

    /**
     * 3. 批量插入或更新任务。
     * 仅用于经过过滤的安全数据。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTasks(tasks: List<InspectionTask>)

    /**
     * 【事务】执行任务列表的智能合并。
     *
     * @param networkTasks 来自后端的最新任务列表
     */
    @Transaction
    suspend fun smartMergeTasks(networkTasks: List<InspectionTask>) {
        if (networkTasks.isEmpty()) return

        // 1. 绝对保护名单：本地新建未上传 (State=0)
        // 这些任务服务器根本不知道，必须死保
        val strictlyLocalIds = getUnuploadedTaskIds().toHashSet()

        // 2. 相对保护名单：本地已完成，但还没同步状态 (isFinished=1, State=1)
        val localFinishedIds = getFinishedButNotSyncedTaskIds().toHashSet()

        val safeToUpdateTasks = networkTasks.filter { netTask ->
            val taskId = netTask.taskId

            // 情况 A: 绝对保护，直接拒绝
            if (strictlyLocalIds.contains(taskId)) {
                return@filter false
            }

            // 情况 B: 相对保护 (本地已完成)
            if (localFinishedIds.contains(taskId)) {
                // 关键点：如果网络说“我也完成了(true)”，说明达成共识，允许更新！
                // 这样可以将本地 syncState 顺便更新为 2 (Finalized)，且同步最新标题。
                if (netTask.isFinished) {
                    return@filter true
                } else {
                    // 如果网络说“没完成(false)”，说明网络滞后，拒绝更新，保护本地完成状态。
                    return@filter false
                }
            }

            // 情况 C: 其他普通任务，允许更新
            true
        }

        val finalTasksToSave = safeToUpdateTasks.map { netTask ->
            // 尝试获取本地记录
            val localTask = getTaskById(netTask.taskId)

            if (localTask != null) {
                // 如果本地存在，继承本地的缓存字段
                netTask.copy(
                    currentDistance = localTask.currentDistance,
                    currentDuration = localTask.currentDuration,
                    // 如果未来有其他本地独有字段，也要在这里 copy
                )
            } else {
                netTask
            }
        }

        // 3. 批量写入
        if (finalTasksToSave.isNotEmpty()) {
            insertOrUpdateTasks(finalTasksToSave)
        }
    }

    // -------------------------------------------------------------------------
    // Region: 记录智能合并 (Records Smart Merge)
    // -------------------------------------------------------------------------

    /**
     * 【核心】合并记录的事务逻辑
     * * @param taskId 当前任务 ID
     * @param networkRecords 从后端拉下来的最新数据
     */
    @Transaction
    suspend fun smartMergeRecords(taskId: String, networkRecords: List<InspectionRecord>) {
        if (networkRecords.isEmpty()) return

        // 1. 获取本地未同步列表 (sync_status != 2)
        val unsyncedIds = getUnsyncedRecordIds(taskId).toHashSet()

        // 2. 内存过滤：剔除掉那些本地还没上传的数据
        // 逻辑：如果网络数据里的 ID 在 dirtyIds 里，说明本地有未提交的修改，跳过网络版，保留本地版。
        val recordsToSave = networkRecords.filter { netRecord ->
            !unsyncedIds.contains(netRecord.recordId)
        }

        // 3. 批量写入
        if (recordsToSave.isNotEmpty()) {
            insertOrUpdateRecords(recordsToSave)
        }
    }

    /**
     * 查找所有本地“受保护”的记录 ID。
     * 受保护 = 未完全同步 (sync_status != 2)。这些记录本地比云端新，不能覆盖。
     */
    @Query("SELECT record_id FROM inspection_records WHERE task_id = :taskId AND sync_status != 2")
    suspend fun getUnsyncedRecordIds(taskId: String): List<String>

    /**
     * 批量插入/更新。
     * 经过 Filter 后的数据都是安全的，可以直接 Replace。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRecords(records: List<InspectionRecord>)
}