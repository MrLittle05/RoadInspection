package com.example.roadinspection.data.repository

import android.graphics.Bitmap
import android.location.Location
import com.example.roadinspection.data.model.Inspection
import com.example.roadinspection.data.model.InspectionPoint
import kotlinx.coroutines.flow.Flow
import java.util.Date

interface RoadInspectionRepository {

    // ============ UI 数据流 ============

    // 获取巡检列表流
    fun getInspections(): Flow<List<Inspection>>

    // 获取某次巡检的所有点流
    fun getInspectionPoints(inspectionId: Long): Flow<List<InspectionPoint>>

    // ============ 巡检业务操作 (InspectionManager 调用) ============

    // 1. 开始巡检 (返回 inspectionId 用于后续关联)
    suspend fun startInspection(startTime: Date): Long

    // 2. 结束巡检
    suspend fun endInspection(inspectionId: Long, endTime: Date)

    /**
     * 3. 核心保存方法
     * Repository 层负责：
     * a. 将 Bitmap 压缩为 WebP 并保存到私有目录
     * b. 生成 InspectionPoint 对象
     * c. 插入数据库
     * 这样 InspectionManager 不需要关心文件 IO
     */
    suspend fun saveRecord(
        inspectionId: Long,
        bitmap: Bitmap,
        location: Location?,
        address: String
    )

    // ============ 后台同步操作 (WorkManager 调用)  ============

    // 获取所有需要上传的点 (status != SYNCED)
    suspend fun getPendingPoints(): List<InspectionPoint>

    // 更新上传状态 (例如：上传成功后，更新 status 和 serverUrl)
    suspend fun updatePointStatus(pointId: Long, status: Int, serverUrl: String? = null)

    // ============ 清理操作 ============

    suspend fun deleteInspection(inspection: Inspection)
}