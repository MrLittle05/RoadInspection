package com.example.roadinspection.domain.capture

import java.math.BigDecimal
import java.math.RoundingMode
import android.content.Context
import android.location.Location
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 综合巡检控制器 (Visual + Data)
 *
 * 职责：
 * 1. 监听位置和速度 (50ms高频轮询，保障软件插值精度)
 * 2. 智能切换“里程触发”与“时间预测”模式
 * 3. 相机状态管理 (防卡死、积压跳过机制)
 * 4. 同频执行：拍照 + IRI计算 + 数据持久化 (10m 间隔)
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
    private var lastActionDistance = 0f

    // --- main 分支引入的并发控制 ---
    private val isCapturing = AtomicBoolean(false)
    private val MAX_RETRY_COUNT = 40 // 50ms * 40 = 2秒熔断
    private var retryCount = 0

    companion object {
        private const val TAG = "CaptureController"
        private const val ACTION_INTERVAL_METERS = 10.0f
        private const val HIGH_SPEED_THRESHOLD_MS = 10.0f
    }

    /**
     * 启动自动巡检流 (视觉 + 数据)
     */
    fun start(taskId: String) {
        this.currentTaskId = taskId

        val currentDist = locationProvider.getDistanceFlow().value
        if (lastActionDistance == 0f || currentDist < lastActionDistance) {
            lastActionDistance = currentDist
        }

        Log.i(TAG, "🟢 综合巡检流已启动 (TaskId: $taskId, StartDist: $lastActionDistance)")

        controlJob?.cancel()
        controlJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val location = locationProvider.getLocationFlow().value
                val currentDistance = locationProvider.getDistanceFlow().value
                val speed = location?.speed ?: 0f
                val distanceGap = currentDistance - lastActionDistance

                // 1. 极端情况防御 (From main): 里程跳变过大，重置标尺
                if (distanceGap > 100) {
                    Log.w(TAG, "🚀 里程跳变过大 ($distanceGap m)，重置标尺")
                    lastActionDistance = currentDistance
                    continue
                }

                if (speed > HIGH_SPEED_THRESHOLD_MS) {
                    // === 高速模式 (时间预测) ===
                    if (isCapturing.get()) {
                        handleCameraBusy(currentDistance)
                        delay(50)
                        continue
                    }

                    val msPerInterval = ((ACTION_INTERVAL_METERS / speed) * 1000).toLong()
                    val safeDelay = msPerInterval.coerceAtLeast(200L)

                    Log.v(TAG, "🚀 高速模式 ($speed m/s): 预测将在 ${safeDelay}ms 后触发动作")
                    delay(safeDelay)

                    isCapturing.set(true)
                    retryCount = 0
                    lastActionDistance += ACTION_INTERVAL_METERS
                    performCombinedAction(isAuto = true, segmentLength = ACTION_INTERVAL_METERS)

                } else {
                    // === 低速模式 (轮询检测) ===
                    if (distanceGap >= ACTION_INTERVAL_METERS) {
                        if (isCapturing.get()) {
                            handleCameraBusy(currentDistance)
                        } else {
                            Log.d(TAG, "🐢 低速模式: 里程达标 ($distanceGap >= $ACTION_INTERVAL_METERS)")
                            isCapturing.set(true)
                            retryCount = 0

                            // 核心修复 (From main): 严格推进 10m，避免用 currentDistance 导致累积误差
                            lastActionDistance += ACTION_INTERVAL_METERS
                            performCombinedAction(isAuto = true, segmentLength = distanceGap)
                        }
                    }
                }

                // 保持 50ms 高频轮询，提升软件插值定距的精度 (From main)
                delay(50)
            }
        }
    }

    fun stop() {
        controlJob?.cancel()
        currentTaskId = null
        lastActionDistance = 0f
        isCapturing.set(false) // 安全起见，停止时强制释放锁
        Log.i(TAG, "🔴 综合巡检流已停止")
    }

    fun manualCapture() {
        if (currentTaskId == null) return
        if (isCapturing.get()) {
            Log.w(TAG, "⚠️ 相机忙碌中，本次手动触发被忽略")
            return
        }

        isCapturing.set(true)
        performPhotoOnly(isAuto = false)
    }

    // ================== 私有动作实现 ==================

    /**
     * 处理相机忙碌与熔断逻辑 (抽取自 main)
     */
    private fun handleCameraBusy(currentDistance: Float) {
        retryCount++
        if (retryCount < MAX_RETRY_COUNT) {
            if (retryCount % 10 == 0) Log.v(TAG, "⏳ 相机忙碌，等待中... ($retryCount/$MAX_RETRY_COUNT)")
        } else {
            Log.e(TAG, "⚠️ 相机卡死或处理过慢，触发熔断强制跳过！")
            isCapturing.set(false)
            // 放弃积压的照片，把标尺拉到当前位置，保住后续流程
            lastActionDistance = currentDistance
            retryCount = 0
        }
    }

    /**
     * 执行综合动作：计算IRI -> 拍照 -> 存库
     */
    private fun performCombinedAction(isAuto: Boolean, segmentLength: Float) {
        val taskId = currentTaskId ?: return
        val location = locationProvider.getLocationFlow().value ?: return
        val speedKmh = getCalculatedSpeed(location) * 3.6f

        // 1. 计算 IRI
        val iriResult = iriCalculator.computeAndClear(
            avgSpeedKmh = speedKmh,
            distanceMeters = segmentLength
        )

        if (iriResult != null) {
            onIriCalculated(iriResult)
        } else {
            Log.w(TAG, "⚠️ IRI 计算无效 (距离: $segmentLength, 速度: $speedKmh)")
        }

        // 2. 执行拍照 (异步)
        cameraHelper.takePhoto(
            isAuto = isAuto,
            onSuccess = { uri ->
                isCapturing.set(false) // 🟢 合并增补：释放相机锁

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
                        address = addressStr.takeUnless { it == "未知路段 (网络查询失败)" },
                        iri = (iriResult?.iriValue?.toDouble() ?: 0.0).round(2)
                    )

                    repository.saveRecord(record)
                    WorkManagerConfig.scheduleUpload(context)

                    onImageSaved(uri)
                    Log.d(TAG, "✅ 记录已保存: IRI=${record.iri}, Path=$uri")
                }
            },
            onError = { e ->
                isCapturing.set(false) // 🟢 合并增补：异常时也必须释放锁
                Log.e(TAG, "❌ 定距拍照失败: $e")
            }
        )
    }

    /**
     * 仅拍照 (用于手动触发，不结算 IRI)
     */
    private fun performPhotoOnly(isAuto: Boolean) {
        val taskId = currentTaskId ?: return
        val location = locationProvider.getLocationFlow().value ?: return

        cameraHelper.takePhoto(isAuto, { uri ->
            isCapturing.set(false) // 🟢 合并增补：释放相机锁

            scope.launch(Dispatchers.IO) {
                val record = InspectionRecord(
                    taskId = taskId,
                    localPath = uri.toString(),
                    captureTime = System.currentTimeMillis(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    address = "手动触发",
                    iri = 0.00
                )
                repository.saveRecord(record)
                onImageSaved(uri)
            }
        }, {
            isCapturing.set(false) // 🟢 合并增补：异常释放锁
            Log.e(TAG, "手动拍照失败")
        })
    }

    private var lastLocation: Location? = null

    private fun getCalculatedSpeed(currentLocation: Location): Float {
        if (currentLocation.hasSpeed() && currentLocation.speed > 0.5f) {
            return currentLocation.speed
        }

        val prev = lastLocation
        lastLocation = currentLocation
        if (prev != null) {
            val dist = currentLocation.distanceTo(prev)
            val timeSec = (currentLocation.time - prev.time) / 1000f
            if (timeSec > 0.1f) {
                val calcSpeed = dist / timeSec
                if (calcSpeed < 50f) return calcSpeed
            }
        }
        return 0f
    }

    private fun Double.round(decimals: Int): Double {
        return BigDecimal(this).setScale(decimals, RoundingMode.HALF_UP).toDouble()
    }
}