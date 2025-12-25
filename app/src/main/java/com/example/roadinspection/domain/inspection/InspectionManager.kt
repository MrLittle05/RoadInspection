package com.example.roadinspection.domain.inspection

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.roadinspection.domain.camera.CameraHelper
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.service.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


class InspectionManager(
    private val context: Context,
    private val locationProvider: LocationProvider,
    private val cameraHelper: CameraHelper,
    private val scope: CoroutineScope,
    private val onImageSaved: (Uri) -> Unit
) {
    private var autoCaptureJob: Job? = null
    private var lastCaptureDistance = 0.0
    private val PHOTO_INTERVAL_METERS = 10.0

    // 开始业务
    fun startInspection() {
        // 1. 启动保活服务
        val intent = Intent(context, KeepAliveService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        // 2. 重置数据
        locationProvider.resetDistanceCounter()
        lastCaptureDistance = 0.0

        // 3. 开始监听流并触发拍照
        startAutoCaptureFlow()
    }

    // 停止业务
    fun stopInspection() {
        context.stopService(Intent(context, KeepAliveService::class.java))
        locationProvider.stopDistanceCounter()
        autoCaptureJob?.cancel()
    }

    private fun startAutoCaptureFlow() {
        autoCaptureJob?.cancel()
        autoCaptureJob = scope.launch {
            locationProvider.distanceFlow.collect { totalDistance ->
                if (totalDistance - lastCaptureDistance >= PHOTO_INTERVAL_METERS) {
                    lastCaptureDistance = totalDistance

                    cameraHelper.takePhoto(
                        true,
                        onSuccess = {savedUri -> onImageSaved(savedUri)},
                        onError = { Log.e("Manager", "Auto capture failed: $it") }
                    )
                }
            }
        }
    }

    fun manualCapture(callback: (Uri) -> Unit) {
        cameraHelper.takePhoto(false, onSuccess = callback, onError = {})
    }
}