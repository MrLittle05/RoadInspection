package com.example.roadinspection.domain.inspection

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.roadinspection.domain.camera.CameraHelper
import com.example.roadinspection.domain.location.AddressProvider // 1. 引入 AddressProvider
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.service.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.example.roadinspection.data.repository.RoadInspectionRepository
import java.util.Date

class InspectionManager(
    private val context: Context,
    private val locationProvider: LocationProvider,
    private val cameraHelper: CameraHelper,
    private val scope: CoroutineScope,
    // 注意：根据文档建议，后期这里应该替换为 Repository，目前先保留用于测试
    private val onImageSaved: (Uri) -> Unit,

    private val repository: RoadInspectionRepository
) {
    // 2. 实例化 AddressProvider (Day 1 任务产出)
    private val addressProvider = AddressProvider(context)

    private var autoCaptureJob: Job? = null
    private var lastCaptureDistance = 0f
    private val PHOTO_INTERVAL_METERS = 10.0

    // 用于记录当前巡检任务的 ID，默认为 -1 (表示未开始)
    private var currentInspectionId: Long = -1L

    // ... startInspection 和 stopInspection 保持不变 ...

    fun startInspection() {
        // ... 保持原有逻辑 ...
        val intent = Intent(context, KeepAliveService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        // ==================================================
        // ✨ 核心修改：向数据库申请新的 ID
        // ==================================================
        scope.launch {
            // 调用 Repository，在数据库创建一条新记录，并拿到它的自增 ID
            // 注意：这里需要 import java.util.Date
            currentInspectionId = repository.startInspection(java.util.Date())

            Log.d("Inspection", "巡检开始，本次任务ID: $currentInspectionId")

            // 拿到 ID 后，再开始记录里程和监听拍照
            // 这样能确保后续所有照片都能关联到正确的 ID
            locationProvider.resetDistanceCounter()
            lastCaptureDistance = 0f
            startAutoCaptureFlow()
        }
    }

    fun stopInspection() {
        context.stopService(Intent(context, KeepAliveService::class.java))
        locationProvider.stopDistanceCounter()
        autoCaptureJob?.cancel()

        // ✨ 记录结束时间
        if (currentInspectionId != -1L) {
            scope.launch {
                repository.endInspection(currentInspectionId, java.util.Date())
                currentInspectionId = -1L // 重置
                Log.d("Inspection", "巡检结束")
            }
        }
    }

    private fun startAutoCaptureFlow() {
        autoCaptureJob?.cancel()
        autoCaptureJob = scope.launch {
            locationProvider.totalDistance.collect { totalDistance ->
                // 达到拍照距离阈值
                if (totalDistance - lastCaptureDistance >= PHOTO_INTERVAL_METERS) {
                    lastCaptureDistance = totalDistance

                    // 执行拍照业务
                    performCapture(isAuto = true)
                }
            }
        }
    }

    fun manualCapture() {
        performCapture(isAuto = false)
    }

    /**
     * 核心业务逻辑封装：拍照 -> 拿定位 -> 查地址 -> (未来存库)
     */
    private fun performCapture(isAuto: Boolean) {
        cameraHelper.takePhoto(
            isAuto = isAuto,
            // 这是一个普通的回调 (Normal Function)
            onSuccess = { savedUri ->

                // ❌ 错误：不能直接在这里调用 saveRecord
                // repository.saveRecord(...)

                // ✅ 正确：启动一个协程 (Coroutine Context)
                scope.launch(Dispatchers.IO) {

                    // 1. 先拿到数据 (此时是 Location? 类型)
                    val currentLocation = locationProvider.locationFlow.value

                    // 2. 先判空！(不要在外面调用函数)
                    if (currentLocation != null) {
                        // ✅ 只有进入这个花括号内部，Kotlin 才确信 currentLocation 不是 null

                        // 3. 在这里调用查地址 (这是正确的位置)
                        val address = addressProvider.resolveAddress(currentLocation)

                        // 4. 在这里调用存库
                        repository.saveRecord(
                            inspectionId = currentInspectionId,
                            photoPath = savedUri.toString(),
                            location = currentLocation,
                            address = address
                        )

                        Log.d("Inspection", "保存成功")
                    } else {
                        Log.e("Inspection", "无法保存：当前没有定位信息")
                    }

                    Log.d("Inspection", "保存成功")
                }
            },
            onError = { Log.e("Manager", "Capture failed: $it") }
        )
    }
}