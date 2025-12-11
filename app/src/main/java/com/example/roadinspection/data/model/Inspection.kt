package com.example.roadinspection.data.model

import java.util.Date

/**
 * 代表一次完整的巡检记录
 * @param id 主键ID，自动生成
 * @param startTime 开始时间
 * @param endTime 结束时间，如果还未结束则为 null
 * @param name 路线名称 (可选)
 */
data class Inspection(
    val id: Long = 0,
    val startTime: Date,
    val endTime: Date? = null,
    val name: String? = null
)