package com.example.roadinspection.ui.screen.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlin.math.abs
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.amap.api.services.core.ServiceSettings
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.AppDatabase
import com.example.roadinspection.domain.iri.IriCalculator
import com.example.roadinspection.domain.camera.CameraHelper
import com.example.roadinspection.domain.inspection.InspectionManager
import com.example.roadinspection.domain.location.GpsSignalProvider
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.network.NetworkStatusProvider
import com.example.roadinspection.ui.theme.GreetingCardTheme
import com.example.roadinspection.ui.bridge.AndroidNativeApiImpl
import com.example.roadinspection.utils.DashboardUpdater
import com.example.roadinspection.utils.notifyJsUpdatePhoto
import com.example.roadinspection.utils.notifyJsUpdateIri
import com.example.roadinspection.worker.WorkManagerConfig
import com.example.roadinspection.data.model.VersionInfo
import com.example.roadinspection.utils.UpdateManager
import com.example.roadinspection.R
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var locationProvider: LocationProvider
    private lateinit var networkStatusProvider: NetworkStatusProvider
    private lateinit var gpsSignalProvider: GpsSignalProvider

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startTrackingServices()
        }
        Log.d("Permissions", "Permissions granted: $permissions")
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 👇 核心代码在这里
        // 使用 lifecycleScope 启动一个协程
        lifecycleScope.launch {

            // 1. 模拟：这里应该是一个真正的网络请求，去获取服务器的 JSON
            // 假设这是从服务器拿到的数据
            val serverVersion = VersionInfo(
                versionCode = 2,
                versionName = "1.1.0",
                downloadUrl = "http://你的服务器/app.apk",
                forceUpdate = false,
                description = "修复了一些Bug"
            )

            // 2. 调用你的 UpdateManager
            // 此时，UpdateManager 里的代码就会变成“被引用”状态，不再报灰
            UpdateManager.checkAndDownload(this@MainActivity, serverVersion)
        }

        // 1. 初始化高德地图隐私配置 (必须在初始化 Provider 前)
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)

        // 2. 初始化核心服务 (使用 ApplicationContext 防止泄漏)
        this.locationProvider = LocationProvider(applicationContext)
        this.networkStatusProvider = NetworkStatusProvider(applicationContext)
        this.gpsSignalProvider = GpsSignalProvider(applicationContext)

        // 3. 动态权限申请
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (!hasPermissions()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }

        // 4. 调度未完成任务上传
        WorkManagerConfig.scheduleUpload(applicationContext)

        // 5. 调度每日清理任务
        WorkManagerConfig.scheduleDailyCleanup(applicationContext)

        setContent {
            GreetingCardTheme {
                val imageCapture = remember { ImageCapture.Builder().build() }

                // [State Hoisting] 提升缩放状态到顶层，作为单一信源
                // 默认 1.0x (无缩放)
                var currentZoomRatio by remember { mutableFloatStateOf(1f) }

                Box(modifier = Modifier.fillMaxSize()) {
                    // 相机预览层：响应 zoomRatio 变化
                    CameraPreview(
                        imageCapture = imageCapture,
                        zoomRatio = currentZoomRatio,
                        onZoomChange = { newZoom -> currentZoomRatio = newZoom }
                    )

                    // Web UI 层：接收 JS 的 setZoom 指令
                    WebViewScreen(
                        locationProvider = locationProvider,
                        gpsSignalProvider = gpsSignalProvider,
                        networkStatusProvider = networkStatusProvider,
                        imageCapture = imageCapture,
                        onSetZoom = { value -> currentZoomRatio = value }
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 如果正在计程(巡检中)，不停止服务以保持后台记录
        if (locationProvider.isUpdatingDistance()) return
        stopTrackingServices()
    }

    override fun onStart() {
        super.onStart()
        if (hasPermissions()) startTrackingServices()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTrackingServices()
    }

    private fun startTrackingServices() {
        locationProvider.startLocationUpdates()
        gpsSignalProvider.startGpsSignalUpdates()
    }

    private fun stopTrackingServices() {
        locationProvider.stopLocationUpdates()
        gpsSignalProvider.stopGpsSignalUpdates()
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 相机预览组件
 * * @param zoomRatio 外部传入的目标缩放倍率
 * @param onZoomChange 手势缩放时的回调，用于更新外部状态
 */
@Composable
fun CameraPreview(
    imageCapture: ImageCapture,
    zoomRatio: Float,
    onZoomChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // 保存 Camera 实例引用以操作 Zoom
    var camera by remember { mutableStateOf<Camera?>(null) }

    // 创建一个可动画的 float 值，初始值为当前的 zoomRatio
    val animatedZoom = remember { Animatable(zoomRatio) }

    // 监听外部 zoomRatio 的变化
    LaunchedEffect(zoomRatio) {
        camera?.let { cam ->
            val zoomState = cam.cameraInfo.zoomState.value
            if (zoomState != null) {
                // A. 获取硬件真实的边界
                // 小米可能是 0.6，三星可能是 0.5，Pixel 可能是 0.7
                val realMin = zoomState.minZoomRatio
                val realMax = zoomState.maxZoomRatio

                // B. 修正目标值 (Target Correction)
                // 如果传入 0.5 但最小是 0.6，这就把目标修正为 0.6
                val validTarget = zoomRatio.coerceIn(realMin, realMax)

                // C. 计算差值 (用修正后的目标值计算)
                val diff = abs(validTarget - animatedZoom.value)

                // D. 执行动画
                // 只有当变化幅度确实较大时才动画，否则吸附
                if (diff > 0.05f) { // 阈值稍微调低一点点
                    animatedZoom.animateTo(
                        targetValue = validTarget,
                        animationSpec = tween(durationMillis = 250, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                    )
                } else {
                    animatedZoom.snapTo(validTarget)
                }
            }
        }
    }

    // 监听动画值的变化，并驱动相机硬件
    // 这样 CameraX 接收到的是一串连续变化的数值 (1.0, 0.95, 0.9 ... 0.5)
    LaunchedEffect(animatedZoom.value) {
        camera?.let { cam ->
            val zoomState = cam.cameraInfo.zoomState.value
            if (zoomState != null) {
                // 安全钳制
                val clampedRatio = animatedZoom.value.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                cam.cameraControl.setZoomRatio(clampedRatio)
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { previewView ->
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            preview.setSurfaceProvider(previewView.surfaceProvider)

            try {
                cameraProvider.unbindAll()
                // 绑定生命周期并获取 Camera 对象
                val cam = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
                camera = cam

                // 配置手势监听器
                val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val zoomState = cam.cameraInfo.zoomState.value
                        val currentZoom = zoomState?.zoomRatio ?: 1f
                        val delta = detector.scaleFactor
                        val newZoom = currentZoom * delta

                        // 1. 应用缩放 (即时响应手势)
                        cam.cameraControl.setZoomRatio(newZoom)
                        // 2. 更新上层状态 (保持 UI 同步)
                        onZoomChange(newZoom)
                        return true
                    }
                })

                previewView.setOnTouchListener { _, event ->
                    scaleGestureDetector.onTouchEvent(event)
                    true
                }
            } catch (exc: Exception) {
                Log.e("CameraPreview", "相机绑定失败", exc)
            }
        }
    )
}

/**
 * WebView 容器组件
 * * @param onSetZoom 接收来自 JS 的缩放指令
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun WebViewScreen(
    locationProvider: LocationProvider,
    gpsSignalProvider: GpsSignalProvider,
    networkStatusProvider: NetworkStatusProvider,
    imageCapture: ImageCapture,
    onSetZoom: (Float) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 1. 业务对象初始化 (使用 remember 保持引用)
    val cameraHelper = remember(context, imageCapture) { CameraHelper(context, imageCapture) }

    // IriCalculator (标定系数应从配置读取，此处暂定 5.0)
    val iriCalculator = remember(context) { IriCalculator(context, calibrationFactor = 5.0f) }

    val database = remember { AppDatabase.getDatabase(context) }
    val dao = remember { database.inspectionDao() }
    val repository = remember { InspectionRepository(dao) }

    // 2. 定义回调函数
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val onImageSaved: (Uri) -> Unit = { uri ->
        webViewRef?.notifyJsUpdatePhoto(uri)
    }

    val onIriCalculated: (IriCalculator.IriResult) -> Unit = { result ->
        webViewRef?.notifyJsUpdateIri(result.iriValue, result.distanceMeters)
    }

    val inspectionManager = remember(context, locationProvider, cameraHelper, scope) {
        InspectionManager(context, repository, locationProvider, cameraHelper, iriCalculator, scope, onImageSaved, onIriCalculated)
    }

    val selectImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> }

    // 3. 注入 JS 接口 (包含 onSetZoom)
    val androidNativeApi = remember(inspectionManager, context, selectImageLauncher, onSetZoom) {
        AndroidNativeApiImpl(inspectionManager, context, selectImageLauncher, onSetZoom)
    }

    val dashboardUpdaterRef = remember { mutableStateOf<DashboardUpdater?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true

                // 启动数据更新器
                val updater = DashboardUpdater(this, locationProvider, gpsSignalProvider, networkStatusProvider, repository)
                dashboardUpdaterRef.value = updater

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        updater.start()
                        // 恢复最后一张图片
                        getLatestPhotoUri(ctx)?.let { uri ->
                            view?.notifyJsUpdatePhoto(uri)
                        }
                    }
                }

                addJavascriptInterface(androidNativeApi, "AndroidNative")
                loadUrl("file:///android_asset/index.html")
            }.also { webViewRef = it }
        },
        update = { }
    )

    // 页面销毁时停止更新
    DisposableEffect(Unit) {
        onDispose { dashboardUpdaterRef.value?.stop() }
    }
}

/**
 * 获取设备上最新的一张图片 Uri
 */
private fun getLatestPhotoUri(context: Context): Uri? {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val id = cursor.getLong(idColumn)
            return ContentUris.withAppendedId(collection, id)
        }
    }
    return null
}