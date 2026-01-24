package com.example.roadinspection.data.source.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 巡检任务实体类 (Parent Table)。
 *
 * 代表一次完整的巡检活动（从“开始巡检”到“结束巡检”）。
 * 作为 [InspectionRecord] 的父表，用于聚合和管理具体的巡检记录。
 */
@Entity(tableName = "inspection_tasks")
data class InspectionTask(
    /**
     * 任务全局唯一标识符 (UUID)。
     *
     * 使用 UUID 字符串而非自增 ID，旨在保证分布式环境下的全局唯一性，
     * 避免多端同步时发生主键冲突，方便前后端数据统一。
     */
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String = UUID.randomUUID().toString(),

    /**
     * 任务标题。
     * 例如："2023-12-30 中山路日常巡检"
     */
    @ColumnInfo(name = "title")
    val title: String,

    /** 任务开始时间戳 (毫秒) */
    @ColumnInfo(name = "start_time")
    val startTime: Long = System.currentTimeMillis(),

    /**
     * 任务结束时间戳 (毫秒)。
     * 任务进行中时为 null。
     */
    @ColumnInfo(name = "end_time")
    val endTime: Long? = null,

    /** 巡检员 ID (预留字段) */
    @ColumnInfo(name = "inspector_id")
    val inspectorId: String = "user_default",

    /**
     * 本地完成状态标记。
     * - false: 进行中
     * - true: 已结束
     */
    @ColumnInfo(name = "is_finished")
    val isFinished: Boolean = false,

    /**
     * 任务本身的同步状态
     * 0 = 本地新建，尚未同步 (Local Only) -> 需要调 /api/task/create
     * 1 = 已同步，但仍在进行 (Synced)      -> 服务器已有此任务
     * 2 = 已同步且已结束 (Finalized)   -> 服务器已更新 end_time
    **/
    @ColumnInfo(name = "sync_state")
    val syncState: Int = 0
)