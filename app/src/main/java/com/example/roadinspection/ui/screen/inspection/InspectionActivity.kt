package com.example.roadinspection.ui.screen.inspection

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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.services.core.ServiceSettings
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.AppDatabase
import com.example.roadinspection.domain.camera.CameraHelper
import com.example.roadinspection.domain.inspection.InspectionManager
import com.example.roadinspection.domain.iri.IriCalculator
import com.example.roadinspection.domain.location.GpsSignalProvider
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.network.NetworkStatusProvider
import com.example.roadinspection.ui.bridge.AndroidNativeApiImpl
import com.example.roadinspection.ui.theme.GreetingCardTheme
import com.example.roadinspection.utils.DashboardUpdater
import com.example.roadinspection.utils.notifyJsUpdateIri
import com.example.roadinspection.utils.notifyJsUpdatePhoto
import com.example.roadinspection.worker.WorkManagerConfig
import kotlinx.coroutines.launch
import kotlin.math.abs

class InspectionActivity : ComponentActivity() {

    private lateinit var locationProvider: LocationProvider
    private lateinit var networkStatusProvider: NetworkStatusProvider
    private lateinit var gpsSignalProvider: GpsSignalProvider

    // 巡检必须的重度权限
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
            permissions[Manifest.permission.CAMERA] == true) {
            startTrackingServices()
        } else {
            // 权限被拒，直接退出巡检
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 获取 intent 中的 URL
        val targetUrl = intent.getStringExtra("TARGET_URL") ?: "file:///android_asset/inspection.html"

        // 获取恢复任务的 Id
        val resumeTaskId = intent.getStringExtra("RESUME_TASK_ID")

        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)

        locationProvider = LocationProvider(applicationContext)
        networkStatusProvider = NetworkStatusProvider(applicationContext)
        gpsSignalProvider = GpsSignalProvider(applicationContext)

        // 申请巡检特有权限
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO
        )
        requestPermissionLauncher.launch(permissions)

        setContent {
            GreetingCardTheme {
                // 拦截返回键，防止误触退出巡检
                // 实际业务中可能需要 JS 调用 stopInspection 后再 finish
                BackHandler {
                    // 这里可以加一个二次确认弹窗，或者直接 finish
                    finish()
                }

                val imageCapture = remember { ImageCapture.Builder().build() }
                var currentZoomRatio by remember { mutableFloatStateOf(1f) }

                Box(modifier = Modifier.fillMaxSize()) {
                    // 1. 底层相机
                    CameraPreviewLayer(
                        imageCapture = imageCapture,
                        zoomRatio = currentZoomRatio,
                        onZoomChange = { currentZoomRatio = it }
                    )

                    // 2. 顶层 WebView HUD
                    InspectionWebViewLayer(
                        url = targetUrl,
                        resumeTaskId = resumeTaskId,
                        locationProvider = locationProvider,
                        gpsSignalProvider = gpsSignalProvider,
                        networkStatusProvider = networkStatusProvider,
                        imageCapture = imageCapture,
                        onSetZoom = { currentZoomRatio = it }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startTrackingServices()
    }

    override fun onPause() {
        super.onPause()

        // 仅释放相机预览（视觉层），保留拍照能力
        // CameraX 的 Preview 会在 onPause() 自动暂停渲染，无需手动 unbind
        // 同时 ImageCapture 保持绑定，JS 仍可调用 takePhoto()
    }

    override fun onResume() {
        super.onResume()
        // 相机预览自动恢复（CameraX 绑定到 Lifecycle）
        // 定位如未停止则继续运行，无需额外操作
    }

    override fun onStop() {
        super.onStop()
        // 只有当没有正在记录数据时才停止定位，保证后台巡检能力
        if (!locationProvider.isUpdatingDistance()) {
            stopTrackingServices()
        }
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
}

// 相机预览组件 (逻辑与之前相同，改个名以示区分)
@Composable
fun CameraPreviewLayer(
    imageCapture: ImageCapture,
    zoomRatio: Float,
    onZoomChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val animatedZoom = remember { Animatable(zoomRatio) }

    LaunchedEffect(zoomRatio) {
        camera?.let { cam ->
            val zoomState = cam.cameraInfo.zoomState.value ?: return@let
            val validTarget = zoomRatio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            if (abs(validTarget - animatedZoom.value) > 0.05f) {
                animatedZoom.animateTo(validTarget, tween(250))
            } else {
                animatedZoom.snapTo(validTarget)
            }
        }
    }

    LaunchedEffect(animatedZoom.value) {
        camera?.cameraControl?.setZoomRatio(animatedZoom.value)
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
            preview.setSurfaceProvider(previewView.surfaceProvider)

            try {
                cameraProvider.unbindAll()
                val cam = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                camera = cam

                val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val currentZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                        val newZoom = currentZoom * detector.scaleFactor
                        onZoomChange(newZoom)
                        return true
                    }
                })
                previewView.setOnTouchListener { _, event -> scaleDetector.onTouchEvent(event); true }
            } catch (e: Exception) {
                Log.e("Camera", "Bind failed", e)
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun InspectionWebViewLayer(
    url: String,
    resumeTaskId: String?,
    locationProvider: LocationProvider,
    gpsSignalProvider: GpsSignalProvider,
    networkStatusProvider: NetworkStatusProvider,
    imageCapture: ImageCapture,
    onSetZoom: (Float) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 初始化重度依赖
    val cameraHelper = remember(context, imageCapture) { CameraHelper(context, imageCapture) }
    val iriCalculator = remember(context) { IriCalculator(context, 5.0f) }
    val repository = remember { InspectionRepository(AppDatabase.getDatabase(context).inspectionDao()) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // JS -> Native 回调
    val onImageSaved: (Uri) -> Unit = { uri -> webViewRef?.notifyJsUpdatePhoto(uri) }
    val onIriCalculated: (IriCalculator.IriResult) -> Unit = { res -> webViewRef?.notifyJsUpdateIri(res.iriValue, res.distanceMeters) }

    val inspectionManager = remember {
        InspectionManager(context, repository, locationProvider, cameraHelper, iriCalculator, scope, onImageSaved, onIriCalculated)
    }

    LaunchedEffect(resumeTaskId) {
        if (resumeTaskId != null) {
            inspectionManager.restoreInspection(resumeTaskId)
        }
    }

    val selectImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { }

    BackHandler {
        webViewRef?.evaluateJavascript("window.JSBridge.onNativeBackPressed()", null)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                WebView.setWebContentsDebuggingEnabled(true)

                setBackgroundColor(Color.TRANSPARENT) // 透明背景以显示相机
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = true
                settings.allowUniversalAccessFromFileURLs = true

                // 启动 HUD 更新器
                val updater = DashboardUpdater(this, locationProvider, gpsSignalProvider, networkStatusProvider, repository)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        updater.start()
                    }
                }

                // 注入全功能 Bridge
                val api = AndroidNativeApiImpl(
                    context = ctx,
                    webView = this,
                    scope = scope,
                    repository = repository,
                    inspectionManager = inspectionManager,     // 传入实例
                    selectImageLauncher = selectImageLauncher, // 传入实例
                    onSetZoom = onSetZoom                      // 传入回调
                )
                addJavascriptInterface(api, "AndroidNative")

                loadUrl(url)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        updater.start()

                        // 页面加载完毕后，将恢复的状态注入给 JS
                        if (resumeTaskId != null) {
                            scope.launch {
                                val state = repository.getTaskState(resumeTaskId)
                                if (state.isNotEmpty()) {
                                    val json = org.json.JSONObject(state).toString()
                                    val script = "window.JSBridge.onRestoreState($json)"

                                    Log.d("InspectionActivity", "注入恢复状态: $script")
                                    view?.evaluateJavascript(script, null)
                                }
                            }
                        }
                    }
                }
            }.also { webViewRef = it }
        }
    )
}