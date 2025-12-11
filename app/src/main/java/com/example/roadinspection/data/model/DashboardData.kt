package com.example.roadinspection.data.model

/**
 * 用于高频更新的仪表盘实时数据模型。
 * 包含了定位、速度、网络、巡检状态等信息。
 *
 * @property timeDiff 与系统时间相差的毫秒数。
 * @property lat 纬度。
 * @property lng 经度。
 * @property netType 网络类型 (例如 "5G", "WIFI", "N/A")。
 * @property netLevel 网络信号强度，范围 [0 - 4]。
 * @property gpsLevel GPS信号强度，范围 [0 - 4]。
// * @property totalDistance 本次巡检累计移动距离 (单位：米)。
// * @property isInspecting 当前是否处于巡检记录状态。
 */
data class DashboardData(
    val timeDiff: Long = 0L,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val netType: String = "N/A",
    val netLevel: Int = 0,
    val gpsLevel: Int = 0,
//    val totalDistance: Double = 0.0,
//    val isInspecting: Boolean = false
)
