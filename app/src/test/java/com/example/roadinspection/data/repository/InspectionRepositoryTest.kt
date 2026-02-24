package com.example.roadinspection.data.repository

import com.example.roadinspection.data.model.ApiResponse
import com.example.roadinspection.data.source.local.InspectionDao
import com.example.roadinspection.data.source.local.InspectionRecord
import com.example.roadinspection.data.source.local.InspectionTask
import com.example.roadinspection.data.source.remote.InspectionApiService
import com.example.roadinspection.data.source.remote.RecordDto
import com.example.roadinspection.data.source.remote.TaskDto
import com.example.roadinspection.di.NetworkModule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.impl.annotations.MockK
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * InspectionRepository 的单元测试。
 *
 * 测试策略：
 * 1. 验证 Repository 是否正确协调 API 和 DAO。
 * 2. 验证本地业务逻辑（如文件清理、状态转换）是否正确。
 * 3. 使用 MockK 模拟外部依赖（Dao, ApiService）。
 */
class InspectionRepositoryTest {

    @MockK(relaxed = true)
    lateinit var mockDao: InspectionDao

    @MockK
    lateinit var mockApi: InspectionApiService

    private lateinit var repository: InspectionRepository

    // 用于跟踪测试过程中创建的临时文件，以便在测试结束后清理
    private val tempFiles = mutableListOf<File>()

    // 预设的测试用户 ID
    private val testUserId = "user_test_001"

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        // Mock NetworkModule 单例，使其返回我们模拟的 API 对象
        mockkObject(NetworkModule)
        every { NetworkModule.api } returns mockApi

        // 初始化 Repository
        repository = InspectionRepository(mockDao)
    }

    @After
    fun tearDown() {
        // 清理所有临时文件
        tempFiles.forEach { if (it.exists()) it.delete() }
        tempFiles.clear()

        // 解除所有 Mock
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // Region: 任务 CRUD 与基础查询
    // -------------------------------------------------------------------------

    @Test
    fun `createTask should insert task with correct inspectorId`() = runTest {
        // Arrange (准备)
        val title = "Test Inspection"
        val taskSlot = slot<InspectionTask>()

        // Act (执行)
        val taskId = repository.createTask(title, testUserId)

        // Assert (验证)
        coVerify { mockDao.insertTask(capture(taskSlot)) }

        val captured = taskSlot.captured
        assertEquals("任务 ID 应与返回值一致", taskId, captured.taskId)
        assertEquals("标题应正确透传", title, captured.title)
        assertEquals("InspectorId 应正确透传", testUserId, captured.inspectorId)
        assertEquals("新任务应为未同步状态", 0, captured.syncState)
    }

    @Test
    fun `finishTask should update end time and status`() = runTest {
        // Arrange
        val taskId = "uuid-1"

        // Act
        repository.finishTask(taskId)

        // Assert
        // 验证 DAO 的 finishTask 被调用，且传递了 taskId 和当前时间戳 (any())
        coVerify { mockDao.finishTask(eq(taskId), any()) }
    }

    @Test
    fun `getAllTasks should return flow from dao with userId`() = runTest {
        // Arrange
        val flow = flowOf(listOf<InspectionTask>())
        every { mockDao.getAllTasks(testUserId) } returns flow

        // Act & Assert
        assertEquals(flow, repository.getAllTasks(testUserId))

        // 验证调用时带上了 userId
        verify(exactly = 1) { mockDao.getAllTasks(testUserId) }
    }

    // -------------------------------------------------------------------------
    // Region: 网络同步逻辑 (智能合并)
    // -------------------------------------------------------------------------

    @Test
    fun `syncTasksFromNetwork should fetch transform and delegate to smartMerge`() = runTest {
        // Arrange
        val netTask = TaskDto(
            taskId = "t1", title = "Remote Task", inspectorId = testUserId,
            startTime = 1000L, endTime = 2000L, isFinished = true
        )
        val apiResponse = ApiResponse.success(listOf(netTask))
        val taskListSlot = slot<List<InspectionTask>>()

        // Mock API 返回成功
        coEvery { mockApi.fetchTasks(testUserId) } returns apiResponse
        // Mock DAO 的智能合并方法 (必须 Mock，否则会尝试执行接口中的 Default Impl)
        coEvery { mockDao.smartMergeTasks(any()) } returns Unit

        // Act
        repository.syncTasksFromNetwork(testUserId)

        // Assert
        coVerify { mockApi.fetchTasks(testUserId) }
        coVerify { mockDao.smartMergeTasks(capture(taskListSlot)) }

        val mergedTask = taskListSlot.captured.first()
        assertEquals("t1", mergedTask.taskId)
        // 验证 Repository 内的转换逻辑：从网络拉下来的任务，SyncState 应被强制设为 2 (已同步)
        assertEquals("网络任务应标记为已同步(2)", 2, mergedTask.syncState)
    }

    @Test(expected = RuntimeException::class)
    fun `syncTasksFromNetwork should throw exception on api failure`() = runTest {
        // Arrange
        coEvery { mockApi.fetchTasks(any()) } returns ApiResponse.error("Server Error")

        // Act
        repository.syncTasksFromNetwork(testUserId)
    }

    @Test
    fun `syncRecordsFromNetwork should fetch and merge records`() = runTest {
        // Arrange
        val taskId = "task_1"
        val netRecord = RecordDto(
            recordId = "r1", taskId = taskId, serverUrl = "http://oss.com/img.jpg",
            captureTime = 100L, address = "St", rawLat = 1.0, rawLng = 2.0
        )
        val apiResponse = ApiResponse.success(listOf(netRecord))
        val recordsSlot = slot<List<InspectionRecord>>()

        coEvery { mockApi.fetchRecords(taskId) } returns apiResponse
        coEvery { mockDao.smartMergeRecords(taskId, any()) } returns Unit

        // Act
        repository.syncRecordsFromNetwork(taskId)

        // Assert
        coVerify { mockDao.smartMergeRecords(taskId, capture(recordsSlot)) }

        val mergedRecord = recordsSlot.captured.first()
        assertEquals("r1", mergedRecord.recordId)
        assertEquals("网络记录本地路径应为空", "", mergedRecord.localPath)
        assertEquals("网络记录应标记为已同步(2)", 2, mergedRecord.syncStatus)
    }

    // -------------------------------------------------------------------------
    // Region: 文件清理逻辑
    // -------------------------------------------------------------------------

    @Test
    fun `clearExpiredFiles should delete physical files and update db`() = runTest {
        // Arrange: 创建真实临时文件
        val tempFile = createTempFile("test_pic_", ".jpg")
        val recordId = "rec_1"
        // 使用 file:// 格式路径模拟 Android 环境
        val filePath = "file:///${tempFile.absolutePath.replace("\\", "/")}"

        // 构造待清理记录，注意 syncStatus 必须为 2 才能匹配 DAO 逻辑（虽然这里是 Mock 返回的）
        val recordToClean = InspectionRecord(
            recordId = recordId,
            taskId = "task_1",
            localPath = filePath,
            serverUrl = "http://oss/url",
            syncStatus = 2, // 必须是已同步
            captureTime = System.currentTimeMillis() - 1000000L,
            latitude = 0.0, longitude = 0.0
        )

        coEvery { mockDao.getRecordsToClean(any()) } returns listOf(recordToClean)

        // Act
        val deletedCount = repository.clearExpiredFiles(retentionDays = 7)

        // Assert
        assertEquals("应返回成功删除的文件数", 1, deletedCount)
        assertFalse("物理文件应该被删除", tempFile.exists())
        coVerify { mockDao.clearLocalPaths(listOf(recordId)) }
    }

    @Test
    fun `clearExpiredFiles should update db even if file is missing`() = runTest {
        // Arrange
        val nonExistentPath = "/path/does/not/exist.jpg"
        val recordId = "rec_999"

        val record = InspectionRecord(
            recordId = recordId,
            taskId = "task_1",
            localPath = nonExistentPath,
            syncStatus = 2,
            captureTime = 0L,
            latitude = 0.0, longitude = 0.0
        )

        coEvery { mockDao.getRecordsToClean(any()) } returns listOf(record)

        // Act
        val count = repository.clearExpiredFiles(7)

        // Assert
        assertEquals("由于没有物理文件被删除，计数应为 0", 0, count)
        // 关键验证：即使文件不见了，数据库也应该更新为空，以保持一致性
        coVerify(exactly = 1) { mockDao.clearLocalPaths(listOf(recordId)) }
    }

    // -------------------------------------------------------------------------
    // Region: 删除逻辑 (新增测试)
    // -------------------------------------------------------------------------

    @Test
    fun `finalizeDeletion should delete task and local files`() = runTest {
        // Arrange
        val taskId = "task_del_1"
        // 创建一个关联的图片文件
        val tempFile = createTempFile("del_img_", ".jpg")
        val filePath = "file:///${tempFile.absolutePath.replace("\\", "/")}"

        // Mock DAO 返回关联的文件路径
        coEvery { mockDao.getLocalPathsByTaskId(taskId) } returns listOf(filePath)
        coEvery { mockDao.deleteTask(taskId) } returns Unit

        // Act
        repository.finalizeDeletion(taskId)

        // Assert
        coVerify(exactly = 1) { mockDao.deleteTask(taskId) } // 验证数据库删除
        assertFalse("关联的物理图片应该被删除", tempFile.exists())
    }

    @Test
    fun `markTaskForDeletion should call dao`() = runTest {
        val taskId = "task_mark_del"
        repository.markTaskForDeletion(taskId)
        coVerify { mockDao.markTaskAsDeleted(taskId) }
    }

    // -------------------------------------------------------------------------
    // Region: 辅助方法
    // -------------------------------------------------------------------------

    /**
     * 创建安全的临时文件并注册到清理列表
     */
    private fun createTempFile(prefix: String, suffix: String): File {
        return File.createTempFile(prefix, suffix).also { file ->
            file.setWritable(true)
            tempFiles.add(file)
        }
    }
}