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

class InspectionManager(
    private val context: Context,
    private val locationProvider: LocationProvider,
    private val cameraHelper: CameraHelper,
    private val scope: CoroutineScope,
    // 注意：根据文档建议，后期这里应该替换为 Repository，目前先保留用于测试
    private val onImageSaved: (Uri) -> Unit
) {
    // 2. 实例化 AddressProvider (Day 1 任务产出)
    private val addressProvider = AddressProvider(context)

    private var autoCaptureJob: Job? = null
    private var lastCaptureDistance = 0f
    private val PHOTO_INTERVAL_METERS = 10.0

    // ... startInspection 和 stopInspection 保持不变 ...

    fun startInspection() {
        // ... 保持原有逻辑 ...
        val intent = Intent(context, KeepAliveService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        locationProvider.resetDistanceCounter()
        lastCaptureDistance = 0f
        startAutoCaptureFlow()
    }

    fun stopInspection() {
        context.stopService(Intent(context, KeepAliveService::class.java))
        locationProvider.stopDistanceCounter()
        autoCaptureJob?.cancel()
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
            onSuccess = { savedUri ->
                // 📸 1. 拍照成功，拿到了 Uri

                // 启动协程处理后续耗时操作 (查地址是耗时的)
                scope.launch(Dispatchers.IO) {
                    val currentLocation = locationProvider.locationFlow.value

                    if (currentLocation != null) {
                        // 📍 2. 调用 AddressProvider (这正是你要的那一行代码)
                        // 它会自动判断是直接从 extras 拿，还是去联网查
                        val addressStr = addressProvider.resolveAddress(currentLocation)

                        Log.d("Inspection", "业务闭环: Uri=$savedUri, Addr=$addressStr")

                        // 💾 3. Day 2 任务预留位置：
                        // repository.saveRecord(savedUri, currentLocation, addressStr)
                    }

                    // 临时回调给 UI 显示
                    onImageSaved(savedUri)
                }
            },
            onError = { Log.e("Manager", "Capture failed: $it") }
        )
    }
}