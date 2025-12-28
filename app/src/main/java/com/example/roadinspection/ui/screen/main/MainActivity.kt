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
import com.amap.api.services.core.ServiceSettings
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

        // 高德地图隐私合规接口 (必须在初始化之前调用)
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)

        // 使用 ApplicationContext 避免内存泄漏
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
                val imageCapture = remember { ImageCapture.Builder().build() }

                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview(imageCapture = imageCapture)
                    WebViewScreen(
                        locationProvider = locationProvider,
                        gpsSignalProvider = gpsSignalProvider,
                        networkStatusProvider = networkStatusProvider,
                        imageCapture = imageCapture
                    )
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
    val scope = rememberCoroutineScope()

    // CameraHelper
    val cameraHelper = remember(context, imageCapture) {
        CameraHelper(context, imageCapture)
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val onImageSaved: (Uri) -> Unit = { uri ->
        webViewRef?.notifyJsUpdatePhoto(uri)
    }

    // InspectionManager
    val inspectionManager = remember(context, locationProvider, cameraHelper, scope) {
        InspectionManager(context, locationProvider, cameraHelper, scope, onImageSaved)
    }

    // Image Launcher
    val selectImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        // Handle image selection if needed
    }

    // WebAppInterface
    val webAppInterface = remember(inspectionManager, context, selectImageLauncher) {
        WebAppInterfaceImpl(inspectionManager, context, selectImageLauncher)
    }

    // DashboardUpdater Ref
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

                // 创建 Updater
                val updater = DashboardUpdater(this, locationProvider, gpsSignalProvider, networkStatusProvider)
                dashboardUpdaterRef.value = updater

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        updater.start()
                        getLatestPhotoUri(ctx)?.let { uri ->
                            view?.notifyJsUpdatePhoto(uri)
                        }
                    }
                }

                addJavascriptInterface(webAppInterface, "AndroidNative")
                loadUrl("file:///android_asset/index.html")
            }.also {
                webViewRef = it
            }
        },
        // [Compose 优化] 确保重组时不会重新加载 URL
        update = { }
    )

    // 清理资源
    DisposableEffect(Unit) {
        onDispose {
            dashboardUpdaterRef.value?.stop()
        }
    }
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