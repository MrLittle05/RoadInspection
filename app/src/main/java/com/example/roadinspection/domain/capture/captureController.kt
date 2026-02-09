package com.example.roadinspection.domain.capture

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.InspectionRecord
import com.example.roadinspection.domain.address.AddressProvider
import com.example.roadinspection.domain.camera.CameraHelper
import com.example.roadinspection.domain.iri.IriCalculator
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.worker.WorkManagerConfig
import kotlinx.coroutines.*

/**
 * 综合巡检控制器 (Visual + Data)
 *
 * 职责：
 * 1. 监听位置和速度
 * 2. 智能切换“里程触发”与“时间预测”模式
 * 3. 同频执行：拍照 + IRI计算 + 数据持久化 (10m 间隔)
 */
class CaptureController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val locationProvider: LocationProvider,
    private val cameraHelper: CameraHelper,
    private val iriCalculator: IriCalculator,
    private val repository: InspectionRepository,
    private val onImageSaved: (Uri) -> Unit,
    private val onIriCalculated: (IriCalculator.IriResult) -> Unit
) {

    private val addressProvider = AddressProvider(context)
    private var controlJob: Job? = null

    // 状态变量
    private var currentTaskId: String? = null

    // 合并后的里程标尺 (用于同时控制拍照和IRI)
    private var lastActionDistance = 0f

    companion object {
        private const val TAG = "CaptureController"

        // 统一间隔：10米
        private const val ACTION_INTERVAL_METERS = 10.0f

        // 速度阈值：36 km/h (10 m/s) 以上视为高速，启用预测模式
        private const val HIGH_SPEED_THRESHOLD_MS = 10.0f
    }

    /**
     * 启动自动巡检流 (视觉 + 数据)
     */
    fun start(taskId: String) {
        this.currentTaskId = taskId

        // 初始化里程标尺
        // 注意：如果是 Resume，这里应该保持之前的状态吗？
        // 如果是全新 Start，locationProvider 可能会重置 distance 为 0。
        // 这里假设 locationProvider.distance 是从 0 开始累加的当前段里程。
        val currentDist = locationProvider.getDistanceFlow().value
        if (lastActionDistance == 0f || currentDist < lastActionDistance) {
            lastActionDistance = currentDist
        }

        Log.i(TAG, "🟢 综合巡检流已启动 (TaskId: $taskId, StartDist: $lastActionDistance)")

        controlJob?.cancel()
        controlJob = scope.launch {
            while (isActive) {
                val location = locationProvider.getLocationFlow().value
                val currentDistance = locationProvider.getDistanceFlow().value
                val speed = location?.speed ?: 0f

                // 计算自上次动作以来的增量距离
                val deltaDistance = currentDistance - lastActionDistance

                if (speed > HIGH_SPEED_THRESHOLD_MS) {
                    // === 高速模式 (时间预测) ===
                    // 计算走完 10m 需要多少毫秒
                    val msPerInterval = ((ACTION_INTERVAL_METERS / speed) * 1000).toLong()

                    // 硬件限制保护：如果太快(如>200km/h)，强制至少间隔 200ms
                    val safeDelay = msPerInterval.coerceAtLeast(200L)

                    Log.v(TAG, "🚀 高速模式 ($speed m/s): 预测将在 ${safeDelay}ms 后触发动作")
                    delay(safeDelay)

                    // 时间到了，强制触发动作
                    // 此时 GPS 可能还没更新 distance，我们手动推进标尺
                    lastActionDistance += ACTION_INTERVAL_METERS

                    // 执行综合动作 (传入预估的里程段长，通常就是间隔值)
                    performCombinedAction(isAuto = true, segmentLength = ACTION_INTERVAL_METERS)

                } else {
                    // === 低速模式 (轮询检测) ===
                    if (deltaDistance >= ACTION_INTERVAL_METERS) {
                        Log.d(TAG, "🐢 低速模式: 里程达标 ($deltaDistance >= $ACTION_INTERVAL_METERS)，触发动作")

                        // 更新标尺
                        lastActionDistance = currentDistance

                        // 执行综合动作
                        performCombinedAction(isAuto = true, segmentLength = deltaDistance)
                    }
                    // 轮询间隔
                    delay(500)
                }
            }
        }
    }

    /**
     * 停止巡检流
     */
    fun stop() {
        controlJob?.cancel()
        currentTaskId = null
        lastActionDistance = 0f // 重置
        Log.i(TAG, "🔴 综合巡检流已停止")
    }

    /**
     * 手动触发
     */
    fun manualCapture() {
        if (currentTaskId == null) return
        // 手动拍照通常不计算 IRI (因为距离不足)，或者计算了也只算极短距离的
        // 这里策略是：手动只拍照，不结算 IRI，以免打乱自动流的 buffer
        performPhotoOnly(isAuto = false)
    }

    // ================== 私有动作实现 ==================

    /**
     * 执行综合动作：计算IRI -> 拍照 -> 存库
     * @param segmentLength 本次计算涵盖的距离 (用于 IRI 归一化)
     */
    private fun performCombinedAction(isAuto: Boolean, segmentLength: Float) {
        val taskId = currentTaskId ?: return
        val location = locationProvider.getLocationFlow().value ?: return
        val speedKmh = (location.speed) * 3.6f

        // 1. 计算 IRI (同步执行，非阻塞但轻量)
        val iriResult = iriCalculator.computeAndClear(
            avgSpeedKmh = speedKmh,
            distanceMeters = segmentLength
        )

        // 传递 IRI 给 UI
        if (iriResult != null) {
            onIriCalculated(iriResult)
        } else {
            Log.w(TAG, "⚠️ IRI 计算无效 (距离: $segmentLength, 速度: $speedKmh)")
        }

        // 2. 执行拍照 (异步)
        cameraHelper.takePhoto(
            isAuto = isAuto,
            onSuccess = { uri ->
                // 3. 开启 IO 协程存库
                scope.launch(Dispatchers.IO) {
                    val addressStr = try {
                        addressProvider.resolveAddress(location)
                    } catch (e: Exception) { "" }

                    val record = InspectionRecord(
                        taskId = taskId,
                        localPath = uri.toString(),
                        captureTime = System.currentTimeMillis(),
                        latitude = location.latitude,
                        longitude = location.longitude,
                        address = addressStr,
                        iri = iriResult?.iriValue?.toDouble() ?: 0.0
                    )

                    repository.saveRecord(record)
                    WorkManagerConfig.scheduleUpload(context)

                    onImageSaved(uri)
                    Log.d(TAG, "✅ 记录已保存: IRI=${record.iri}, Path=$uri")
                }
            },
            onError = { e -> Log.e(TAG, "❌ 定距拍照失败: $e") }
        )
    }

    /**
     * 仅拍照 (用于手动触发，不结算 IRI)
     */
    private fun performPhotoOnly(isAuto: Boolean) {
        val taskId = currentTaskId ?: return
        val location = locationProvider.getLocationFlow().value ?: return

        cameraHelper.takePhoto(isAuto, { uri ->
            scope.launch(Dispatchers.IO) {
                val record = InspectionRecord(
                    taskId = taskId,
                    localPath = uri.toString(),
                    captureTime = System.currentTimeMillis(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    address = "手动触发",
                    iri = 0.0 // 手动触发暂无 IRI
                )
                repository.saveRecord(record)
                onImageSaved(uri)
            }
        }, { Log.e(TAG, "手动拍照失败") })
    }
}