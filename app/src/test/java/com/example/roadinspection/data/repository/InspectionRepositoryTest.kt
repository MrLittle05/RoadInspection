package com.example.roadinspection.data.repository

import com.example.roadinspection.data.source.local.InspectionDao
import com.example.roadinspection.data.source.local.InspectionRecord
import com.example.roadinspection.data.source.local.InspectionTask
import com.example.roadinspection.data.source.remote.ApiResponse
import com.example.roadinspection.data.source.remote.InspectionApiService
import com.example.roadinspection.data.source.remote.RecordDto
import com.example.roadinspection.data.source.remote.TaskDto
import com.example.roadinspection.di.NetworkModule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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

class InspectionRepositoryTest {
    @MockK(relaxed = true)
    lateinit var mockDao: InspectionDao

    @MockK
    lateinit var mockApi: InspectionApiService

    private lateinit var repository: InspectionRepository

    // 跟踪临时文件以便清理
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        // Mock NetworkModule 单例
        mockkObject(NetworkModule)
        every { NetworkModule.api } returns mockApi

        // 初始化 Repository
        repository = InspectionRepository(mockDao)
    }

    @After
    fun tearDown() {
        // 清理所有临时文件
        tempFiles.forEach { it.delete() }
        tempFiles.clear()

        // 清理所有 mocks
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // Region: 任务 CRUD 与本地逻辑
    // -------------------------------------------------------------------------

    @Test
    fun `createTask should insert task with correct inspectorId`() = runTest {
        // Arrange
        val title = "Test Inspection"
        val userId = "user_007"
        val taskSlot = slot<InspectionTask>()

        // Act
        val taskId = repository.createTask(title, userId)

        // Assert
        coVerify { mockDao.insertTask(capture(taskSlot)) }

        val captured = taskSlot.captured
        assertEquals(taskId, captured.taskId)
        assertEquals(title, captured.title)
        assertEquals(userId, captured.inspectorId)
        assertEquals(0, captured.syncState)
    }

    @Test
    fun `finishTask should update end time`() = runTest {
        val taskId = "uuid-1"
        repository.finishTask(taskId)
        coVerify { mockDao.finishTask(eq(taskId), any()) }
    }

    // -------------------------------------------------------------------------
    // Region: 网络同步逻辑 (智能合并)
    // -------------------------------------------------------------------------

    @Test
    fun `syncTasksFromNetwork should fetch, transform and merge tasks`() = runTest {
        // Arrange
        val userId = "user_1"
        val netTask = TaskDto(
            taskId = "t1", title = "Remote Task", inspectorId = "u1",
            startTime = 1000L, endTime = 2000L, isFinished = true
        )
        val apiResponse = ApiResponse(200, "OK", listOf(netTask))
        val taskListSlot = slot<List<InspectionTask>>()

        coEvery { mockApi.fetchTasks(userId) } returns apiResponse

        // Act
        repository.syncTasksFromNetwork(userId)

        // Assert
        coVerify { mockApi.fetchTasks(userId) }
        coVerify { mockDao.smartMergeTasks(capture(taskListSlot)) }

        val mergedTask = taskListSlot.captured.first()
        assertEquals("t1", mergedTask.taskId)
        assertEquals(2, mergedTask.syncState)
    }

    @Test(expected = RuntimeException::class)
    fun `syncTasksFromNetwork should throw exception on api failure`() = runTest {
        coEvery { mockApi.fetchTasks(any()) } returns ApiResponse(500, "Error", null)
        repository.syncTasksFromNetwork("user_1")
    }

    @Test
    fun `syncRecordsFromNetwork should fetch and merge records`() = runTest {
        // Arrange
        val taskId = "task_1"
        val netRecord = RecordDto(
            recordId = "r1", taskId = taskId, serverUrl = "http://oss.com/img.jpg",
            captureTime = 100L, address = "St", rawLat = 1.0, rawLng = 2.0
        )
        val apiResponse = ApiResponse(200, "OK", listOf(netRecord))
        val recordsSlot = slot<List<InspectionRecord>>()

        coEvery { mockApi.fetchRecords(taskId) } returns apiResponse

        // Act
        repository.syncRecordsFromNetwork(taskId)

        // Assert
        coVerify { mockDao.smartMergeRecords(taskId, capture(recordsSlot)) }

        val mergedRecord = recordsSlot.captured.first()
        assertEquals("r1", mergedRecord.recordId)
        assertEquals("", mergedRecord.localPath)
        assertEquals(2, mergedRecord.syncStatus)
    }

    // -------------------------------------------------------------------------
    // Region: 文件清理逻辑 (关键修复 - 无 mockkConstructor)
    // -------------------------------------------------------------------------

    @Test
    fun `clearExpiredFiles should delete physical files and update db`() = runTest {
        // Arrange: 创建真实临时文件 + 使用 file:// 格式路径（兼容 Windows）
        val tempFile = createTempFile("test_pic_", ".jpg")
        val recordId = "rec_1"

        // ✅ 关键：使用 file:// 格式路径，确保能被正则匹配并转换为 / 开头
        val filePath = "file:///${tempFile.absolutePath.replace("\\", "/")}"

        // ✅ 修复: 直接创建真实对象，不 mock data class
        val recordToClean = InspectionRecord(
            recordId = recordId,
            taskId = "task_1",
            localPath = filePath, // 使用 file:// 格式
            serverUrl = "",
            syncStatus = 0,
            captureTime = System.currentTimeMillis() - (8 * 24 * 60 * 60 * 1000L), // 过期
            latitude = 0.0,
            longitude = 0.0,
            address = ""
        )

        coEvery { mockDao.getRecordsToClean(any()) } returns listOf(recordToClean)

        // Act
        val deletedCount = repository.clearExpiredFiles(retentionDays = 7)

        // Assert
        assertEquals(1, deletedCount)
        assertFalse(tempFile.exists()) // 验证物理删除
        coVerify { mockDao.clearLocalPaths(listOf(recordId)) }
    }

    @Test
    fun `clearExpiredFiles should update db if file does not exist`() = runTest {
        val nonExistentPath = "/this/path/definitely/does/not/exist_12345.jpg"
        val recordId = "rec_999"

        // ✅ 直接实例化真实对象（不 mock data class）
        val record = InspectionRecord(
            recordId = recordId,
            taskId = "task_1",
            localPath = nonExistentPath,
            serverUrl = "",
            syncStatus = 0,
            captureTime = System.currentTimeMillis() - (8 * 24 * 60 * 60 * 1000L),
            latitude = 0.0,
            longitude = 0.0,
            address = ""
        )

        coEvery { mockDao.getRecordsToClean(any()) } returns listOf(record)

        // Act
        val count = repository.clearExpiredFiles(7)

        // Assert: 文件不存在视为已清理，应更新数据库
        assertEquals(0, count) // 无物理文件删除（计数为0）
        coVerify(exactly = 1) { mockDao.clearLocalPaths(listOf(recordId)) } // ✅ 应更新数据库
    }

    @Test
    fun `clearExpiredFiles should handle multiple records correctly`() = runTest {
        // Arrange: 创建两个临时文件
        val file1 = createTempFile("multi1_", ".jpg")
        val file2 = createTempFile("multi2_", ".jpg")

        // ✅ 关键：使用 file:// 格式路径
        val records = listOf(
            InspectionRecord(
                recordId = "rec_a",
                taskId = "task_1",
                localPath = "file:///${file1.absolutePath.replace("\\", "/")}",
                serverUrl = "",
                syncStatus = 0,
                captureTime = System.currentTimeMillis() - (8 * 24 * 60 * 60 * 1000L),
                latitude = 0.0,
                longitude = 0.0,
                address = ""
            ),
            InspectionRecord(
                recordId = "rec_b",
                taskId = "task_1",
                localPath = "file:///${file2.absolutePath.replace("\\", "/")}",
                serverUrl = "",
                syncStatus = 0,
                captureTime = System.currentTimeMillis() - (8 * 24 * 60 * 60 * 1000L),
                latitude = 0.0,
                longitude = 0.0,
                address = ""
            )
        )

        coEvery { mockDao.getRecordsToClean(any()) } returns records

        // Act
        val deletedCount = repository.clearExpiredFiles(7)

        // Assert
        assertEquals(2, deletedCount)
        assertFalse(file1.exists())
        assertFalse(file2.exists())
        coVerify { mockDao.clearLocalPaths(listOf("rec_a", "rec_b")) }
    }

    // -------------------------------------------------------------------------
    // Region: 透传/委托测试
    // -------------------------------------------------------------------------

    @Test
    fun `getAllTasks should return flow from dao`() = runTest {
        val flow = flowOf(listOf<InspectionTask>())
        every { mockDao.getAllTasks() } returns flow
        assertEquals(flow, repository.getAllTasks())
    }

    @Test
    fun `getBatchUnfinishedRecords should use default limit`() = runTest {
        repository.getBatchUnfinishedRecords()
        coVerify { mockDao.getBatchUnfinishedRecords(10) }
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------

    /**
     * 创建安全的临时文件并跟踪清理
     * 注意：在 Windows 上，absolutePath 返回 C:\... 格式
     * 我们在测试中使用 file:///C:/... 格式模拟 Android 行为
     */
    private fun createTempFile(prefix: String, suffix: String): File {
        return File.createTempFile(prefix, suffix).also { file ->
            file.setWritable(true)
            tempFiles.add(file)
        }
    }
}