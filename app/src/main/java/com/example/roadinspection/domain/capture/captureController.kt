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
import java.util.concurrent.atomic.AtomicBoolean

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

    private val isCapturing = AtomicBoolean(false)

    // 🔴 1. 定义最大重试次数 (50ms * 40 = 2000ms = 2秒)
    // 如果2秒还没拍好一张，说明要么车速极快，要么相机卡死，必须跳过
    private val MAX_RETRY_COUNT = 40
    private var retryCount = 0

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
                // 计算积压的里程
                val distanceGap = currentDistance - lastCaptureDistance

                if (distanceGap >= PHOTO_INTERVAL_METERS) {
                    // === A. 正常情况：相机空闲 ===
                    if (!isCapturing.get()) {
                        Log.d(TAG, "📍 触发拍照 (Gap: $distanceGap)")

                        performCapture(isAuto = true, savedDistance = currentDistance)

                        // 核心：只推进10米
                        lastCaptureDistance += PHOTO_INTERVAL_METERS.toFloat()

                        // 成功触发了一次，重置计数器
                        retryCount = 0
                    }
                    // === B. 异常情况：相机忙碌 ===
                    else {
                        retryCount++

                        // 策略1: 还在容忍范围内，只是计数，什么都不做
                        // 下次循环(50ms后)会自然重试
                        if (retryCount < MAX_RETRY_COUNT) {
                            if (retryCount % 10 == 0) Log.v(
                                TAG,
                                "⏳ 相机忙碌，等待中... ($retryCount/$MAX_RETRY_COUNT)"
                            )
                        }
                        // 策略2: 【熔断】超时了，强制跳过！
                        else {
                            Log.e(TAG, "⚠️ 相机卡死或处理过慢，强制跳过本次拍照！(Gap: $distanceGap)")

                            // 1. 强制认为上一张结束了（防止永久锁死）
                            isCapturing.set(false)

                            // 2. 放弃这张照片，把标尺往前拉
                            // 比如积压了 30米，直接把标尺拉到当前位置，虽然丢了片，但保住了后面的流程
                            lastCaptureDistance = currentDistance

                            retryCount = 0
                        }
                    }
                    // === C. 极端情况防御：由于GPS漂移或停车，积压了过大里程 ===
                    // 比如 Gap 突然变成 100米（可能是程序切后台回来），不要连拍10张，直接重置
                    if (distanceGap > 100) {
                        Log.w(TAG, "🚀 里程跳变过大 ($distanceGap m)，重置标尺")
                        lastCaptureDistance = currentDistance
                    }

                    delay(50) // 50ms 检测一次
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
        if (isCapturing.get()) {
            Log.w(TAG, "相机忙碌中，本次触发丢弃") // 至少你知道是因为这里丢的
            return
        }
        isCapturing.set(true)

        val taskId = currentTaskId ?: return
        val location = locationProvider.getLocationFlow().value ?: return // 无位置不拍照

        cameraHelper.takePhoto(
            isAuto = isAuto,
            onSuccess = { uri ->
                isCapturing.set(false)

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
            onError = { e ->
                isCapturing.set(false)
                Log.e(TAG, "❌ 拍照失败: $e")
            }
        )
    }
}