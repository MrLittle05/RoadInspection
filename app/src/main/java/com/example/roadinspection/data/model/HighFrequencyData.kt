package com.example.roadinspection.data.model

/**
 * 用于高频更新的仪表盘实时数据模型。
 * 包含了定位、速度、网络、巡检状态等信息。
 *
 * @property timeDiff 与系统时间相差的毫秒数。
 * @property lat 纬度。
 * @property lng 经度。
 */
data class HighFrequencyData(
    val timeDiff: Long = 0L,
    val lat: Double = 0.0,
    val lng: Double = 0.0,

)
