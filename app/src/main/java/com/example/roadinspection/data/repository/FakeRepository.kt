package com.example.roadinspection.data.repository

// 这是一个“替身”仓库，只为了骗过编译器，让你能运行 App
// 等队友写好了真正的数据库，再换成真的
import com.example.roadinspection.data.repository.RoadInspectionRepository
import com.example.roadinspection.data.model.Inspection
import com.example.roadinspection.data.model.InspectionPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Date
import android.location.Location
import android.util.Log

class FakeRepository : RoadInspectionRepository {
    // 假装启动了，返回一个固定的 ID 10086
    override suspend fun startInspection(startTime: Date): Long {
        Log.d("FakeRepo", "假装在数据库创建了新任务，ID: 10086")
        return 10086L
    }

    // 假装保存了
    override suspend fun saveRecord(
        inspectionId: Long,
        photoPath: String,
        location: Location?,
        address: String
    ) {
        Log.d("FakeRepo", "假装保存了数据 -> 任务ID:$inspectionId, 地址:$address, 图片:$photoPath")
    }

    override suspend fun endInspection(inspectionId: Long, endTime: Date) {
        Log.d("FakeRepo", "假装结束了任务: $inspectionId")
    }

    // 下面这些暂时用不到，给个空实现或默认值即可
    override fun getInspections(): Flow<List<Inspection>> = flowOf(emptyList())
    override fun getInspectionPoints(inspectionId: Long): Flow<List<InspectionPoint>> = flowOf(emptyList())
//    override suspend fun getInspectionById(inspectionId: Long): Inspection? = null
//    override suspend fun addInspectionPoint(point: InspectionPoint) {}
//    override suspend fun deleteInspectionPoint(point: InspectionPoint) {}
    override suspend fun deleteInspection(inspection: Inspection) {}
    override suspend fun getPendingPoints(): List<InspectionPoint> = emptyList()
    override suspend fun updatePointStatus(pointId: Long, status: Int, serverUrl: String?) {}
}