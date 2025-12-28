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
import androidx.core.content.ContextCompat
import com.example.roadinspection.data.source.local.WebAppInterfaceImpl
import com.example.roadinspection.domain.camera.CameraHelper
import com.example.roadinspection.domain.inspection.InspectionManager
import com.example.roadinspection.domain.location.GpsSignalProvider
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.network.NetworkStatusProvider
import com.example.roadinspection.ui.theme.GreetingCardTheme
import com.example.roadinspection.utils.DashboardUpdater
import com.example.roadinspection.utils.notifyJsUpdatePhoto

class MainActivity : ComponentActivity() {

    private lateinit var locationProvider: LocationProvider
    private lateinit var networkStatusProvider: NetworkStatusProvider

    private lateinit var gpsSignalProvider: GpsSignalProvider

    // 权限请求启动器
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

        // 使用 applicationContext 防止内存泄漏
        this.locationProvider = LocationProvider(applicationContext)
        this.networkStatusProvider = NetworkStatusProvider(applicationContext)
        this.gpsSignalProvider = GpsSignalProvider(applicationContext)

        // 启动时请求权限
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO
        )

        // 适配 Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (!hasPermissions()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }

        setContent {
            GreetingCardTheme {
                // ImageCapture 保持在 Compose 作用域内
                val imageCapture = remember { ImageCapture.Builder().build() }

                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview(imageCapture = imageCapture)
                    WebViewScreen(
                        locationProvider = locationProvider,
                        gpsSignalProvider = gpsSignalProvider,
                        networkStatusProvider = networkStatusProvider,
                        imageCapture = imageCapture)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (locationProvider.isRecordingDistance) return
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

@Composable
fun CameraPreview(imageCapture: ImageCapture) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

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
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)

                // 简单的触摸缩放逻辑
                val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
                val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val zoomState = camera.cameraInfo.zoomState.value
                        val currentZoomRatio = zoomState?.zoomRatio ?: 1f
                        val delta = detector.scaleFactor
                        camera.cameraControl.setZoomRatio(currentZoomRatio * delta)
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

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun WebViewScreen(
    locationProvider: LocationProvider,
    gpsSignalProvider: GpsSignalProvider,
    networkStatusProvider: NetworkStatusProvider,
    imageCapture: ImageCapture
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope() // 用于 InspectionManager 的协程作用域

    // 状态保持
    var webView: WebView? by remember { mutableStateOf(null) }
    var dashboardUpdater: DashboardUpdater? by remember { mutableStateOf(null) }

    // 1. 实例化 CameraHelper
    val cameraHelper = remember(context, imageCapture) {
        CameraHelper(context, imageCapture)
    }

    val onImageSaved: (Uri) -> Unit = { uri ->
        webView?.notifyJsUpdatePhoto(uri)
    }

    // 2. 实例化 InspectionManager
    // 使用 remember 确保重组时不会重复创建，除非依赖项改变
    val inspectionManager = remember(context, locationProvider, cameraHelper, scope) {
        InspectionManager(context, locationProvider, cameraHelper, scope, onImageSaved)
    }

    // 图片选择器的 Launcher
    val selectImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val script = "onImageSelected(\'$it\')"
            webView?.post { webView?.evaluateJavascript(script, null) }
        }
    }

    // 3. 实例化 WebAppInterfaceImpl
    val webAppInterface = remember(inspectionManager, context, selectImageLauncher) {
        WebAppInterfaceImpl(inspectionManager, context, selectImageLauncher)
    }

    // 生命周期管理：WebView 销毁时停止更新
    DisposableEffect(webView) {
        webView?.let {
            dashboardUpdater = DashboardUpdater(it, locationProvider, gpsSignalProvider, networkStatusProvider)
        }
        onDispose {
            dashboardUpdater?.stop()
            // 如果需要在页面销毁时停止巡检，也可以在这里调用：
            // inspectionManager.stopInspection()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)

                        // 开始更新仪表盘数据
                        dashboardUpdater?.start()

                        // 加载最后一张图片到 Web (如果有)
                        getLatestPhotoUri(ctx)?.let {
                            uri -> view?.notifyJsUpdatePhoto(uri)
                        }
                    }
                }
                loadUrl("file:///android_asset/index.html")
            }.also { webview ->
                webView = webview
                // 注入 Native 接口
                webview.addJavascriptInterface(webAppInterface, "AndroidNative")
            }
        }
    )
}

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