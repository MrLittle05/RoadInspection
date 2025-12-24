package com.example.roadinspection.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.roadinspection.R

/**
 * 伴随式保活服务。
 * 只要它启动，整个 App 进程就会提升为前台优先级，
 * 从而让原本的 LocationProvider 在后台也能持续工作。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("道路巡检中")
            .setContentText("正在后台记录轨迹和里程...")
            .setSmallIcon(R.mipmap.ic_launcher) // 如果报错，请换成你的实际图标资源
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // 启动前台，让系统认为 APP 正在活跃
        startForeground(1001, notification)

        // START_STICKY 表示如果服务被意外杀死，系统会尝试重启它
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "巡检后台保活",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "inspection_keep_alive"
    }
}