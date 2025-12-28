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
import com.example.roadinspection.data.source.local.WebAppInterfaceImpl
import com.example.roadinspection.domain.location.LocationProvider
import com.example.roadinspection.domain.network.NetworkStatusProvider
import com.example.roadinspection.ui.theme.GreetingCardTheme
import com.amap.api.services.core.ServiceSettings
import com.example.roadinspection.utils.DashboardUpdater
import com.example.roadinspection.utils.GPSSignalUpdater


class MainActivity : ComponentActivity() {

    private lateinit var locationProvider: LocationProvider
    private lateinit var networkStatusProvider: NetworkStatusProvider
    private lateinit var gpsSignalUpdater: GPSSignalUpdater

    // 权限请求启动器
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            locationProvider.startLocationUpdates()
            if (::gpsSignalUpdater.isInitialized) {
                gpsSignalUpdater.start()
            }
        }
        Log.d("Permissions", "Permissions granted: $permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)

        locationProvider = LocationProvider(this)
        networkStatusProvider = NetworkStatusProvider(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gpsSignalUpdater = GPSSignalUpdater(this)
        }

        // 1. 启动时立即请求所有必要权限
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE, // 获取网络状态需要
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        requestPermissionLauncher.launch(permissions.toTypedArray())

        setContent {
            GreetingCardTheme {
                val imageCapture = remember { ImageCapture.Builder().build() }

                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview(imageCapture)
                    if (::gpsSignalUpdater.isInitialized) {
                        WebViewScreen(locationProvider, networkStatusProvider, gpsSignalUpdater, imageCapture)
                    } else {
                        // Fallback for older devices without GnssStatus support
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(imageCapture: ImageCapture) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            PreviewView(it).apply {
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    locationProvider: LocationProvider,
    networkStatusProvider: NetworkStatusProvider,
    gpsSignalUpdater: GPSSignalUpdater,
    imageCapture: ImageCapture
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var dashboardUpdater: DashboardUpdater? by remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()

    val selectImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val script = "onImageSelected('$it')"
            webView?.post {
                webView?.evaluateJavascript(script, null)
            }
        }
    }

    DisposableEffect(webView) {
        webView?.let {
            dashboardUpdater = DashboardUpdater(it, locationProvider, gpsSignalUpdater, networkStatusProvider, it.context).apply { start() }
        }
        onDispose {
            dashboardUpdater?.stop()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        dashboardUpdater?.start()
                        // Find latest photo and update webview
                        getLatestPhotoUri(context)?.let { uri ->
                            displayLastestPhoto(uri, view)
                        }
                    }
                }
                loadUrl("file:///android_asset/index.html")
            }.also { webview ->
                webView = webview
                // 在 WebView 初始化之后，定义回调并设置 JavaScript 接口
                val onImageSavedCallback: (Uri) -> Unit = { uri ->
                    displayLastestPhoto(uri, webview)
                }
                webview.addJavascriptInterface(WebAppInterfaceImpl(webview.context, selectImageLauncher, locationProvider, imageCapture, onImageSavedCallback, scope), "AndroidNative")
            }
        }
    )
}

private fun getLatestPhotoUri(context: Context): Uri? {
    val collection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

private fun displayLastestPhoto(uri: Uri, webview: WebView?) {
    // 我们需要在主线程上运行 evaluateJavascript
    // webView.post 能确保这一点
    val script = "updateLatestPhoto('$uri')"
    webview?.post {
        webview.evaluateJavascript(script, null)
    }
}
