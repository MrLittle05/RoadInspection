package com.example.roadinspection.ui.bridge

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.example.roadinspection.BuildConfig
import com.example.roadinspection.data.model.ApiResponse
import com.example.roadinspection.data.repository.InspectionRepository
import com.example.roadinspection.domain.inspection.InspectionManager
import com.example.roadinspection.ui.screen.inspection.InspectionActivity
import com.example.roadinspection.utils.invokeJsCallback
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * WebAppInterface 接口的具体实现类。
 *
 * **架构说明：**
 * 该类作为 WebView 与 Android 原生能力的桥梁。
 * 所有方法均由 JavaScript 在后台线程调用，因此涉及 UI 操作时必须切换至主线程。
 *
 * **生命周期管理：**
 * [scope] 必须注入 Activity 或 Fragment 的 `lifecycleScope`。
 * 这保证了当原生页面销毁时，所有未完成的数据库查询、网络请求和 Flow 收集都会自动取消，
 * 防止内存泄漏和 WebView 销毁后的回调崩溃。
 */
class AndroidNativeApiImpl(
    private val inspectionManager: InspectionManager? = null,
    private val repository: InspectionRepository,
    private val context: Context,
    private val selectImageLauncher: ActivityResultLauncher<String>? = null,
    private val onSetZoom: ((Float) -> Unit)? = null,
    webView: WebView,
    private val scope: CoroutineScope
) : AndroidNativeApi {

    private val webViewRef = WeakReference(webView)
    private val gson = Gson()
    private val TAG = "AndroidNativeApi"

    // 用于追踪当前的 fetchTasks 任务，防止前端频繁调用导致重复订阅
    private var currentTasksJob: Job? = null
    // 用于追踪当前的 fetchRecords 任务
    private var currentRecordsJob: Job? = null

    @JavascriptInterface
    override fun getApiBaseUrl(): String {
        return BuildConfig.SERVER_URL
    }

    @JavascriptInterface
    override fun startInspectionActivity(url: String) {
        Log.d(TAG, "JS请求跳转: $url")
        // 防止 JS 传入 ./inspection.html 或 /inspection.html 导致路径拼接错误
        val cleanUrl = url.removePrefix("./").removePrefix("/")

        val intent = Intent(context, InspectionActivity::class.java).apply {
            // 拼接完整 file 协议路径
            putExtra("TARGET_URL", "file:///android_asset/$cleanUrl")
        }
        context.startActivity(intent)
    }

    @JavascriptInterface
    override fun startInspection(title: String?, currentUserId: String) {
        inspectionManager?.startInspection(title, currentUserId)
            ?: showToast("错误：当前页面不支持开始巡检")
    }

    @JavascriptInterface
    override fun stopInspection() {
        inspectionManager?.stopInspection()
    }

    @JavascriptInterface
    override fun manualCapture() {
        val result = inspectionManager?.manualCapture() ?: false
        if (!result && inspectionManager != null) showToast("当前无巡检任务，拍摄失败")
    }

    @JavascriptInterface
    override fun openGallery(type: String) {
        when (type) {
            "all" -> {
                selectImageLauncher?.launch("image/*")
            }

            "route" -> {
                showToast("该功能暂未实现")
            }

            else -> {
                showToast("未知的相册类型: $type")
            }
        }
    }

    @JavascriptInterface
    override fun setZoom(value: Float) {
        onSetZoom?.invoke(value)
    }

    @JavascriptInterface
    override fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    @JavascriptInterface
    override fun fetchTasks(userId: String) {
        // 1. 自动取消上一次正在进行的订阅 (如果有)，防止重复回调
        currentTasksJob?.cancel()

        // 2. 启动新的协程任务
        currentTasksJob = scope.launch(Dispatchers.IO) {
            try {
                // ---------------------------------------------------------
                // 阶段 A: 建立响应式数据流 (Reactive Stream)
                // ---------------------------------------------------------
                // 启动一个子协程专门负责监听数据库变化
                launch {
                    repository.getAllTasks()
                        // 仅当数据内容发生实质变化时才通知，避免频繁刷新 UI
                        // 注意：InspectionTask 需要实现 equals/hashCode
                        // .distinctUntilChanged()
                        .catch { e ->
                            // 捕获流内部的异常，防止协程崩溃
                            handleException("fetchTasksFlow", "onTasksReceived", e as Exception)
                        }
                        .collect { tasks ->
                            // 序列化并回调前端
                            val response = ApiResponse.success(tasks)
                            val jsonResult = gson.toJson(response)
                            webViewRef.get()?.invokeJsCallback("onTasksReceived", jsonResult)
                        }
                }

                // ---------------------------------------------------------
                // 阶段 B: 触发后台网络同步 (Fire-and-Forget)
                // ---------------------------------------------------------
                // 这是一个挂起函数，但它发生异常不会打断上面的 Flow (因为我们在不同的子协程或 try-catch 块中)
                // 如果同步成功，Room 数据更新，上面的 collect 会自动收到新数据。
                repository.syncTasksFromNetwork(userId)

            } catch (e: Exception) {
                // 处理同步过程中可能的顶层异常
                Log.w(TAG, "fetchTasks 主流程异常: ${e.message}")
                // 注意：这里通常不需要回调前端错误，因为本地数据可能已经正常显示了
                // 仅记录日志即可，或者根据业务需求决定是否 toast 提示
            }
        }
    }

    @JavascriptInterface
    override fun fetchRecords(taskId: String) {
        currentRecordsJob?.cancel()
        currentRecordsJob = scope.launch(Dispatchers.IO) {
            try {
                // A: 监听本地数据
                launch {
                    repository.getRecordsByTask(taskId)
                        .catch { e -> handleException("fetchRecordsFlow", "onRecordsReceived", e as Exception) }
                        .collect { records ->
                            val response = ApiResponse.success(records)
                            webViewRef.get()?.invokeJsCallback("onRecordsReceived", gson.toJson(response))
                        }
                }

                // B: 触发网络同步 (使用 UUID 智能合并)
                repository.syncRecordsFromNetwork(taskId)

            } catch (e: Exception) {
                Log.w(TAG, "fetchRecords 流程异常: ${e.message}")
            }
        }
    }

    /**
     * 统一异常处理辅助方法。
     * 负责：记录日志 -> UI提示 -> 回调前端错误状态。
     *
     * @param methodTag 发生错误的方法名（用于日志）
     * @param jsCallbackName 前端接收的回调函数名
     * @param e 异常对象
     */
    private suspend fun handleException(methodTag: String, jsCallbackName: String, e: Exception) {
        // Step 1: 记录详细堆栈日志 (供开发排查)
        Log.e(TAG, "$methodTag error: ${e.message}", e)

        // Step 2: UI 线程弹窗提示 (供用户感知)
        // 只有当 Context 有效时才弹窗
        withContext(Dispatchers.Main) {
            val safeContext = webViewRef.get()?.context ?: context
            Toast.makeText(safeContext, "数据加载异常: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        // Step 3: 回调前端，传递错误信息 (供前端降级处理)
        // 返回 code=500, data=空列表(防止前端 map 报错)
        val errorResponse = ApiResponse.error<List<Any>>(
            msg = "Internal Error: ${e.message}",
            data = emptyList()
        )
        val errorJson = gson.toJson(errorResponse)

        webViewRef.get()?.invokeJsCallback(jsCallbackName, errorJson)
    }
}

