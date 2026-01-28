package com.example.roadinspection.data.source.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * [InspectionDao] 的集成测试。
 *
 * **测试策略：**
 * 使用 Room 的内存数据库 (In-Memory Database) 在 Android 环境中运行。
 * 验证 SQL 语句的正确性、外键约束、级联删除以及复杂的业务状态流转逻辑（如智能合并、文件清理）。
 *
 * **环境：** Android Instrumented Test (需要连接真机或模拟器)。
 */
@RunWith(AndroidJUnit4::class)
class InspectionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: InspectionDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 创建内存数据库，进程结束即销毁，不污染真实存储
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // 测试环境允许主线程查询
            .build()
        dao = db.inspectionDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Region: 基础任务管理 (Basic Task CRUD)
    // -------------------------------------------------------------------------

    /**
     * 测试任务插入与基础查询。
     *
     * **目标：** 验证 [InspectionDao.insertTask] 数据写入正确，且 [InspectionDao.getAllTasks] Flow 能发射数据。
     */
    @Test
    fun insertAndGetAllTasks() = runBlocking {
        // Arrange
        val task1 = InspectionTask(taskId = "t1", title = "Task 1", startTime = 1000)
        val task2 = InspectionTask(taskId = "t2", title = "Task 2", startTime = 2000)

        // Act
        dao.insertTask(task1)
        dao.insertTask(task2)

        // Assert: 验证 Flow 发射列表，且按 startTime DESC 排序
        val tasks = dao.getAllTasks().first()
        assertEquals("应包含2个任务", 2, tasks.size)
        assertEquals("Task 2 应排在前面 (时间倒序)", "t2", tasks[0].taskId)
        assertEquals("t1", tasks[1].taskId)
    }

    /**
     * 测试结束任务逻辑。
     *
     * **目标：** 验证 [InspectionDao.finishTask] 正确更新 `isFinished` 标记和 `endTime`。
     */
    @Test
    fun finishTaskUpdatesStatus() = runBlocking {
        // Arrange
        dao.insertTask(InspectionTask(taskId = "t1", title = "Open Task", isFinished = false))

        // Act
        val endTime = 99999L
        dao.finishTask("t1", endTime)
        val loaded = dao.getTaskById("t1")

        // Assert
        assertNotNull(loaded)
        assertTrue("任务应标记为已完成", loaded!!.isFinished)
        assertEquals("结束时间应更新", endTime, loaded.endTime)
    }

    // -------------------------------------------------------------------------
    // Region: 任务同步状态流转 (Task Sync Logic)
    // -------------------------------------------------------------------------

    /**
     * 测试查找待同步任务与状态更新。
     *
     * **目标：** 验证 SyncState 状态机流转 (0 -> 1 -> 2)。
     */
    @Test
    fun taskSyncStateFlow() = runBlocking {
        // Arrange: 插入不同状态的任务
        // t1: State=0 (新建未上传)
        // t2: State=1 (已上传)
        dao.insertTask(InspectionTask(taskId = "t1", title = "New Local", syncState = 0))
        dao.insertTask(InspectionTask(taskId = "t2", title = "Synced", syncState = 1))

        // Act 1: 查找未同步任务
        val unsynced = dao.getUnsyncedTasks()
        assertEquals("应只有1个未同步任务", 1, unsynced.size)
        assertEquals("t1", unsynced[0].taskId)

        // Act 2: 更新 t1 状态为 1
        dao.updateTaskSyncState("t1", 1)
        val t1Updated = dao.getTaskById("t1")
        assertEquals("t1 状态应变为 1", 1, t1Updated?.syncState)

        // Act 3: t1 完成巡检，验证 "已完成但状态未更新" 的查询
        dao.finishTask("t1", System.currentTimeMillis())
        val finishedNotSynced = dao.getFinishedButNotSyncedTasks()
        assertEquals("应找出已完成但SyncState=1的任务", 1, finishedNotSynced.size)
        assertEquals("t1", finishedNotSynced[0].taskId)
    }

    // -------------------------------------------------------------------------
    // Region: 记录管理 (Record CRUD)
    // -------------------------------------------------------------------------

    /**
     * 测试记录插入与查询。
     *
     * **目标：** 验证 [InspectionDao.insertRecord] 与 [InspectionDao.getRecordsByTask] 的联动。
     */
    @Test
    fun insertAndGetRecords() = runBlocking {
        // Arrange: 必须先有父任务
        dao.insertTask(InspectionTask(taskId = "t1", title = "Parent"))

        val r1 = InspectionRecord(taskId = "t1", localPath = "path1", captureTime = 100, latitude = 0.0, longitude = 0.0)
        val r2 = InspectionRecord(taskId = "t1", localPath = "path2", captureTime = 200, latitude = 0.0, longitude = 0.0)

        // Act
        dao.insertRecord(r1)
        dao.insertRecord(r2)

        // Assert
        val records = dao.getRecordsByTask("t1").first()
        assertEquals(2, records.size)
        assertEquals("path1", records[0].localPath) // 按时间 ASC
    }

    /**
     * 测试 WorkManager 批量获取与计数。
     *
     * **目标：** 验证 [InspectionDao.getBatchUnfinishedRecords] 的 limit 限制及计数器准确性。
     */
    @Test
    fun batchQueryAndCount() = runBlocking {
        // Arrange
        dao.insertTask(InspectionTask(taskId = "t1", title = "Parent"))
        // 插入 5 条未同步(0)，1 条已同步(2)
        for (i in 1..5) {
            dao.insertRecord(InspectionRecord(taskId = "t1", localPath = "p$i", syncStatus = 0, captureTime = i.toLong(), latitude = 0.0, longitude = 0.0))
        }
        dao.insertRecord(InspectionRecord(taskId = "t1", localPath = "synced", syncStatus = 2, captureTime = 100, latitude = 0.0, longitude = 0.0))

        // Act
        val batch = dao.getBatchUnfinishedRecords(limit = 3)
        val count = dao.getUnfinishedCount().first()

        // Assert
        assertEquals("Batch limit 应限制为 3", 3, batch.size)
        assertEquals("总未完成数应为 5", 5, count)
        assertTrue("Batch 结果不应包含已同步记录", batch.none { it.syncStatus == 2 })
    }

    // -------------------------------------------------------------------------
    // Region: 数据清理与维护 (Cleanup)
    // -------------------------------------------------------------------------

    /**
     * 测试本地文件路径清理逻辑 (释放空间但不删数据)。
     *
     * **目标：** 验证 [InspectionDao.getRecordsToClean] 筛选条件及 [InspectionDao.clearLocalPaths] 执行效果。
     * **筛选条件：** syncStatus=2 (已同步) AND localPath!='' (有文件) AND time < threshold (过期)。
     */
    @Test
    fun cleanLocalFilePaths() = runBlocking {
        // Arrange
        val threshold = 2000L
        dao.insertTask(InspectionTask(taskId = "t1", title = "P"))

        // Case 1: 完美符合 (已同步，有路径，已过期)
        val r1 = InspectionRecord(recordId = "r1", taskId = "t1", localPath = "/sdcard/img1.jpg", syncStatus = 2, captureTime = 1000, latitude = 0.0, longitude = 0.0)
        // Case 2: 未同步 (不应清理)
        val r2 = InspectionRecord(recordId = "r2", taskId = "t1", localPath = "/sdcard/img2.jpg", syncStatus = 0, captureTime = 1000, latitude = 0.0, longitude = 0.0)
        // Case 3: 未过期 (不应清理)
        val r3 = InspectionRecord(recordId = "r3", taskId = "t1", localPath = "/sdcard/img3.jpg", syncStatus = 2, captureTime = 3000, latitude = 0.0, longitude = 0.0)

        dao.insertRecord(r1)
        dao.insertRecord(r2)
        dao.insertRecord(r3)

        // Act 1: 获取待清理列表
        val toClean = dao.getRecordsToClean(expirationThreshold = threshold)

        // Assert 1
        assertEquals("应只有 r1 符合清理条件", 1, toClean.size)
        assertEquals("r1", toClean[0].recordId)

        // Act 2: 执行清理
        dao.clearLocalPaths(listOf("r1"))
        val r1Loaded = dao.getRecordsByTask("t1").first().find { it.recordId == "r1" }

        // Assert 2
        assertNotNull(r1Loaded)
        assertEquals("LocalPath 应被置空", "", r1Loaded?.localPath)
        assertEquals("SyncStatus 应保持不变", 2, r1Loaded?.syncStatus)
    }

    /**
     * 测试级联删除。
     *
     * **目标：** 验证删除任务时，关联记录自动删除。
     */
    @Test
    fun cascadeDeleteTask() = runBlocking {
        // Arrange
        dao.insertTask(InspectionTask(taskId = "t1", title = "P"))
        dao.insertRecord(InspectionRecord(taskId = "t1", localPath = "p", captureTime = 1, latitude = 0.0, longitude = 0.0))

        // Act
        dao.deleteTask("t1")

        // Assert
        assertNull("任务应不存在", dao.getTaskById("t1"))
        val records = dao.getRecordsByTask("t1").first()
        assertTrue("记录应被级联清空", records.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Region: 智能合并 (Smart Merge) - 核心业务逻辑
    // -------------------------------------------------------------------------

    /**
     * 测试任务智能合并：绝对保护机制。
     *
     * **场景：** 本地有一个新建未上传的任务 (SyncState=0)。
     * **预期：** 无论网络端传来什么数据，都不能覆盖本地任务，因为服务器根本不知道这个任务的存在(UUID冲突或极端情况)。
     */
    @Test
    fun smartMergeTask_protectsStrictlyLocalTask() = runBlocking {
        // Arrange
        val localTask = InspectionTask(taskId = "t1", title = "Local Draft", syncState = 0)
        dao.insertTask(localTask)

        val networkTask = InspectionTask(taskId = "t1", title = "Network Override", syncState = 2)

        // Act
        dao.smartMergeTasks(listOf(networkTask))

        // Assert
        val current = dao.getTaskById("t1")
        assertEquals("应保留本地标题", "Local Draft", current?.title)
        assertEquals("SyncState 应保持 0", 0, current?.syncState)
    }

    /**
     * 测试任务智能合并：相对保护机制 (本地已完成 vs 网络未完成)。
     *
     * **场景：** 本地任务已完成 (isFinished=1, State=1)，但网络端因为滞后，显示该任务未完成。
     * **预期：** 拒绝网络端的更新，防止本地“已完成”状态被回滚为“进行中”。
     */
    @Test
    fun smartMergeTask_protectsLocalFinishedStatus() = runBlocking {
        // Arrange
        // 本地：已完成，等待同步结束状态
        val localTask = InspectionTask(taskId = "t1", title = "Job", isFinished = true, syncState = 1)
        dao.insertTask(localTask)

        // 网络：滞后数据，显示未完成
        val networkTask = InspectionTask(taskId = "t1", title = "Job", isFinished = false, syncState = 1)

        // Act
        dao.smartMergeTasks(listOf(networkTask))

        // Assert
        val current = dao.getTaskById("t1")
        assertTrue("应保留本地已完成状态", current!!.isFinished)
    }

    /**
     * 测试任务智能合并：正常合并。
     *
     * **场景：** 本地任务已完成，网络端也显示已完成 (双方达成共识)。
     * **预期：** 允许更新，接受网络端的最新数据 (如 syncState=2, updated title)。
     */
    @Test
    fun smartMergeTask_allowsUpdateWhenConsensus() = runBlocking {
        // Arrange
        val localTask = InspectionTask(taskId = "t1", title = "Old Title", isFinished = true, syncState = 1)
        dao.insertTask(localTask)

        // 网络：也完成了，且带来了新标题和终态 (State=2)
        val networkTask = InspectionTask(taskId = "t1", title = "New Title", isFinished = true, syncState = 2)

        // Act
        dao.smartMergeTasks(listOf(networkTask))

        // Assert
        val current = dao.getTaskById("t1")
        assertEquals("标题应被网络更新", "New Title", current?.title)
        assertEquals("状态应更新为 Finalized (2)", 2, current?.syncState)
    }

    /**
     * 测试记录智能合并：保护本地未上传的修改。
     *
     * **场景：** 本地有一条记录被修改过或新建 (SyncStatus=0)，网络端有同ID的记录。
     * **预期：** 保留本地记录，丢弃网络记录。
     */
    @Test
    fun smartMergeRecord_protectsUnsyncedRecords() = runBlocking {
        // Arrange
        dao.insertTask(InspectionTask(taskId = "t1", title = "P"))

        // 本地：有一条记录，备注了 address="Local Edit"，状态未同步
        val localRec = InspectionRecord(
            recordId = "r1", taskId = "t1", localPath = "p1",
            address = "Local Edit", syncStatus = 0, // 0 = Pending
            captureTime = 100, latitude = 0.0, longitude = 0.0
        )
        dao.insertRecord(localRec)

        // 网络：同ID，address="Cloud Version"
        val netRec = localRec.copy(address = "Cloud Version", syncStatus = 2)

        // Act
        dao.smartMergeRecords("t1", listOf(netRec))

        // Assert
        val records = dao.getRecordsByTask("t1").first()
        val r1 = records.find { it.recordId == "r1" }
        assertEquals("应保留本地修改的地址", "Local Edit", r1?.address)
        assertEquals("状态应保持未同步", 0, r1?.syncStatus)
    }

    /**
     * 测试记录智能合并：覆盖已同步记录。
     *
     * **场景：** 本地记录已同步 (SyncStatus=2)，网络端有更新。
     * **预期：** 接受网络端数据。
     */
    @Test
    fun smartMergeRecord_overwritesSyncedRecords() = runBlocking {
        // Arrange
        dao.insertTask(InspectionTask(taskId = "t1", title = "P"))

        // 本地：已同步
        val localRec = InspectionRecord(
            recordId = "r1", taskId = "t1", localPath = "p1",
            address = "Old", syncStatus = 2, // 2 = Synced
            captureTime = 100, latitude = 0.0, longitude = 0.0
        )
        dao.insertRecord(localRec)

        // 网络：更新了地址
        val netRec = localRec.copy(address = "New Cloud", syncStatus = 2)

        // Act
        dao.smartMergeRecords("t1", listOf(netRec))

        // Assert
        val records = dao.getRecordsByTask("t1").first()
        val r1 = records.find { it.recordId == "r1" }
        assertEquals("应接受网络更新", "New Cloud", r1?.address)
    }
}