package com.example.roadinspection.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.roadinspection.BuildConfig
import com.example.roadinspection.data.model.VersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    private const val TAG = "UpdateManager"

    // APK 下载后的文件名
    private const val APK_NAME = "update_pkg.apk"

    /**
     * 1. 检查更新 (在 UI 层的 ViewModel 或 Activity 中调用)
     */
    suspend fun checkAndDownload(context: Context, serverVersion: VersionInfo) {
        if (serverVersion.versionCode > BuildConfig.VERSION_CODE) {
            Log.i(TAG, "发现新版本: ${serverVersion.versionName}")

            // 这里可以先弹窗提示用户 "发现新版本"，用户点击 "更新" 后再执行下面的下载
            // 为了演示简便，这里直接开始下载
            downloadApk(context, serverVersion.downloadUrl)
        } else {
            Log.i(TAG, "当前已是最新版本")
        }
    }

    /**
     * 2. 下载 APK
     * 使用 HttpURLConnection (或者你可以换成 OkHttp/Retrofit)
     * 下载到 context.getExternalFilesDir(null) 目录，不需要申请存储权限
     */
    private suspend fun downloadApk(context: Context, urlString: String) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始下载 APK: $urlString")
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            val file = File(context.getExternalFilesDir(null), APK_NAME)
            if (file.exists()) file.delete()

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(file)

            val buffer = ByteArray(1024)
            var len: Int
            var totalRead = 0L

            // 这里可以添加进度回调
            while (inputStream.read(buffer).also { len = it } != -1) {
                outputStream.write(buffer, 0, len)
                totalRead += len
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            Log.i(TAG, "下载完成: ${file.absolutePath}")

            // 下载完成后，切回主线程准备安装
            withContext(Dispatchers.Main) {
                prepareInstall(context, file)
            }

        } catch (e: Exception) {
            Log.e(TAG, "下载失败", e)
        }
    }

    /**
     * 3. 准备安装
     * 处理 Android 8.0+ 的权限检查
     */
    private fun prepareInstall(context: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                // 如果没有“安装未知应用”的权限，跳转到设置页去开启
                Log.w(TAG, "没有安装权限，跳转设置页")
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }
        // 有权限，直接安装
        installApk(context, apkFile)
    }

    /**
     * 4. 调起系统安装器
     */
    private fun installApk(context: Context, file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // 获取 FileProvider URI (必须与 AndroidManifest 中的 authorities 一致)
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            // 赋予临时的读写权限
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")

            context.startActivity(intent)

            // 可选：安装进程启动后，结束当前 App
            // android.os.Process.killProcess(android.os.Process.myPid())

        } catch (e: Exception) {
            Log.e(TAG, "调起安装失败", e)
        }
    }
}