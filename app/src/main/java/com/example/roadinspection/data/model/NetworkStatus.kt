package com.example.roadinspection.data.model

/**
 * 网络状态的数据模型
 * @param networkType 网络类型 (e.g., "WIFI", "5G", "4G", "N/A")
 * @param signalLevel 信号强度 [0-4]
 */
data class NetworkStatus(
    val networkType: String = "N/A",
    val signalLevel: Int = 0
)
