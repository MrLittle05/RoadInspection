package com.example.roadinspection.data.source.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 巡检记录实体类 (Child Table)。
 *
 * 记录单次拍摄的照片、位置及上传状态。
 *
 * **外键约束：**
 * 关联 [InspectionTask] 的 `task_id`。配置了 `CASCADE` 级联删除，
 * 当父任务被删除时，该任务下所有的照片记录将自动被清除。
 */
@Entity(
    tableName = "inspection_records",
    foreignKeys = [
        ForeignKey(
            entity = InspectionTask::class,
            parentColumns = ["task_id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // 为 task_id 和 sync_status 建立索引，加速关联查询和同步任务查询
    indices = [Index(value = ["task_id"]), Index(value = ["sync_status"])]
)
data class InspectionRecord(
    /**
     * 使用 UUID 作为主键。
     * 只有在本地创建时生成一次，上传给后端时带上它，后端返回时也带上它。
     */
    @PrimaryKey
    @ColumnInfo(name = "record_id")
    val recordId: String = UUID.randomUUID().toString(),

    /** 关联的任务 ID (外键) */
    @ColumnInfo(name = "task_id")
    val taskId: String,

    /**
     * 图片在本地文件系统中的绝对路径。
     * 例如: `/storage/emulated/0/Android/data/.../files/Pictures/IMG_xxx.webp`
     */
    @ColumnInfo(name = "local_path")
    val localPath: String,

    /**
     * 图片上传至 OSS 后返回的远程 URL。
     * 初始为 null，上传成功后回填。
     */
    @ColumnInfo(name = "server_url")
    val serverUrl: String? = null,

    /**
     * 数据同步状态机。
     *
     * - **0 (PENDING)**: 待上传。照片仅保存在本地。
     * - **1 (IMAGE_UPLOADED)**: 图片已上传至 OSS，但业务数据尚未提交给后端。
     * - **2 (SYNCED)**: 已完成。图片和业务数据均已成功同步至服务器。
     */
    @ColumnInfo(name = "sync_status")
    val syncStatus: Int = 0,

    /** 拍摄时间戳 (毫秒) */
    @ColumnInfo(name = "capture_time")
    val captureTime: Long,

    /** 拍摄时的纬度 (WGS84 或 GCJ02，视具体实现而定) */
    val latitude: Double,

    /** 拍摄时的经度 */
    val longitude: Double,

    /** 逆地理编码后的地址字符串 (例如 "xx路xx号") */
    val address: String? = null
)