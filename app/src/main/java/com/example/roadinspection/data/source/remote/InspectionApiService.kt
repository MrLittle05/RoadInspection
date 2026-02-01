package com.example.roadinspection.data.source.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// -------------------------------------------------------------------------
// Region: 数据传输对象 (DTOs) - 响应模型
// -------------------------------------------------------------------------

/**
 * 通用 API 响应包装类。
 * 对应后端文档中的标准 JSON 结构。
 *
 * @param T 具体的数据类型 (data 字段的类型)
 */
data class ApiResponse<T>(
    val code: Int,      // e.g., 200, 500 [cite: 29]
    val message: String?, // e.g., "任务创建成功" [cite: 42]
    val data: T?        // 具体业务数据 (仅 /api/oss/sts 等接口有此字段)
) {
    /** 判断请求是否业务成功 */
    val isSuccess: Boolean
        get() = code == 200
}

/**
 * 阿里云 STS 临时凭证数据。
 * 对应 /api/oss/sts 接口的 data 字段 [cite: 29]。
 */
data class StsCredentials(
    val accessKeyId: String,
    val accessKeySecret: String,
    val stsToken: String,
    val region: String, // e.g., "oss-cn-shanghai"
    val bucket: String  // e.g., "road-inspection-dev"
)

data class TaskDto(
    val taskId: String,
    val title: String,
    val inspectorId: String,
    val startTime: Long,
    val endTime: Long?,
    val isFinished: Boolean
)

data class RecordDto(
    val recordId: String,
    val taskId: String,
    val serverUrl: String,
    val captureTime: Long,
    val address: String,
    val rawLat: Double,
    val rawLng: Double
)

// -------------------------------------------------------------------------
// Region: 数据传输对象 (DTOs) - 请求 Body 模型
// -------------------------------------------------------------------------

/**
 * 创建/同步任务请求体。
 * 对应 /api/task/create 接口参数 [cite: 39]。
 */
data class CreateTaskReq(
    val taskId: String,
    val title: String,
    val inspectorId: String,
    val startTime: Long,

    /** * 任务结束时间 (可选)。
     * **新特性：** 如果任务在离线期间已经结束，同步时直接带上此字段，
     * 后端会将任务标记为已完成 (isFinished=true)。
     */
    val endTime: Long? = null
)

/**
 * 结束任务请求体。
 * 对应 /api/task/finish 接口参数 [cite: 48]。
 */
data class FinishTaskReq(
    val taskId: String,
    val endTime: Long
)

/**
 * 提交巡检记录请求体。
 * 对应 /api/record/submit 接口参数 。
 */
data class SubmitRecordReq(
    val recordId: String,
    val taskId: String,
    val serverUrl: String, // 图片在 OSS 的完整 URL
    val latitude: Double,
    val longitude: Double,
    val address: String?,  // 逆地理编码地址 (可选)
    val captureTime: Long,
    val iri: Double?,
    val pavementDistress: String?,
)

// -------------------------------------------------------------------------
// Region: Retrofit 接口定义
// -------------------------------------------------------------------------

/**
 * 道路巡检后端 API 服务接口。
 * Base URL: http://<服务器IP>:3000 [cite: 15]
 */
interface InspectionApiService {

    /**
     * 获取 OSS 临时上传凭证 (STS Token)。
     * [cite: 23, 24]
     */
    @GET("/api/oss/sts")
    suspend fun getStsToken(): ApiResponse<StsCredentials>

    /**
     * 创建或同步巡检任务。
     * 具有幂等性，支持重复调用。
     * [cite: 35, 36, 37]
     */
    @POST("/api/task/create")
    suspend fun createTask(@Body req: CreateTaskReq): ApiResponse<Unit>

    /**
     * 结束巡检任务。
     * [cite: 44, 45]
     */
    @POST("/api/task/finish")
    suspend fun finishTask(@Body req: FinishTaskReq): ApiResponse<Unit>

    /**
     * 提交单条病害记录 (元数据)。
     * 必须在图片上传 OSS 成功后调用。
     * [cite: 57, 58, 59]
     */
    @POST("/api/record/submit")
    suspend fun submitRecord(@Body req: SubmitRecordReq): ApiResponse<Unit>

    /**
     * 获取任务列表。
     * [cite: 63]
     */
    @GET("/api/task/list")
    suspend fun fetchTasks(@Query("userId") userId: String): ApiResponse<List<TaskDto>>

    /**
     * 获取指定任务的照片记录。
     * [cite: 64]
     */
    @GET("/api/record/list")
    suspend fun fetchRecords(@Query("taskId") taskId: String): ApiResponse<List<RecordDto>>
}