package com.example.roadinspection.data.repository

import com.example.roadinspection.data.source.local.InspectionDao
import com.example.roadinspection.data.source.local.InspectionRecord
import com.example.roadinspection.data.source.local.InspectionTask
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [InspectionRepository] 的本地单元测试 (Local Unit Test)。
 *
 * **测试策略：**
 * 使用 [Mockk] 模拟底层 [InspectionDao] 的行为，验证 Repository 层的业务逻辑封装是否正确。
 * 主要关注：参数传递准确性、默认值逻辑、以及方法调用的委托关系。
 *
 * **环境：** JVM (无需 Android 模拟器)。
 */
class InspectionRepositoryTest {

    // 1. 创建 Mock DAO (relaxed = true 表示默认返回空值，不报错，简化测试设置)
    private val mockDao = mockk<InspectionDao>(relaxed = true)

    // 2. 注入 Mock DAO
    private val repository = InspectionRepository(mockDao)

    // -------------------------------------------------------------------------
    // Region: Task 业务逻辑测试
    // -------------------------------------------------------------------------

    /**
     * 测试创建任务的业务逻辑。
     *
     * **测试目标：** 验证 Repository 是否正确组装了 [InspectionTask] 对象。
     * **验证点：**
     * 1. 是否生成了 Task 对象并调用了 DAO 的插入方法。
     * 2. 任务标题是否与输入一致。
     * 3. `inspectorId` 是否使用了预设的默认值 ("user_default")。
     * 4. 是否自动生成了 `startTime`。
     */
    @Test
    fun `createTask should insert task with correct title and inspectorId`() = runTest {
        // Arrange
        val title = "测试巡检"
        val taskSlot = slot<InspectionTask>() // 用于捕获传给 DAO 的参数

        // Act
        val taskId = repository.createTask(title)

        // Assert
        // 验证 insertTask 被调用了 1 次，并捕获传入的 task 对象
        coVerify(exactly = 1) { mockDao.insertTask(capture(taskSlot)) }

        // 验证返回值和捕获值的正确性
        val capturedTask = taskSlot.captured
        assertEquals("返回的ID应与生成的ID一致", taskId, capturedTask.taskId)
        assertEquals("标题应透传", title, capturedTask.title)
        assertEquals("应使用默认巡检员ID", "user_default", capturedTask.inspectorId)
        assertNotNull("应自动生成开始时间", capturedTask.startTime)
    }

    /**
     * 测试结束任务的逻辑。
     *
     * **测试目标：** 验证 Repository 是否在调用 DAO 时自动补全了结束时间戳。
     * **验证点：** [InspectionDao.finishTask] 被调用，且第二个参数 (`endTime`) 是一个有效的非零时间戳。
     */
    @Test
    fun `finishTask should call dao with timestamp`() = runTest {
        // Arrange
        val taskId = "uuid-123"

        // Act
        repository.finishTask(taskId)

        // Assert
        coVerify(exactly = 1) {
            // any() 匹配任何参数，这里用于验证时间戳只要传了就行
            mockDao.finishTask(eq(taskId), any())
        }
    }

    /**
     * 测试获取未同步任务的委托逻辑。
     *
     * **测试目标：** 验证 Repository 直接透传调用 DAO 的 [InspectionDao.getUnsyncedTasks]。
     */
    @Test
    fun `getUnsyncedTasks should delegate to dao`() = runTest {
        // Act
        repository.getUnsyncedTasks()
        // Assert
        coVerify(exactly = 1) { mockDao.getUnsyncedTasks() }
    }

    /**
     * 测试获取“已完结但未同步状态”任务的委托逻辑。
     *
     * **测试目标：** 验证 Repository 直接透传调用 DAO 的对应方法。
     */
    @Test
    fun `getFinishedButNotSyncedTasks should delegate to dao`() = runTest {
        // Act
        repository.getFinishedButNotSyncedTasks()
        // Assert
        coVerify(exactly = 1) { mockDao.getFinishedButNotSyncedTasks() }
    }

    /**
     * 测试更新同步状态的委托逻辑。
     *
     * **测试目标：** 验证参数 (`taskId`, `newState`) 是否正确传递给了 DAO。
     */
    @Test
    fun `updateTaskSyncState should delegate to dao`() = runTest {
        // Act
        repository.updateTaskSyncState("uuid-1", 2)
        // Assert
        coVerify(exactly = 1) { mockDao.updateTaskSyncState("uuid-1", 2) }
    }

    /**
     * 测试获取所有任务列表的数据流委托。
     *
     * **测试目标：** 验证 Repository 返回的 Flow 对象就是 DAO 返回的那个 Flow 对象 (引用一致性)。
     */
    @Test
    fun `getAllTasks should return flow from dao`() = runTest {
        // Arrange: 模拟 DAO 返回一个特定的 Flow
        val mockFlow = flowOf(listOf<InspectionTask>())
        every { mockDao.getAllTasks() } returns mockFlow

        // Act
        val result = repository.getAllTasks()

        // Assert
        assertEquals("应直接返回 DAO 的 Flow 对象", mockFlow, result)
        verify(exactly = 1) { mockDao.getAllTasks() }
    }

    // -------------------------------------------------------------------------
    // Region: Record 业务逻辑测试
    // -------------------------------------------------------------------------

    /**
     * 测试保存记录的委托逻辑。
     *
     * **测试目标：** 验证 [InspectionRepository.saveRecord] 正确调用了 DAO 的插入方法。
     */
    @Test
    fun `saveRecord should call insertRecord`() = runTest {
        // Arrange
        val record = mockk<InspectionRecord>()
        // Act
        repository.saveRecord(record)
        // Assert
        coVerify(exactly = 1) { mockDao.insertRecord(record) }
    }

    /**
     * 测试更新记录的委托逻辑。
     *
     * **测试目标：** 验证 [InspectionRepository.updateRecord] 正确调用了 DAO 的更新方法。
     */
    @Test
    fun `updateRecord should call updateRecord`() = runTest {
        // Arrange
        val record = mockk<InspectionRecord>()
        // Act
        repository.updateRecord(record)
        // Assert
        coVerify(exactly = 1) { mockDao.updateRecord(record) }
    }

    /**
     * 测试按任务获取记录列表的委托逻辑。
     *
     * **测试目标：** 验证 Repository 正确调用了 DAO 的查询方法并传递了 taskId。
     */
    @Test
    fun `getRecordsByTask should delegate to dao`() = runTest {
        // Act
        repository.getRecordsByTask("task-1")
        // Assert
        verify(exactly = 1) { mockDao.getRecordsByTask("task-1") }
    }

    /**
     * 测试按状态筛选记录的委托逻辑。
     *
     * **测试目标：** 验证 Repository 正确传递了 taskId 和 status 参数。
     */
    @Test
    fun `getRecordsByTaskAndStatus should delegate to dao`() = runTest {
        // Act
        repository.getRecordsByTaskAndStatus("task-1", 1)
        // Assert
        verify(exactly = 1) { mockDao.getRecordsByTaskAndStatus("task-1", 1) }
    }

    /**
     * 测试批量获取未完成记录的**默认参数**逻辑。
     *
     * **测试目标：** 验证当调用者不传参数时，Repository 是否使用了默认值 `limit = 10`。
     */
    @Test
    fun `getBatchUnfinishedRecords should use default limit 10`() = runTest {
        // Act: 不传参调用
        repository.getBatchUnfinishedRecords()
        // Assert
        coVerify(exactly = 1) { mockDao.getBatchUnfinishedRecords(10) }
    }

    /**
     * 测试批量获取未完成记录的**自定义参数**逻辑。
     *
     * **测试目标：** 验证当调用者传递参数时，Repository 是否透传了该参数。
     */
    @Test
    fun `getBatchUnfinishedRecords should pass custom limit`() = runTest {
        // Act: 传参 50
        repository.getBatchUnfinishedRecords(limit = 50)
        // Assert
        coVerify(exactly = 1) { mockDao.getBatchUnfinishedRecords(50) }
    }

    /**
     * 测试未完成数量统计的委托逻辑。
     *
     * **测试目标：** 验证属性访问 (`repository.unfinishedCount`) 是否正确触发了 DAO 的调用。
     */
    @Test
    fun `unfinishedCount should delegate to dao`() = runTest {
        // Act
        // 访问 property 会触发 getter
        repository.unfinishedCount
        // Assert
        verify(exactly = 1) { mockDao.getUnfinishedCount() }
    }
}