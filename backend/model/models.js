/**
 * @module models
 * @description 定义 MongoDB 数据模型 (Mongoose Schemas)
 */

import { Schema, model } from "mongoose";

// ============================================================
// 1. 任务模型 (Task)
// ============================================================
/**
 * 巡检任务 Schema
 * 记录一次完整的巡检活动，包含开始/结束状态
 */
const taskSchema = new Schema({
  // 业务主键：由 Android 端生成的 UUID，用于唯一标识任务
  taskId: { type: String, required: true, unique: true, index: true },

  title: String, // 任务标题/描述
  inspectorId: String, // 巡检员 ID (可以是工号或用户名)
  startTime: Number, // 开始时间戳 (Unix Timestamp)
  endTime: Number, // 结束时间戳

  // 任务状态标记：false=进行中, true=已完成
  isFinished: { type: Boolean, default: false },

  // 数据库元数据：记录记录插入服务器的时间
  createdAt: { type: Date, default: Date.now },
});

// ============================================================
// 2. 记录模型 (Record)
// ============================================================
/**
 * 巡检记录 Schema
 * 对应每一个具体的病害点，包含照片 URL 和地理位置
 */
const recordSchema = new Schema({
  // 外键关联：指向所属的 Task
  taskId: { type: String, required: true, index: true },

  serverUrl: String, // OSS 图片地址
  captureTime: Number, // 拍摄时间戳
  address: String, // 逆地理编码后的地址描述 (如：上海市xx路)

  /**
   * 核心地理位置字段 (GeoJSON 标准格式)
   * 用于支持 MongoDB 的空间查询 (如 $near, $geoWithin)
   * @warning 注意 coordinates 顺序是 [经度, 纬度]，与 Google Maps/高德 API 常用的 (lat, lng) 相反！
   */
  location: {
    type: { type: String, enum: ["Point"], default: "Point" },
    coordinates: { type: [Number], required: true }, // [Lng, Lat]
  },

  // 保留原始经纬度字段，作为备份数据便于查错
  rawLat: Number,
  rawLng: Number,
});

// 创建 2dsphere 空间索引
// 作用：开启地理空间查询能力，例如 "查找附近 5km 内的所有病害"
recordSchema.index({ location: "2dsphere" });

const Task = model("Task", taskSchema);
const Record = model("Record", recordSchema);

export { Task, Record };
