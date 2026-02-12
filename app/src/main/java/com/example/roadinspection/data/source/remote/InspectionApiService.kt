package com.example.roadinspection.data.source.remote

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import com.example.roadinspection.data.model.ApiResponse

// -------------------------------------------------------------------------
// Region: 数据传输对象 (DTOs) - 响应模型
// -------------------------------------------------------------------------

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

data class UserDto(
    val id: String,
    val username: String,
    val role: String
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

data class UpdateProfileReq(
    val newUsername: String? = null,
    val newPassword: String? = null
)

data class LogoutReq(val refreshToken: String)

// -------------------------------------------------------------------------
// Region: Auth 相关 DTO
// -------------------------------------------------------------------------

/**
 * 刷新 Token 请求体。
 */
data class RefreshTokenReq(
    val refreshToken: String
)

/**
 * 刷新 Token 响应体。
 * 后端返回的 data 字段结构。
 */
data class RefreshTokenResp(
    val accessToken: String,
    val refreshToken: String // 通常刷新后会连同 Refresh Token 一起更新，实现"滑动过期"
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
     * 更新用户信息
     * (参考 authService.ts 中的 PATCH 逻辑)
     */
    @retrofit2.http.PATCH("/api/user/{id}")
    suspend fun updateProfile(
        @Path("id") id: String,
        @Body req: UpdateProfileReq
    ): ApiResponse<UserDto>

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

    /**
     * 刷新 Token 接口。
     * **注意：** 此接口返回值必须是 [Call] 而不是 [suspend]，
     * 因为 Authenticator 内部必须同步执行网络请求 (Synchronous)，
     * 且不能使用协程挂起，否则会阻塞 OkHttp 的线程池调度。
     */
    @POST("/api/auth/refresh")
    fun refreshToken(@Body req: RefreshTokenReq): Call<ApiResponse<RefreshTokenResp>>

    @POST("/api/auth/logout")
    suspend fun logout(@Body req: LogoutReq): ApiResponse<Unit>

    /**
     * 删除任务。
     * 即使后端执行软删除，客户端仍发送 DELETE 请求。
     * 后端响应 200 (OK) 或 404 (Not Found) 都视为删除成功。
     */
    @retrofit2.http.DELETE("/api/task/{taskId}")
    suspend fun deleteTask(@Path("taskId") taskId: String, @Query("userId") userId: String): ApiResponse<Unit>
}