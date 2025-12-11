package com.example.roadinspection.ui.screen.main

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.example.roadinspection.util.DashboardUpdater
import com.example.roadinspection.util.GPSSignalUpdater

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

        locationProvider = LocationProvider(this)
        networkStatusProvider = NetworkStatusProvider(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gpsSignalUpdater = GPSSignalUpdater(this)
        }

        // 1. 启动时立即请求所有必要权限
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO
            )
        )

        setContent {
            GreetingCardTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview()
                    // 确保只在 gpsSignalUpdater 初始化后才调用
                    if (::gpsSignalUpdater.isInitialized) {
                        WebViewScreen(locationProvider, networkStatusProvider, gpsSignalUpdater)
                    } else {
                        // 对于不支持 GnssStatus 的旧设备，可以提供一个不含 gpsUpdater 的版本
                        // 为了简化，这里我们暂时只在支持的设备上显示 WebView
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview() {
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
                val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
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
    gpsSignalUpdater: GPSSignalUpdater
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var dashboardUpdater: DashboardUpdater? by remember { mutableStateOf(null) }

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
            dashboardUpdater = DashboardUpdater(it, locationProvider, gpsSignalUpdater, networkStatusProvider).apply { start() }
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
                addJavascriptInterface(WebAppInterfaceImpl(context, selectImageLauncher), "AndroidNative")
                webViewClient = object: WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        dashboardUpdater?.start()
                    }
                }
                loadUrl("file:///android_asset/camera.html")
            }.also {
                webView = it
            }
        }
    )
}
