package com.example.roadinspection.data.model

/**
 * 高频仪表盘数据模型
 * 仅包含随位置更新频繁变化的数值。
 *
 * @property timeDiff 与系统时间相差的毫秒数。
 * @property lat 纬度。
 * @property lng 经度。
 * @property totalDistance 本次巡检累计移动距离 (单位：米)。
 */
data class HighFrequencyData(
    val timeDiff: Long = 0L,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val totalDistance: Double = 0.0
)