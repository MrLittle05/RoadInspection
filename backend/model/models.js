/**
 * @module models
 * @description 定义 MongoDB 数据模型 (Mongoose Schemas)
 * 包含: User(用户), Task(巡检任务), Record(病害记录)
 */

import { Schema, model } from "mongoose";

// ============================================================
// 1. 用户模型 (User)
// ============================================================
/**
 * 用户/巡检员 Schema
 * 包含认证信息、角色权限及软删除机制
 */
const userSchema = new Schema(
  {
    // 注意：MongoDB 自动生成 _id (ObjectId)

    // 用户名 (用于登录，唯一)
    username: {
      type: String,
      required: true,
      unique: true,
      trim: true, // 自动去除首尾空格
    },

    // 密码哈希值 (严禁存明文)
    // select: false 意味着默认查询 (User.find) 不会返回此字段，保障安全
    hashedPassword: {
      type: String,
      required: true,
      select: false,
    },

    // 用户角色
    role: {
      type: String,
      enum: ["admin", "inspector"],
      default: "inspector",
    },

    // 软删除字段
    // null = 有效用户; 有日期 = 已删除用户
    deletedAt: { type: Date, default: null },

    // 用于服务端比对和注销
    refreshToken: { type: String, select: false },
  },
  {
    // 自动管理 createdAt 和 updatedAt 字段
    timestamps: true,
    // 允许虚拟字段 (virtuals) 包含在 JSON 输出中
    toJSON: { virtuals: true },
    toObject: { virtuals: true },
  },
);

// 虚拟字段: 将 _id 映射为 id
// 前端拿到的数据里会有 "id": "507f1f..." 方便使用
userSchema.virtual("id").get(function () {
  return this._id.toHexString();
});

// 索引优化: 经常需要查询 "未被删除的用户" 或 "按用户名查找"
userSchema.index({ deletedAt: 1 });
userSchema.index({ username: 1 });

// ============================================================
// 2. 任务模型 (Task)
// ============================================================
/**
 * 巡检任务 Schema
 * 业务主键是 Android 生成的 UUID
 */
const taskSchema = new Schema({
  // 业务主键：由 Android 端生成的 UUID
  taskId: { type: String, required: true, unique: true, index: true },

  title: { type: String, required: true },

  // 关联字段: 存储 User 表的 _id
  // ref: 'User' 允许使用 .populate('inspectorId') 联表查询
  inspectorId: {
    type: Schema.Types.ObjectId,
    ref: "User",
    required: true,
    index: true,
  },

  startTime: { type: Number, required: true }, // Unix Timestamp
  endTime: Number,

  // 任务状态: false=进行中, true=已完成
  isFinished: { type: Boolean, default: false },

  // 记录插入时间
  createdAt: { type: Date, default: Date.now },

  // 软删除字段
  // null = 有效; 有日期 = 已删除
  deletedAt: { type: Date, default: null },
});

taskSchema.index({ deletedAt: 1 });

// ============================================================
// 3. 记录模型 (Record)
// ============================================================
/**
 * 巡检记录 Schema
 * 对应具体的病害点，包含地理位置
 */
const recordSchema = new Schema({
  recordId: { type: String, required: true, unique: true, index: true },
  // 外键关联：指向所属的 Task (注意这里是 taskId 字符串，不是 _id)
  taskId: { type: String, required: true, index: true },

  serverUrl: { type: String, required: true }, // 图片在阿里云OSS的地址
  captureTime: { type: Number, required: true },
  address: String,

  // GeoJSON 地理位置
  location: {
    type: { type: String, enum: ["Point"], default: "Point" },
    coordinates: { type: [Number], required: true }, // [Lng, Lat]
  },

  // 原始经纬度备份
  rawLat: { type: Number, required: true },
  rawLng: { type: Number, required: true },

  iri: { type: Number, default: null },

  pavementDistress: { type: String, default: null },

  // 软删除字段 (随任务级联删除)
  deletedAt: { type: Date, default: null },
});

// 创建 2dsphere 空间索引 (支持 $near, $geoWithin 查询)
recordSchema.index({ location: "2dsphere" });

recordSchema.index({ deletedAt: 1 });

// ============================================================
// 导出模型
// ============================================================
const User = model("User", userSchema);
const Task = model("Task", taskSchema);
const Record = model("Record", recordSchema);

export { Record, Task, User };
