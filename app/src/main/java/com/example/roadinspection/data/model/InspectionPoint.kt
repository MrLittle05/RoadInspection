package com.example.roadinspection.data.model;

import java.util.Date

/**
 * 代表巡检过程中的一个采集点（例如一张照片）
 * @param id 主键ID，自动生成
 * @param inspectionId 外键，关联到具体的巡检记录
 * @param imageUri 照片的存储路径 (String)
 * @param latitude 纬度
 * @param longitude 经度
 * @param timestamp 采集时间
 */
data class InspectionPoint(
        val id: Long = 0,
        val inspectionId: Long,
        val imageUri: String,
        val latitude: Double,
        val longitude: Double,
        val timestamp: Date
)
