package com.example.roadinspection.data.repository

import com.example.roadinspection.data.model.Inspection
import com.example.roadinspection.data.model.InspectionPoint
import kotlinx.coroutines.flow.Flow
import java.util.Date

// --- 仓库接口 ---

interface RoadInspectionRepository {

    /**
     * 获取所有巡检记录的流。
     * 当数据库发生变化时，Flow 会发出新的列表。
     */
    fun getInspections(): Flow<List<Inspection>>

    /**
     * 获取指定巡检记录的所有采集点。
     * @param inspectionId 巡检记录的 ID
     */
    fun getInspectionPoints(inspectionId: Long): Flow<List<InspectionPoint>>

    /**
     * 根据ID获取单个巡检记录。
     * @param inspectionId 巡检记录的 ID
     * @return 如果找到则返回 Inspection 对象，否则返回 null
     */
    suspend fun getInspectionById(inspectionId: Long): Inspection?

    /**
     * 开始一次新的巡检，并在数据库中创建一条新记录。
     * @param startTime 开始时间
     * @return 返回新创建的巡检记录的 ID
     */
    suspend fun startInspection(startTime: Date): Long

    /**
     * 结束指定的巡检。
     * @param inspectionId 要结束的巡检记录的 ID
     * @param endTime 结束时间
     */
    suspend fun endInspection(inspectionId: Long, endTime: Date)

    /**
     * 添加一个巡检采集点（例如，保存一张照片和相关数据）。
     * @param point 要添加的采集点数据
     */
    suspend fun addInspectionPoint(point: InspectionPoint)

    /**
     * 删除一个指定的采集点
     * @param point 要删除的采集点
     */
    suspend fun deleteInspectionPoint(point: InspectionPoint)

    /**
     * 删除一个完整的巡检记录及其所有相关的采集点
     * @param inspection 要删除的巡检记录
     */
    suspend fun deleteInspection(inspection: Inspection)
}
