package com.example.roadinspection.domain.inspection

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.domain.camera.CameraHelper
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.iri.IriCalculator
import com.example.roadinspection.service.KeepAliveService
import com.example.roadinspection.worker.WorkManagerConfig
import com.example.roadinspection.domain.capture.CaptureController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InspectionManager(
    private val context: Context,
    private val repository: InspectionRepository,
    private val locationProvider: LocationProvider,
    private val cameraHelper: CameraHelper,
    private val iriCalculator: IriCalculator,
    private val scope: CoroutineScope,
    private val onImageSaved: (android.net.Uri) -> Unit,
    private val onIriCalculated: (IriCalculator.IriResult) -> Unit
) {

    // 基础设施组件
    private val captureController = CaptureController(
        context,
        scope,
        locationProvider,
        cameraHelper,
        iriCalculator,
        repository,
        onImageSaved,
        onIriCalculated
    )

    // 业务状态变量
    private var currentTaskId: String? = null
    private var isPaused: Boolean = false

    /** 历史累计时长 (毫秒) - 包含之前所有会话的时长 */
    private var accumulatedDurationMs: Long = 0L

    /** 本次会话开始时间戳 (毫秒) - 用于计算当前这段未暂停的时长 */
    private var lastSessionStartTime: Long = 0L

    companion object {
        private const val TAG = "InspectionManager"
    }

    // -------------------------------------------------------------------------
    // Region: 核心业务流程 (Lifecycle)
    // -------------------------------------------------------------------------

    fun startInspection(title: String? = null, currentUserId: String) {
        Log.i(TAG, "🟢 正在启动巡检任务...")
        scope.launch {
            // 1. 启动基础设施
            startKeepAliveService()

            // 2. 准备 IRI 传感器
            if (iriCalculator.startListening()) {
                Log.i(TAG, "✅ IRI 传感器启动成功")
            } else {
                Log.e(TAG, "❌ IRI 传感器启动失败!")
            }

            // 3. 数据库建单
            val taskTitle = title ?: generateDefaultTitle()
            currentTaskId = repository.createTask(taskTitle, currentUserId)
            Log.i(TAG, "3. 任务创建成功 TaskId: $currentTaskId")

            // 4. 重置 InspectionManager 业务状态
            accumulatedDurationMs = 0L
            lastSessionStartTime = System.currentTimeMillis()
            isPaused = false

            // 5. 重置 locationProvider 业务状态并启动
            locationProvider.startDistanceUpdates()

            // 6. 启动统一控制器
            currentTaskId?.let { id ->
                captureController.start(id)
            }
        }
    }

    /**
     * 恢复任务现场 (Restore Checkpoint)
     * 场景：用户从主页点击“继续巡检”。
     * 行为：加载数据库状态，恢复里程和计时器，但**保持暂停状态**，等待用户点击“继续”。
     */
    fun restoreInspection(taskId: String) {
        scope.launch {
            Log.i(TAG, "🔄 正在恢复任务: $taskId")

            // 1. 查库获取进度快照
            val task = repository.getTaskById(taskId)
            if (task == null) {
                Log.e(TAG, "❌ 恢复失败: 找不到任务 $taskId")
                return@launch
            }

            currentTaskId = taskId

            // 2. 恢复计时状态
            accumulatedDurationMs = (task.currentDuration) * 1000L
            lastSessionStartTime = 0L // 尚未开始新的一段计时

            // 3. 恢复里程状态
            locationProvider.setInitialDistance(task.currentDistance)

            // 4. 设置为暂停模式 (关键：不启动传感器，不启动服务)
            isPaused = true

            // 注意：此时 captureController 不需要启动，等待 resumeInspection 调用

            Log.i(TAG, "✅ 现场已恢复 (Paused): Dist=${task.currentDistance}m, Dur=${task.currentDuration}s")
        }
    }

    fun pauseInspection() {
        if (currentTaskId == null || isPaused) return

        Log.i(TAG, "⏸️ 正在暂停巡检...")

        if (lastSessionStartTime > 0) {
            val sessionDuration = System.currentTimeMillis() - lastSessionStartTime
            accumulatedDurationMs += sessionDuration
            lastSessionStartTime = 0L // 归零，表示当前没有正在进行的计时段
        }

        // 停止硬件服务
        locationProvider.pauseDistanceUpdates()
        iriCalculator.stopListening()
        captureController.stop()

        isPaused = true

        // 保存状态（此时 accumulatedDurationMs 已经是最新且包含刚才那段的了）
        saveCheckpoint()
    }

    fun resumeInspection() {
        if (currentTaskId == null || !isPaused) return

        Log.i(TAG, "▶️ 正在恢复巡检...")

        scope.launch {
            // 恢复硬件服务
            locationProvider.resumeDistanceUpdates()
            iriCalculator.startListening()

            currentTaskId?.let { id ->
                captureController.start(id)
            }

            lastSessionStartTime = System.currentTimeMillis()

            isPaused = false
        }
    }

    fun stopInspection() {
        Log.i(TAG, "🔴 正在停止巡检任务...")

        // 1. 停止业务流
        captureController.stop()

        // 2. 释放硬件资源
        locationProvider.stopDistanceUpdates()
        iriCalculator.stopListening()

        // 3. 停止服务
        stopKeepAliveService()

        // 4. 数据库状态更新
        scope.launch {
            currentTaskId?.let { taskId ->
                repository.finishTask(taskId)
                WorkManagerConfig.scheduleUpload(context)
            }
            currentTaskId = null
        }
    }

    fun manualCapture() : Boolean {
        if (currentTaskId == null) return false
        captureController.manualCapture()
        return true
    }

    /**
     * 保存当前任务进度缓存 (Checkpoint)。
     */
    fun saveCheckpoint() {
        val taskId = currentTaskId
        if (taskId == null) {
            Log.w(TAG, "⚠️ 尝试保存进度但当前无任务")
            return
        }

        // 1. 计算当前总时长 (毫秒)
        // 逻辑：总时长 = 历史累计 + 当前这趟没暂停的时长(如果是运行状态)
        val currentSessionDuration = if (!isPaused && lastSessionStartTime > 0) {
            System.currentTimeMillis() - lastSessionStartTime
        } else {
            0L // 如果已暂停，当前会话时长已经在 pauseInspection 里结算进 accumulatedDurationMs 了
        }

        val totalDurationMs = accumulatedDurationMs + currentSessionDuration
        val totalDurationSeconds = totalDurationMs / 1000L

        // 2. 获取当前高精度里程
        val realDistance = locationProvider.getDistanceFlow().value

        // 3. 存入数据库
        scope.launch {
            repository.saveTaskCheckpoint(taskId, realDistance, totalDurationSeconds)
        }
        Log.d(TAG, "💾 保存进度: Task=$taskId, Dist=${"%.1f".format(realDistance)}m, Time=${totalDurationSeconds}s")
    }

    // -------------------------------------------------------------------------
    // Region: 辅助方法
    // -------------------------------------------------------------------------

    private fun startKeepAliveService() {
        try {
            val intent = Intent(context, KeepAliveService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动保活服务异常", e)
        }
    }

    private fun stopKeepAliveService() {
        try {
            context.stopService(Intent(context, KeepAliveService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "停止保活服务异常", e)
        }
    }

    private fun generateDefaultTitle(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return "日常巡检 ${sdf.format(Date())}"
    }
}