package com.example.roadinspection.domain.inspection

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.InspectionRecord
import com.example.roadinspection.domain.camera.CameraHelper
import com.example.roadinspection.domain.address.AddressProvider
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.iri.IriCalculator
import com.example.roadinspection.service.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 巡检业务的核心管理器 (Domain Layer / Business Logic).
 *
 * **核心职责：**
 * 1. **全生命周期管理**：协调定位服务、相机、IRI 传感器和前台保活服务的开启与关闭。
 * 2. **双流业务调度**：
 * - **视觉流**：基于 10m 间隔触发自动病害拍照。
 * - **数据流**：基于 50m 间隔触发 IRI (国际平整度) 计算与数据上报。
 * 3. **数据桥接**：将底层的传感器/相机数据封装为业务对象 ([InspectionRecord], [IriCalculator.IriResult]) 并持久化。
 *
 * **架构设计：**
 * 采用“双协程流 (Dual Coroutine Flows)”模式，将高频的距离监听解耦为两个独立的业务动作，
 * 互不阻塞，确保即便相机 I/O 耗时也不会影响 IRI 数据的连续采集。
 *
 * @property context Android 上下文，用于启动服务。
 * @property repository 数据仓库，处理数据库读写。
 * @property locationProvider 位置服务，提供实时里程、经纬度和速度。
 * @property cameraHelper 相机服务，执行实际拍摄。
 * @property iriCalculator IRI 算法核心，负责传感器采集与平整度解算。
 * @property scope 协程作用域，用于执行后台任务。
 * @property onImageSaved 图片保存成功的回调 (用于更新 UI 相册缩略图)。
 * @property onIriCalculated IRI 计算完成的回调 (用于更新 UI 实时图表)。
 */
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
    private val addressProvider = AddressProvider(context)

    // 协程任务句柄
    private var autoCaptureJob: Job? = null
    private var iriCalculationJob: Job? = null

    // 业务状态变量
    /** 当前进行中的任务 ID */
    private var currentTaskId: String? = null

    /** 上一次拍照时的累计里程 */
    private var lastCaptureDistance = 0f

    /** 上一次计算 IRI 时的累计里程 */
    private var lastIriCalculationDistance = 0f

    // 业务配置常量
    companion object {
        private const val TAG = "InspectionManager"

        /** 定距拍照间隔 (米) - 关注路面病害细节 */
        private const val PHOTO_INTERVAL_METERS = 10.0

        /** IRI 计算间隔 (米) - 关注统计学平整度指标 (ASTM 标准建议) */
        private const val IRI_CALC_INTERVAL_METERS = 50.0
    }

    // -------------------------------------------------------------------------
    // Region: 核心业务流程 (Lifecycle)
    // -------------------------------------------------------------------------

    /**
     * 开启巡检任务。
     *
     * **初始化流程：**
     * 1. 启动前台服务保活。
     * 2. 初始化数据库任务记录。
     * 3. **关键**：启动 IRI 传感器监听 (加速度/重力)。
     * 4. 开启 LocationProvider 距离累加。
     * 5. 并行启动 [startAutoCaptureFlow] (拍照) 和 [startIriCalculationFlow] (IRI) 两个业务流。
     *
     * @param title 任务标题 (可选，为空则自动生成时间戳标题)
     */
    fun startInspection(title: String? = null) {
        Log.i(TAG, "🟢 正在启动巡检任务...")
        scope.launch {
            // 1. 启动基础设施
            Log.d(TAG, "1. 启动前台保活服务")
            startKeepAliveService()

            // 2. 准备 IRI 传感器
            Log.d(TAG, "2. 初始化 IRI 传感器监听")
            if (iriCalculator.startListening()) {
                Log.i(TAG, "✅ IRI 传感器启动成功")
            } else {
                Log.e(TAG, "❌ IRI 传感器启动失败! 平整度数据将缺失")
                // 工业级实践：此处应抛出 UI 事件提示用户设备不支持或权限缺失
            }

            // 3. 数据库建单
            val taskTitle = title ?: generateDefaultTitle()
            currentTaskId = repository.createTask(taskTitle)
            Log.i(TAG, "3. 任务创建成功 TaskId: $currentTaskId, Title: $taskTitle")

            // 4. 重置业务状态
            Log.d(TAG, "4. 重置里程计数器")
            locationProvider.startDistanceUpdates()
            lastCaptureDistance = 0f
            lastIriCalculationDistance = 0f

            // 5. 启动双流业务
            Log.i(TAG, "5. 启动双流业务调度 (拍照间隔: ${PHOTO_INTERVAL_METERS}m, IRI间隔: ${IRI_CALC_INTERVAL_METERS}m)")
            startAutoCaptureFlow()
            startIriCalculationFlow()
        }
    }

    /**
     * 停止巡检任务。
     *
     * **清理流程：**
     * 1. 取消所有正在进行的协程任务 (拍照/计算)。
     * 2. 停止位置服务和 IRI 传感器 (释放硬件资源)。
     * 3. 停止前台服务。
     * 4. 数据库结单。
     */
    fun stopInspection() {
        Log.i(TAG, "🔴 正在停止巡检任务...")

        // 1. 停止业务流
        autoCaptureJob?.cancel()
        iriCalculationJob?.cancel()
        Log.d(TAG, "1. 业务协程流已取消")

        // 2. 释放硬件资源
        locationProvider.stopDistanceUpdates()
        iriCalculator.stopListening()
        Log.d(TAG, "2. 硬件资源 (GPS/传感器) 已释放")

        // 3. 停止服务
        stopKeepAliveService()
        Log.d(TAG, "3. 前台服务已停止")

        // 4. 数据库状态更新
        scope.launch {
            currentTaskId?.let { taskId ->
                repository.finishTask(taskId)
                Log.i(TAG, "✅ 任务结单完成 TaskId: $taskId")
            }
            currentTaskId = null
        }
    }

    /**
     * 执行手动拍照。
     *
     * 即使在自动巡检过程中，用户也可以手动触发拍照记录特殊病害。
     * 该操作不会干扰自动拍照和 IRI 计算的计数器。
     */
    fun manualCapture() : Boolean {
        if (currentTaskId == null) {
            Log.w(TAG, "⚠️ 手动拍照请求被忽略: 当前无进行中的任务")
            return false
        }
        Log.i(TAG, "📸 触发手动拍照")
        performCapture(isAuto = false)
        return true
    }

    // -------------------------------------------------------------------------
    // Region: 内部业务逻辑 (Business Flows)
    // -------------------------------------------------------------------------

    /**
     * 业务流 A：自动定距拍照
     * 监听距离变化，每 [PHOTO_INTERVAL_METERS] 米触发一次 [performCapture]。
     */
    private fun startAutoCaptureFlow() {
        autoCaptureJob?.cancel()
        Log.d(TAG, "启动自动拍照流监听...")
        autoCaptureJob = scope.launch {
            locationProvider.getDistanceFlow().collect { totalDistance ->
                if (totalDistance - lastCaptureDistance >= PHOTO_INTERVAL_METERS) {
                    Log.d(TAG, "📏 里程达标 (拍照): Current=${"%.2f".format(totalDistance)}m, Last=${"%.2f".format(lastCaptureDistance)}m")
                    lastCaptureDistance = totalDistance
                    performCapture(isAuto = true)
                }
            }
        }
    }

    /**
     * 业务流 B：IRI 实时计算
     * 监听距离变化，每 [IRI_CALC_INTERVAL_METERS] 米结算一次路面平整度。
     *
     * **逻辑：**
     * 1. 检查距离是否达标 (如 50m)。
     * 2. 从 [LocationProvider] 获取当前瞬时速度 (m/s -> km/h)。
     * 3. 调用 [IriCalculator.computeAndClear] 结算这段距离内的震动数据。
     * 4. 通过 [onIriCalculated] 回调通知 UI 绘制折线图。
     */
    private fun startIriCalculationFlow() {
        iriCalculationJob?.cancel()
        Log.d(TAG, "启动 IRI 计算流监听...")
        iriCalculationJob = scope.launch {
            locationProvider.getDistanceFlow().collect { totalDistance ->
                // 检查是否满足计算间隔 (50m)
                if (totalDistance - lastIriCalculationDistance >= IRI_CALC_INTERVAL_METERS) {

                    // 1. 计算实际段长 (可能略大于 50m，因为 GPS 刷新率限制)
                    val segmentDistance = totalDistance - lastIriCalculationDistance

                    // 2. 获取当前速度 (IRI 算法依赖速度进行归一化或质量评估)
                    val location = locationProvider.getLocationFlow().value
                    val speedKmh = (location?.speed ?: 0f) * 3.6f

                    Log.v(TAG, "📊 触发 IRI 计算: 段长=${"%.1f".format(segmentDistance)}m, 速度=${"%.1f".format(speedKmh)}km/h")

                    // 3. 执行核心计算 (线程安全)
                    val result = iriCalculator.computeAndClear(
                        avgSpeedKmh = speedKmh,
                        distanceMeters = segmentDistance
                    )

                    // 4. 更新里程标尺
                    lastIriCalculationDistance = totalDistance

                    // 5. 分发结果
                    if (result != null) {
                        Log.i(TAG, "✅ IRI 计算完成: Val=${result.iriValue}, Quality=${result.qualityIndex}")
                        // 回调给 UI 层：x轴由 UI 维护(当前总里程)，y轴为 result.iriValue
                        onIriCalculated(result)

                        // TODO: 可选 - 将 track_segment (含 IRI) 存入数据库用于轨迹回放
                        // repository.saveTrackSegment(taskId, totalDistance, result.iriValue, ...)
                    } else {
                        Log.w(TAG, "⚠️ IRI 计算结果为空 (可能因数据样本不足或速度异常)")
                    }
                }
            }
        }
    }

    /**
     * 统一拍照执行逻辑
     *
     * 包含：位置冻结 -> 拍照 -> 地址解析(异步) -> 存库 -> UI通知
     *
     * @param isAuto 是否为自动触发 (用于日志区分)
     */
    private fun performCapture(isAuto: Boolean) {
        val taskId = currentTaskId ?: return
        val modeStr = if (isAuto) "自动" else "手动"

        // 1. 冻结位置 (防止异步操作期间位置漂移)
        val capturedLocation = locationProvider.getLocationFlow().value
        if (capturedLocation == null) {
            Log.w(TAG, "⚠️ 跳过拍照 ($modeStr): 当前位置信息未知")
            return
        }

        Log.v(TAG, "⚡ 开始执行拍照 ($modeStr)...")

        cameraHelper.takePhoto(
            isAuto = isAuto,
            onSuccess = { savedUri ->
                // 2. 切到 IO 线程处理耗时操作 (地址解析 & 数据库)
                scope.launch(Dispatchers.IO) {
                    Log.d(TAG, "📸 相机拍摄成功 ($modeStr), Uri: $savedUri. 正在解析地址...")

                    val addressStr = addressProvider.resolveAddress(capturedLocation)
                    Log.d(TAG, "📍 地址解析完成: $addressStr")

                    val record = InspectionRecord(
                        taskId = taskId,
                        localPath = savedUri.toString(),
                        captureTime = System.currentTimeMillis(),
                        latitude = capturedLocation.latitude,
                        longitude = capturedLocation.longitude,
                        address = addressStr
                    )

                    repository.saveRecord(record)
                    Log.i(TAG, "💾 记录已写入数据库 [${record.id}]")

                    // 3. 通知 UI
                    onImageSaved(savedUri)
                }
            },
            onError = { error ->
                Log.e(TAG, "❌ 拍照失败 ($modeStr): $error")
            }
        )
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