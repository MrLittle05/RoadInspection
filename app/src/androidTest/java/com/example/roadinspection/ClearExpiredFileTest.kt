package com.example.roadinspection

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.AppDatabase
import com.example.roadinspection.data.source.local.InspectionDao
import com.example.roadinspection.data.source.local.InspectionRecord
import com.example.roadinspection.data.source.local.InspectionTask
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClearExpiredFileTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: InspectionDao
    private lateinit var repository: InspectionRepository
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // 使用内存数据库，测试完即销毁，不影响 App 真实数据
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.inspectionDao()
        repository = InspectionRepository(dao)
    }

    @After
    fun closeDb() {
        database.close()
    }

    /**
     * 测试场景 1: 快乐路径 (Happy Path)
     * 文件存在 -> 满足过期条件 -> 执行清理 -> 结果：文件被删，DB字段被清空
     */
    @Test
    fun testClearExpiredFiles_HappyPath() = runBlocking {
        // 1. 准备：创建一个真实的临时文件
        val tempFile = File(context.cacheDir, "test_image.webp")
        tempFile.writeText("Fake Image Content") // 写入点内容确保文件存在
        assertTrue("测试前文件应存在", tempFile.exists())

        // 构造 file:// 格式的 URI (模拟 CameraHelper 的行为)
        val fileUriString = android.net.Uri.fromFile(tempFile).toString()

        // 2. 准备：在数据库插入一条对应的“过期且已同步”记录
        val taskId = UUID.randomUUID().toString()
        dao.insertTask(InspectionTask(taskId = taskId, title = "Test Task"))

        val record = InspectionRecord(
            taskId = taskId,
            localPath = fileUriString, // 指向刚才创建的文件
            syncStatus = 2, // 已同步
            captureTime = System.currentTimeMillis() - (4 * 24 * 60 * 60 * 1000L), // 4天前 (过期)
            latitude = 0.0, longitude = 0.0
        )
        val recordId = dao.insertRecord(record)

        // 3. 执行：调用清理逻辑 (保留3天，所以4天前的应该被删)
        val deletedCount = repository.clearExpiredFiles(retentionDays = 3)

        // 4. 验证
        assertEquals("应成功删除了 1 个文件", 1, deletedCount)
        assertFalse("物理文件应该已被删除", tempFile.exists()) // 关键验证：文件真的没了吗？

        // 验证数据库状态
        val updatedRecords = dao.getRecordsToClean(System.currentTimeMillis())
        assertTrue("数据库中该记录的 localPath 应该已被清空，不应再被查询出来", updatedRecords.isEmpty())
    }

    /**
     * 测试场景 2: 文件早已丢失 (File Missing)
     * 文件不存在 -> 满足过期条件 -> 执行清理 -> 结果：视为成功，DB字段被清空 (修正数据一致性)
     */
    @Test
    fun testClearExpiredFiles_FileMissing() = runBlocking {
        // 1. 准备：构造一个不存在的文件路径
        val missingFile = File(context.cacheDir, "ghost_image.webp")
        if(missingFile.exists()) missingFile.delete() // 确保它不存在
        val fileUriString = android.net.Uri.fromFile(missingFile).toString()

        // 2. 插入数据库记录
        val taskId = UUID.randomUUID().toString()
        dao.insertTask(InspectionTask(taskId = taskId, title = "Test Task"))

        val record = InspectionRecord(
            taskId = taskId,
            localPath = fileUriString,
            syncStatus = 2,
            captureTime = System.currentTimeMillis() - (1000L), // 刚刚
            latitude = 0.0, longitude = 0.0
        )
        dao.insertRecord(record)

        // 3. 执行：设置 retentionDays = 0 (立即过期)
        val count = repository.clearExpiredFiles(retentionDays = 0)

        // 4. 验证：即使文件不在，我们也认为清理“完成”了，因为目的是释放空间/修正数据
        // 根据你的逻辑，如果文件不存在，isPhysicallyDeleted = true，所以会被统计
        // 如果你希望这种情况下 database 也要更新，你的逻辑是支持的
        val cleanCheck = dao.getRecordsToClean(System.currentTimeMillis())
        assertTrue("文件丢失的情况下，数据库也应被更新为已清理", cleanCheck.isEmpty())
    }
}