package com.example.roadinspection.domain.inspection

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.InspectionRecord
import com.example.roadinspection.domain.camera.CameraHelper
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.service.KeepAliveService
import com.example.roadinspection.worker.WorkManagerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 巡检业务的核心管理器 (Domain Layer / Business Logic)。
 *
 * **职责：**
 * 1. **流程控制**：协调定位服务、相机和前台保活服务的开启与关闭。
 * 2. **业务调度**：根据 [LocationProvider] 的距离变化触发定距拍照。
 * 3. **数据桥接**：调用 [InspectionRepository] 进行任务创建 (Create Task)、记录存储 (Save Record) 和任务结单 (Finish Task)。
 *
 * **架构位置：**
 * 位于 UI 层（WebAppInterface）与 数据层（Repository）之间，充当“现场指挥官”的角色。
 *
 * @property context 用于启动服务和获取资源。
 * @property repository 数据仓库，用于所有数据库操作。
 * @property locationProvider 位置提供者，提供实时里程和经纬度信息。
 * @property cameraHelper 相机助手，负责实际的拍照动作。
 * @property scope 用于执行数据库操作的协程作用域 (通常由 ViewModel 或 Application 提供)。
 */
class InspectionManager(
    private val context: Context,
    private val repository: InspectionRepository,
    private val locationProvider: LocationProvider,
    private val cameraHelper: CameraHelper,
    private val scope: CoroutineScope,
    private val onImageSaved: (Uri) -> Unit
) {

    /** 自动拍照的任务 Job，用于在停止巡检时取消监听 */
    private var autoCaptureJob: Job? = null

    /** 上一次拍照时的累计里程 (米) */
    private var lastCaptureDistance = 0f

    /** 定距拍照的间隔 (米) */
    private val PHOTO_INTERVAL_METERS = 10.0

    /** 当前进行中的任务 ID。开始巡检时生成，结束时置空。 */
    private var currentTaskId: String? = null

    companion object {
        private const val TAG = "InspectionManager"
    }

    // -------------------------------------------------------------------------
    // Region: 核心业务流程
    // -------------------------------------------------------------------------

    /**
     * 开始巡检业务。
     *
     * **执行流程：**
     * 1. 启动前台保活服务 [KeepAliveService]。
     * 2. 在本地数据库创建一个新的 [InspectionTask]。
     * 3. 重置位置计数器。
     * 4. 开启自动定距拍照流程。
     *
     * @param title 任务标题（可选）。若未提供，将自动生成形如 "巡检任务 2023-10-01 12:00" 的标题。
     */
    fun startInspection(title: String? = null) {
        scope.launch {
            // 1. 启动保活服务 (确保息屏不断网/断定位)
            startKeepAliveService()

            // 2. 生成默认标题 (如果前端没传)
            val taskTitle = title ?: generateDefaultTitle()

            // 3. 存库并获取 TaskID
            currentTaskId = repository.createTask(taskTitle)
            Log.d(TAG, "Inspection started. TaskId: $currentTaskId")

            // 4. 开始更新巡检里程，重置业务数据
            locationProvider.startDistanceUpdates()
            lastCaptureDistance = 0f

            // 5. 开始监听距离变化
            startAutoCaptureFlow()
        }
    }

    /**
     * 停止巡检业务。
     *
     * **执行流程：**
     * 1. 停止前台保活服务。
     * 2. 停止位置监听和自动拍照任务。
     * 3. 在数据库中标记当前任务为“已完成”。
     */
    fun stopInspection() {
        // 1. 停止基础设施
        stopKeepAliveService()
        locationProvider.stopDistanceUpdates()
        autoCaptureJob?.cancel()

        // 2. 更新数据库状态
        scope.launch {
            currentTaskId?.let { taskId ->
                repository.finishTask(taskId)
                Log.d(TAG, "Inspection finished. TaskId: $taskId")
            }
            // 3. 清理内存状态
            currentTaskId = null
        }
    }

    /**
     * 触发一次手动拍照。
     *
     * 适用于用户发现特定病害，手动点击界面按钮进行抓拍的场景。
     * 照片将关联到当前正在进行的任务中。
     */
    fun manualCapture() {
        if (currentTaskId == null) {
            Log.w(TAG, "Manual capture ignored: No active inspection task.")
            return
        }

        cameraHelper.takePhoto(
            isAuto = false,
            onSuccess = { savedUri -> handleImageSaved(savedUri) },
            onError = { error -> Log.e(TAG, "Manual capture failed: $error") }
        )
    }

    // -------------------------------------------------------------------------
    // Region: 内部逻辑与辅助方法
    // -------------------------------------------------------------------------

    /**
     * 开启自动定距拍照流。
     * 监听 [LocationProvider.distanceFlow]，每隔 [PHOTO_INTERVAL_METERS] 米触发一次拍照。
     */
    private fun startAutoCaptureFlow() {
        autoCaptureJob?.cancel()
        autoCaptureJob = scope.launch {
            locationProvider.getDistanceFlow().collect { totalDistance ->
                // 判断是否达到拍照间隔
                if (totalDistance - lastCaptureDistance >= PHOTO_INTERVAL_METERS) {
                    lastCaptureDistance = totalDistance

                    cameraHelper.takePhoto(
                        isAuto = true,
                        onSuccess = { savedUri -> handleImageSaved(savedUri) },
                        onError = { error -> Log.e(TAG, "Auto capture failed: $error") }
                    )
                }
            }
        }
    }

    /**
     * 处理相机保存成功后的逻辑。
     *
     * **核心职责：**
     * 更新 UI, 组装 [InspectionRecord] 对象并调用 Repository 写入数据库。
     *
     * @param uri 图片在本地的 Uri
     */
    private fun handleImageSaved(uri: Uri) {
        val taskId = currentTaskId ?: return // 安全检查：如果没有任务ID，说明任务可能已结束，不存

        // 立即通知 UI 更新图片
        onImageSaved(uri)

        val currentLocation = locationProvider.getLocationFlow().value

        val record = InspectionRecord(
            taskId = taskId,
            localPath = uri.toString(),
            captureTime = System.currentTimeMillis(),
            latitude = currentLocation?.latitude ?: 0.0,
            longitude = currentLocation?.longitude ?: 0.0,
            address = currentLocation?.extras?.getString("address")
        )

        // 存入数据库 (IO 操作由 Repository 内部或 Room 调度，这里只需 launch)
        scope.launch {
            repository.saveRecord(record)
            Log.d(TAG, "Record saved: ${record.localPath}")
            WorkManagerConfig.scheduleUpload(context)
        }
    }

    private fun startKeepAliveService() {
        val intent = Intent(context, KeepAliveService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopKeepAliveService() {
        context.stopService(Intent(context, KeepAliveService::class.java))
    }

    private fun generateDefaultTitle(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return "日常巡检 ${sdf.format(Date())}"
    }
}