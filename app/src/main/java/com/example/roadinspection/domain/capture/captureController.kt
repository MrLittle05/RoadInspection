package com.example.roadinspection.domain.capture

import android.content.Context
import android.util.Log
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.InspectionRecord
import com.example.roadinspection.domain.address.AddressProvider
import com.example.roadinspection.domain.camera.CameraHelper
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.worker.WorkManagerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * 视觉巡检控制器
 *
 * 职责：
 * 1. 监听位置和速度
 * 2. 智能切换“里程触发”与“时间预测”模式 (解决高速漏拍问题)
 * 3. 执行拍照并持久化数据
 */
class captureController(
    private val context: Context,
    private val scope: CoroutineScope, // 从 Manager 传进来的作用域
    private val locationProvider: LocationProvider,
    private val cameraHelper: CameraHelper,
    private val repository: InspectionRepository,
    private val onImageSaved: (android.net.Uri) -> Unit
) {

    private val addressProvider = AddressProvider(context)
    private var captureJob: Job? = null

    // 状态变量
    private var currentTaskId: String? = null
    private var lastCaptureDistance = 0f

    companion object {
        private const val TAG = "captureController"
        private const val PHOTO_INTERVAL_METERS = 10.0
        // 速度阈值：36 km/h (10 m/s) 以上视为高速，启用预测模式
        private const val HIGH_SPEED_THRESHOLD_MS = 10.0f
    }

    /**
     * 启动自动抓拍流
     */
    fun start(taskId: String) {
        this.currentTaskId = taskId
        // 重置里程标尺（这里假设每次Start都是新的一段，或者你可以从外面传进来当前的totalDistance）
        this.lastCaptureDistance = locationProvider.getDistanceFlow().value

        Log.i(TAG, "🟢 视觉巡检流已启动 (TaskId: $taskId)")

        captureJob?.cancel()
        captureJob = scope.launch {
            // 使用 isActive 配合 delay 实现主控循环
            while (isActive) {
                val location = locationProvider.getLocationFlow().value
                val currentDistance = locationProvider.getDistanceFlow().value
                val speed = location?.speed ?: 0f

                if (speed > HIGH_SPEED_THRESHOLD_MS) {
                    // === 高速模式 (预测) ===
                    val msPer10m = ((PHOTO_INTERVAL_METERS / speed) * 1000).toLong()
                    val safeDelay = msPer10m.coerceAtLeast(200L) // 只有200ms间隔也没法拍，硬件跟不上

                    Log.v(TAG, "🚀 高速模式 ($speed m/s): 预测将在 ${safeDelay}ms 后拍照")
                    delay(safeDelay)

                    // 拍照 (手动累加里程，因为还没收到GPS更新)
                    lastCaptureDistance += PHOTO_INTERVAL_METERS.toFloat()
                    performCapture(isAuto = true, savedDistance = lastCaptureDistance)

                } else {
                    // === 低速模式 (轮询检测) ===
                    if (currentDistance - lastCaptureDistance >= PHOTO_INTERVAL_METERS) {
                        Log.d(TAG, "🐢 低速模式: 里程达标，触发拍照")
                        lastCaptureDistance = currentDistance
                        performCapture(isAuto = true, savedDistance = currentDistance)
                    }
                    // 低速下不需要太高频检查，500ms 足够
                    delay(500)
                }
            }
        }
    }

    /**
     * 停止抓拍
     */
    fun stop() {
        captureJob?.cancel()
        currentTaskId = null
        Log.i(TAG, "🔴 视觉巡检流已停止")
    }

    /**
     * 手动触发 (透传给 Manager 使用)
     */
    fun manualCapture() {
        if (currentTaskId == null) return
        performCapture(isAuto = false, savedDistance = locationProvider.getDistanceFlow().value)
    }

    // 私有：统一拍照实现
    private fun performCapture(isAuto: Boolean, savedDistance: Float) {
        val taskId = currentTaskId ?: return
        val location = locationProvider.getLocationFlow().value ?: return // 无位置不拍照

        cameraHelper.takePhoto(
            isAuto = isAuto,
            onSuccess = { uri ->
                // 开启子协程处理 IO
                scope.launch(Dispatchers.IO) {
                    // 1. 尝试解析地址 (失败则忽略)
                    val addressStr = try {
                        addressProvider.resolveAddress(location)
                    } catch (e: Exception) { "" }

                    // 2. 存库
                    val record = InspectionRecord(
                        taskId = taskId,
                        localPath = uri.toString(),
                        captureTime = System.currentTimeMillis(),
                        latitude = location.latitude,
                        longitude = location.longitude,
                        address = addressStr,
                        // 如果数据库有字段存当时的里程，可以用 savedDistance
                    )
                    repository.saveRecord(record)

                    // 3. 触发上传任务
                    WorkManagerConfig.scheduleUpload(context)

                    // 4. 回调 UI
                    onImageSaved(uri)
                    Log.d(TAG, "✅ 图片已保存: $uri")
                }
            },
            onError = { e -> Log.e(TAG, "❌ 拍照失败: $e") }
        )
    }
}