package com.example.roadinspection.ui.screen.main

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.data.source.local.AppDatabase
import com.example.roadinspection.data.source.local.TokenManager
import com.example.roadinspection.ui.bridge.AndroidNativeApiImpl
import com.example.roadinspection.ui.theme.GreetingCardTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // 仅申请相册/存储权限（用于上传头像等），不申请相机和定位
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        TokenManager.init(applicationContext)

        // 基础权限申请
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())

        setContent {
            GreetingCardTheme {
                MainWebViewScreen()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MainWebViewScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 初始化数据库仓库 (用于 fetchTasks)
    val database = remember { AppDatabase.getDatabase(context) }
    val repository = remember { InspectionRepository(database.inspectionDao()) }

    // 图片选择器 (用于 React 端的相册调用)
    val selectImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // 这里的处理逻辑视具体业务而定，可以传回 JS
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = true
                settings.allowUniversalAccessFromFileURLs = true

                // 注入 Bridge
                // 注意：传入 null 作为 inspectionManager，因为主页没有巡检能力
                val api = AndroidNativeApiImpl(
                    context = ctx,
                    webView = this,
                    scope = scope,
                    repository = repository,
                    selectImageLauncher = null // 如果主页不需要传图片给 JS，也可以为 null
                )
                addJavascriptInterface(api, "AndroidNative")

                loadUrl("file:///android_asset/index.html")
            }
        }
    )
}