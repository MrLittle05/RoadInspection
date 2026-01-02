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
 * 验证 SQL 语句的正确性、外键约束、级联删除以及业务状态流转逻辑。
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
        // 关键：创建内存数据库，数据仅存在于 RAM 中，进程结束即销毁，不会污染手机存储
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // 允许在主线程测试，简化协程代码
            .build()
        dao = db.inspectionDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Region: Task 业务流程测试
    // -------------------------------------------------------------------------

    /**
     * 测试任务的基础 CRUD：插入与查询。
     *
     * **目标：** 验证 [InspectionDao.insertTask] 能正确写入数据，且 [InspectionDao.getTaskById] 能正确读取。
     * **验证点：**
     * 1. 任务标题是否一致。
     * 2. 默认的 `isFinished` 状态是否为 false。
     */
    @Test
    fun insertAndGetTask() = runBlocking {
        // Arrange (准备)
        val task = InspectionTask(taskId = "t1", title = "测试任务")

        // Act (执行)
        dao.insertTask(task)
        val loaded = dao.getTaskById("t1")

        // Assert (验证)
        assertNotNull("查询结果不应为空", loaded)
        assertEquals("测试任务", loaded?.title)
        assertEquals("新任务默认应未完成", false, loaded?.isFinished)
    }

    /**
     * 测试结束任务逻辑。
     *
     * **目标：** 验证 [InspectionDao.finishTask] SQL 语句是否正确更新了状态和时间。
     * **验证点：**
     * 1. `isFinished` 状态是否变为 true。
     * 2. `endTime` 是否被更新为传入的时间戳。
     */
    @Test
    fun finishTaskUpdatesStatusAndTime() = runBlocking {
        // Arrange
        val task = InspectionTask(taskId = "t1", title = "测试任务")
        dao.insertTask(task)

        // Act
        val expectedEndTime = 123456789L
        dao.finishTask("t1", expectedEndTime)
        val loaded = dao.getTaskById("t1")

        // Assert
        assertEquals("任务状态应标记为已完成", true, loaded?.isFinished)
        assertEquals("结束时间应被正确记录", expectedEndTime, loaded?.endTime)
    }

    /**
     * 测试完整的任务同步状态流转逻辑 (WorkManager 核心依赖)。
     *
     * **目标：** 模拟从“创建”到“上传”再到“结束”的全过程，验证状态查询方法。
     * **流程验证：**
     * 1. [getUnsyncedTasks]: 只返回 `syncState=0` 的任务。
     * 2. [updateTaskSyncState]: 验证状态从 0 -> 1 的更新。
     * 3. [getFinishedButNotSyncedTasks]: 验证找出“已完结但未同步结束状态”的任务。
     */
    @Test
    fun syncStateFlowTest() = runBlocking {
        // Arrange: 插入两个不同状态的任务
        // t1: 本地新建，未同步 (SyncState=0)
        // t2: 已同步 (SyncState=1)
        val t1 = InspectionTask(taskId = "t1", title = "未同步", syncState = 0)
        val t2 = InspectionTask(taskId = "t2", title = "已同步", syncState = 1)
        dao.insertTask(t1)
        dao.insertTask(t2)

        // Step 1: 验证只查出未同步的任务
        val unsynced = dao.getUnsyncedTasks()
        assertEquals("应只有1个未同步任务", 1, unsynced.size)
        assertEquals("t1", unsynced[0].taskId)

        // Step 2: 模拟 t1 上传成功，状态更新为 1
        dao.updateTaskSyncState("t1", 1)
        assertEquals("t1 状态应更新为 1", 1, dao.getTaskById("t1")?.syncState)

        // Step 3: 模拟 t1 点击了结束巡检，但服务器还不知道 (isFinished=1, syncState=1)
        dao.finishTask("t1", System.currentTimeMillis())
        val finishedButNotSynced = dao.getFinishedButNotSyncedTasks()

        assertEquals("应找出需同步结束状态的任务", 1, finishedButNotSynced.size)
        assertEquals("t1", finishedButNotSynced[0].taskId)
    }

    // -------------------------------------------------------------------------
    // Region: Record 业务流程测试
    // -------------------------------------------------------------------------

    /**
     * 测试记录的插入与基于 Flow 的列表查询。
     *
     * **目标：** 验证 [InspectionDao.getRecordsByTask] 能正确返回关联任务的记录，并按时间排序。
     * **前置条件：** 必须先插入父任务 (InspectionTask)，否则外键约束会失败。
     */
    @Test
    fun insertAndGetRecordsByTask() = runBlocking {
        // Arrange: 必须先插入 Task (外键约束)
        val task = InspectionTask(taskId = "t1", title = "Parent")
        dao.insertTask(task)

        // 插入两条时间不同的记录
        val r1 = InspectionRecord(taskId = "t1", localPath = "/path/1", captureTime = 100, latitude = 0.0, longitude = 0.0)
        val r2 = InspectionRecord(taskId = "t1", localPath = "/path/2", captureTime = 200, latitude = 0.0, longitude = 0.0)

        // Act
        dao.insertRecord(r1)
        dao.insertRecord(r2)

        // Assert: 通过 Flow 获取第一个快照
        val records = dao.getRecordsByTask("t1").first()
        assertEquals("应包含2条记录", 2, records.size)
        // 验证排序 ORDER BY capture_time ASC
        assertEquals("应按时间升序排列", "/path/1", records[0].localPath)
        assertEquals("/path/2", records[1].localPath)
    }

    /**
     * 测试记录的更新操作 (模拟上传回填)。
     *
     * **目标：** 验证 [InspectionDao.updateRecord] 能正确更新 `serverUrl` 和 `syncStatus`。
     * **场景：** 图片上传 OSS 成功后，需要回填 URL 并将状态置为 1 (IMAGE_UPLOADED)。
     */
    @Test
    fun updateRecordStatusAndUrl() = runBlocking {
        // Arrange
        dao.insertTask(InspectionTask(taskId = "t1", title = "Parent"))
        val record = InspectionRecord(taskId = "t1", localPath = "/path/1", captureTime = 100, latitude = 0.0, longitude = 0.0)
        val id = dao.insertRecord(record) // 获取插入后的自增 ID

        // Act: 模拟上传成功，构造更新对象
        // 注意：必须 copy 并带上正确的 id，否则 Room 无法找到对应记录
        val recordToUpdate = record.copy(id = id, serverUrl = "http://oss.com/img.jpg", syncStatus = 1)
        dao.updateRecord(recordToUpdate)

        // Assert
        val loadedList = dao.getRecordsByTask("t1").first()
        val loaded = loadedList[0]
        assertEquals("URL应已更新", "http://oss.com/img.jpg", loaded.serverUrl)
        assertEquals("同步状态应已更新", 1, loaded.syncStatus)
    }

    /**
     * 测试批量查询的 Limit 限制逻辑。
     *
     * **目标：** 验证 [InspectionDao.getBatchUnfinishedRecords] 是否严格遵守传入的 limit 参数。
     * **场景：** 插入 15 条数据，限制取 10 条，验证返回数量及顺序。
     */
    @Test
    fun batchQueryWithLimit() = runBlocking {
        // Arrange: 插入 15 条未完成记录
        dao.insertTask(InspectionTask(taskId = "t1", title = "Parent"))
        for (i in 1..15) {
            dao.insertRecord(InspectionRecord(
                taskId = "t1", localPath = "p$i", captureTime = i.toLong(),
                syncStatus = 0, latitude = 0.0, longitude = 0.0
            ))
        }

        // Act: 限制取 10 条
        val batch = dao.getBatchUnfinishedRecords(limit = 10)

        // Assert
        assertEquals("应严格限制返回 10 条", 10, batch.size)
        assertEquals("应从最早的记录开始取", "p1", batch.first().localPath)
        assertEquals("p10", batch.last().localPath)
    }

    // -------------------------------------------------------------------------
    // Region: 高级功能测试 (级联删除 & 清理)
    // -------------------------------------------------------------------------

    /**
     * 测试数据库外键的级联删除 (Cascade Delete) 特性。
     *
     * **目标：** 验证当删除父任务 (Task) 时，其下属的所有记录 (Records) 是否会自动被删除。
     * **重要性：** 保证数据一致性，防止出现“孤儿”数据。
     */
    @Test
    fun cascadeDelete_whenTaskDeleted_recordsAreDeleted() = runBlocking {
        // Arrange
        dao.insertTask(InspectionTask(taskId = "t1", title = "Parent"))
        dao.insertRecord(InspectionRecord(taskId = "t1", localPath = "p1", captureTime = 1, latitude = 0.0, longitude = 0.0))
        dao.insertRecord(InspectionRecord(taskId = "t1", localPath = "p2", captureTime = 2, latitude = 0.0, longitude = 0.0))

        // Pre-check
        assertEquals("删除前应有2条记录", 2, dao.getRecordsByTask("t1").first().size)

        // Act: 删除任务
        dao.deleteTask("t1")

        // Assert: 验证数据被清空
        val task = dao.getTaskById("t1")
        val records = dao.getRecordsByTask("t1").first()

        assertEquals("任务应被删除", null, task)
        assertEquals("关联的记录应被级联删除", 0, records.size)
    }

    /**
     * 测试过期数据的清理逻辑。
     *
     * **目标：** 验证 [InspectionDao.deleteExpiredRecords] 的筛选逻辑是否严谨。
     * **筛选条件验证：**
     * 1. 必须是已完成 (syncStatus = 2) 的数据。
     * 2. 必须早于过期时间戳。
     * 3. 未完成或未过期的数据不应被误删。
     */
    @Test
    fun deleteExpiredRecords() = runBlocking {
        // Arrange
        dao.insertTask(InspectionTask(taskId = "t1", title = "Parent"))

        // r1: 已完成(2)，时间 1000 -> 应被删除 (满足两个条件)
        dao.insertRecord(InspectionRecord(taskId = "t1", localPath = "p1", captureTime = 1000, syncStatus = 2, latitude = 0.0, longitude = 0.0))
        // r2: 已完成(2)，时间 3000 -> 保留 (未过期，3000 > 2000)
        dao.insertRecord(InspectionRecord(taskId = "t1", localPath = "p2", captureTime = 3000, syncStatus = 2, latitude = 0.0, longitude = 0.0))
        // r3: 未完成(0)，时间 1000 -> 保留 (未完成，尽管时间已过)
        dao.insertRecord(InspectionRecord(taskId = "t1", localPath = "p3", captureTime = 1000, syncStatus = 0, latitude = 0.0, longitude = 0.0))

        // Act: 删除 2000 毫秒以前的已完成记录
        val deletedCount = dao.deleteExpiredRecords(expirationTime = 2000)

        // Assert
        assertEquals("应只有1条符合条件的记录被删除", 1, deletedCount)

        val remaining = dao.getRecordsByTask("t1").first()
        assertEquals("应剩余2条记录", 2, remaining.size)
        assertTrue("未过期的记录应保留", remaining.any { it.localPath == "p2" })
        assertTrue("未同步完成的记录应保留", remaining.any { it.localPath == "p3" })
    }
}