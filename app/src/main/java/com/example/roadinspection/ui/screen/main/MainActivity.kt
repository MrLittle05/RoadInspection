package com.example.roadinspection.ui.screen.main

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import com.example.roadinspection.data.source.remote.TokenAuthenticator
import com.example.roadinspection.di.NetworkModule
import com.example.roadinspection.ui.bridge.AndroidNativeApiImpl
import com.example.roadinspection.ui.theme.GreetingCardTheme
import com.example.roadinspection.utils.invokeJsCallback
import com.example.roadinspection.worker.WorkManagerConfig
import com.google.gson.Gson
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * 主程序入口 Activity.
 * 承载 React 前端的核心 WebView 容器。
 */
class MainActivity : ComponentActivity() {

    // 仅申请基础权限（相册/存储），相机和定位权限在 InspectionActivity 中按需申请
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 全局初始化
        // 初始化 Token 管理器
        TokenManager.init(applicationContext)
        // 初始化网络模块 (注入 Application Context 给 Authenticator 用作 Toast 提示)
        NetworkModule.init(applicationContext)


        // 2. 基础权限申请逻辑
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

        // 开启 Worker
        WorkManagerConfig.scheduleUpload(applicationContext)
        WorkManagerConfig.scheduleDailyCleanup(applicationContext)
    }
}

/**
 * 主界面 Composable.
 * 包含 WebView 和 EventBus 事件监听逻辑.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MainWebViewScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 初始化数据库仓库 (用于 fetchTasks 等 JS 调用)
    val database = remember { AppDatabase.getDatabase(context) }
    val repository = remember { InspectionRepository(database.inspectionDao()) }

    // 持有 WebView 的引用，以便在 EventBus 回调中调用 JS
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 图片选择器 (用于 React 端的相册调用)
    val selectImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // 这里的处理逻辑视具体业务而定，通常会通过 JS Bridge 回传给前端
        // 例如: webViewRef?.invokeJsCallback("onImageSelected", uri.toString())
    }

    // ====================================================================================
    // [核心修改] EventBus 监听逻辑
    // 监听来自 TokenAuthenticator 的强制登出事件 (403)
    // ====================================================================================
    DisposableEffect(Unit) {
        val eventBus = EventBus.getDefault()

        // 定义订阅者对象
        val subscriber = object {
            /**
             * 接收强制登出事件
             * 触发时机：TokenAuthenticator 捕获到后端返回 403 且无法刷新 Token 时
             */
            @Subscribe(threadMode = ThreadMode.MAIN)
            fun onAuthEvent(event: TokenAuthenticator.AuthEvent.ForceLogout) {
                Log.w("MainActivity", "⚠️ 收到强制登出事件: ${event.message}")

                // 1. 构造标准 JSON 响应
                val jsonParams = Gson().toJson(mapOf(
                    "code" to 403,
                    "msg" to event.message,
                    "data" to null
                ))

                // 2. 调用 React 前端的 window.onLogoutComplete 方法
                // 前端应在此方法中清理路由、User Context 并跳转到登录页
                webViewRef?.invokeJsCallback("onLogoutComplete", jsonParams)
            }
        }

        // 注册 EventBus
        if (!eventBus.isRegistered(subscriber)) {
            eventBus.register(subscriber)
        }

        // 当 Composable 销毁时反注册 (防止内存泄漏)
        onDispose {
            if (eventBus.isRegistered(subscriber)) {
                eventBus.unregister(subscriber)
            }
        }
    }

    // ====================================================================================
    // WebView UI
    // ====================================================================================
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                // WebView 基础配置
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = true
                settings.allowUniversalAccessFromFileURLs = true

                // 注入 JS Bridge
                // 注意：inspectionManager 传 null，因为主页通常不涉及具体的巡检逻辑
                val api = AndroidNativeApiImpl(
                    context = ctx,
                    webView = this,
                    scope = scope,
                    repository = repository,
                    selectImageLauncher = selectImageLauncher,
                    inspectionManager = null
                )
                addJavascriptInterface(api, "AndroidNative")

                // 加载前端入口
                loadUrl("file:///android_asset/index.html")
            }
        },
        update = { webView ->
            // [关键] 更新 WebView 引用，供 EventBus 回调使用
            webViewRef = webView
        }
    )
}