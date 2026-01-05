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

    // ============ 查询操作 ============

    suspend fun getInspectionById(inspectionId: Long): Inspection?

    // ============ 插入/删除 单个巡检点 (UI 或 Manager 调用) ============

    /**
     * 新增一个巡检点。
     * 这是一个数据库 IO 操作，因此被声明为 suspend 函数。
     * @param point 要添加的巡检点对象
     */
    suspend fun addInspectionPoint(point: InspectionPoint)

    /**
     * 删除一个巡检点。
     * 这是一个数据库 IO 操作，因此被声明为 suspend 函数。
     * @param point 要删除的巡检点对象
     */
    suspend fun deleteInspectionPoint(point: InspectionPoint)

    // ============ 巡检业务操作 (InspectionManager 调用) ============

    // 1. 开始巡检 (返回 inspectionId 用于后续关联)
    suspend fun startInspection(startTime: Date): Long

    // 2. 结束巡检
    suspend fun endInspection(inspectionId: Long, endTime: Date)

    /**
     * 修改后的保存方法：
     * 不再负责存文件 (假设 CameraHelper 已经存好了)，
     * 只负责将“文件路径”和“地理位置”写入数据库。
     *
     * @param inspectionId 当前巡检的 ID
     * @param photoPath 图片在手机里的绝对路径 (或 Uri.toString())
     * @param location 地理坐标
     * @param address 中文地址
     */
    suspend fun saveRecord(
        inspectionId: Long,
        photoPath: String, // 👈 变动点：这里只收路径，不收 Bitmap
        location: android.location.Location?,
        address: String
    )

    // ============ 后台同步操作 (WorkManager 调用)  ============

    // 获取所有需要上传的点 (status != SYNCED)
    suspend fun getPendingPoints(): List<InspectionPoint>

    // 更新上传状态 (例如：上传成功后，更新 status 和 serverUrl)
    suspend fun updatePointStatus(pointId: Long, status: Int, serverUrl: String? = null)

    // ============ 清理操作 ============

    suspend fun deleteInspection(inspection: Inspection)

    fun getUploadCountFlow(): Flow<Int>
}
