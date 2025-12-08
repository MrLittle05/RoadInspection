package com.example.roadinspection

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.example.roadinspection.ui.theme.GreetingCardTheme

class MainActivity : ComponentActivity() {

    // 权限请求启动器
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 这里可以处理权限被拒绝的情况，暂且假设用户都会同意
        Log.d("Permissions", "Permissions granted: $permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 启动时立即请求所有必要权限
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO // 如果要录像的话
            )
        )

        setContent {
            GreetingCardTheme {
                // 2. 使用 Box 进行图层叠加
                Box(modifier = Modifier.fillMaxSize()) {
                    // 底层：相机预览
                    CameraPreview()
                    
                    // 顶层：WebView UI
                    WebViewScreen()
                }
            }
        }
    }
}

/**
 * 相机预览组件 (底层)
 * 支持双指缩放
 */
@Composable
fun CameraPreview() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // 保持比例填充，可能会裁剪边缘，但不会变形
                scaleType = PreviewView.ScaleType.FILL_CENTER 
            }
        },
        update = { previewView ->
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            preview.setSurfaceProvider(previewView.surfaceProvider)

            try {
                // 解绑所有用例
                cameraProvider.unbindAll()
                
                // 绑定生命周期
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview
                )

                // --- 实现双指缩放逻辑 ---
                val scaleGestureDetector = ScaleGestureDetector(context,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            val zoomState = camera.cameraInfo.zoomState.value
                            val currentZoomRatio = zoomState?.zoomRatio ?: 1f
                            val delta = detector.scaleFactor
                            camera.cameraControl.setZoomRatio(currentZoomRatio * delta)
                            return true
                        }
                    }
                )

                // 将触摸事件传递给 ScaleGestureDetector
                // 注意：由于 WebView 在上层，这里的触摸事件可能会被 WebView 拦截
                // 如果 WebView 区域也是全屏的，需要前端配合处理事件穿透或在 WebView 上监听
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
 * WebView UI 组件 (顶层)
 * 背景透明，只负责显示悬浮按钮和信息
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen() {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                // 1. 核心设置：背景透明
                setBackgroundColor(Color.TRANSPARENT)
                
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true

                // 注入接口
                addJavascriptInterface(WebAppInterface(context), "AndroidNative")

                webViewClient = WebViewClient()

                // 加载本地页面
                loadUrl("file:///android_asset/camera.html")
            }
        }
    )
}