/**
 * @file app.js
 * @module AppEntry
 * @description 后端核心入口文件
 * 基于 Koa 2 框架构建，负责启动 HTTP 服务器、连接数据库、挂载全局中间件及路由处理。
 * * 主要职责：
 * 1. 统一的请求日志记录 (Access Log)
 * 2. MongoDB 数据库连接管理 (Connection Pool)
 * 3. 业务接口路由分发 (Routes)
 */

import Koa from "koa";
import bodyParser from "koa-bodyparser";
import Router from "koa-router";
import { connect } from "mongoose";
import { mongoUrl } from "./config/config.js";
import { Record, Task } from "./model/models.js";
import { getStsToken } from "./utils/oss_helper.js";

const app = new Koa();
const router = new Router();

// ============================================================
// 1. Global Middleware (全局中间件)
// ============================================================

/**
 * 全局请求日志中间件 (Access Logger)
 * 遵循 "3W原则" (Who, When, Result) 记录所有流入服务器的 HTTP 请求。
 * 用于生产环境的性能监控和故障排查。
 */
app.use(async (ctx, next) => {
  const start = Date.now();
  try {
    await next(); // 放行请求进入下游路由
    const ms = Date.now() - start;

    // [INFO] 格式：[METHOD] URL - STATUS - TIME
    // 示例：[POST] /api/record/submit - 200 - 45ms
    console.log(`[${ctx.method}] ${ctx.url} - ${ctx.status} - ${ms}ms`);
  } catch (err) {
    const ms = Date.now() - start;

    // [ERROR] 捕获所有未被下游 try-catch 处理的异常
    console.error(
      `[${ctx.method}] ${ctx.url} - ${err.status || 500} - ${ms}ms`
    );
    console.error("❌ 全局错误捕获:", err);

    // 继续向上抛出，确保 Koa 能返回 500 响应给客户端
    throw err;
  }
});

// ============================================================
// 2. Database Connection (数据库连接)
// ============================================================

console.log("MongoDB 开始连接...");

/**
 * Mongoose 连接配置
 * @see https://mongoosejs.com/docs/connections.html
 */
connect(mongoUrl, {
  // 【核心优化】连接池大小
  // 针对道路巡检场景的 "断网重连并发上传" 特性，适当调大连接池，
  // 防止瞬间涌入 50+ 请求导致数据库连接耗尽 (Connection Timeout)。
  maxPoolSize: 100,

  // 连接超时时间 (5秒)
  // 如果数据库 5秒 没响应，快速失败，避免前端长时间 loading。
  serverSelectionTimeoutMS: 5000,
})
  .then(() => console.log("✅ MongoDB 连接成功\n"))
  .catch((err) => {
    // 数据库连接是致命错误，建议在生产环境接入报警系统 (如钉钉/邮件)
    console.error("❌ MongoDB 连接失败:", err);
    // process.exit(1); // 可选：连接失败直接退出进程，让 PM2 重启
  });

// ============================================================
// 3. API Routes (业务路由)
// ============================================================

/**
 * @route GET /api/oss/sts
 * @summary 获取阿里云 OSS 临时上传凭证 (STS Token)
 * @description Android 端上传文件前，必须先调用此接口获取临时权限。
 * 安全策略：只返回 Token，绝不在日志中打印 AccessKeySecret。
 */
router.get("/api/oss/sts", async (ctx) => {
  console.log("🔑 [STS] 正在请求阿里云 Token..."); // Audit Log: 记录谁在申请权限
  try {
    const credentials = await getStsToken();
    console.log("✅ [STS] Token 签发成功");
    ctx.body = { code: 200, data: credentials };
  } catch (e) {
    // 生产环境脱敏：隐藏具体堆栈，只返回 "联系管理员"
    console.error("❌ [STS] 签发失败:", e.message);
    ctx.status = 500;
    ctx.body = { code: 500, message: "无法获取上传凭证，请联系管理员" };
  }
});

/**
 * @route POST /api/task/create
 * @summary 创建/同步 巡检任务
 * @description
 * 幂等性接口 (Idempotent):
 * 支持 Android 端重复提交。如果 taskId 已存在，则忽略本次插入，
 * 防止弱网环境下客户端重试导致的数据重复。
 * * @param {string} taskId - 任务 UUID (Client Side Generated)
 * @param {string} title - 任务标题
 * @param {string} inspectorId - 巡检员 ID
 * @param {number} startTime - 开始时间戳
 */
router.post("/api/task/create", async (ctx) => {
  const { taskId, title, inspectorId, startTime } = ctx.request.body;

  // 关键业务日志：记录核心 ID，方便日后排查 "某人说他建了任务但库里没有" 的扯皮问题
  console.log(
    `📋 [Task Create] 收到请求: User=${inspectorId}, Task=${taskId}, Title=${title}`
  );

  try {
    // 使用 MongoDB Upsert (更新或插入) 实现幂等
    // 语义：找到 taskId 相同的文档；如果没找到，则插入 ($setOnInsert)；如果找到了，什么都不改。
    await Task.updateOne(
      { taskId: taskId },
      {
        $setOnInsert: {
          taskId,
          title,
          inspectorId,
          startTime,
          isFinished: false,
        },
      },
      { upsert: true }
    );

    console.log(`✅ [Task Create] 任务入库成功: ${taskId}`);
    ctx.body = { code: 200, message: "任务创建成功" };
  } catch (e) {
    console.error(`❌ [Task Create] 失败 (ID: ${taskId}):`, e);
    ctx.body = { code: 500, message: "保存失败" };
  }
});

/**
 * @route POST /api/record/submit
 * @summary 提交单条病害记录
 * @description Android 端完成 OSS 直传后，调用此接口将图片 URL 和地理位置元数据存入数据库。
 * * @param {string} taskId - 关联的任务 ID
 * @param {string} serverUrl - 图片在 OSS 的完整 URL
 * @param {number} latitude - 纬度 (WGS84)
 * @param {number} longitude - 经度 (WGS84)
 * @param {string} address - 逆地理编码地址
 */
router.post("/api/record/submit", async (ctx) => {
  const body = ctx.request.body;

  // 日志作用：排查 "位置漂移" 问题。
  // 如果用户投诉定位不准，可对比此处日志中的 Loc 与用户实际位置。
  console.log(
    `📷 [Record] 收到图片: Task=${body.taskId}, Loc=[${body.longitude}, ${body.latitude}]`
  );

  // Data Transformation (数据清洗与适配)
  // 将扁平化的请求参数转换为符合 GeoJSON 标准的嵌套结构
  const recordData = {
    taskId: body.taskId,
    serverUrl: body.serverUrl,
    captureTime: body.captureTime,
    address: body.address,

    // 冗余存储原始经纬度，作为 "冷备" 数据，防止 GeoJSON 解析出问题时无据可查
    rawLat: body.latitude,
    rawLng: body.longitude,

    // GeoJSON Point 对象
    // ⚠️ 严正注意：MongoDB/GeoJSON 规范经纬度顺序为 [经度(Lng), 纬度(Lat)]
    // 这与 Google Maps API (Lat, Lng) 是相反的，切勿搞反！
    location: {
      type: "Point",
      coordinates: [body.longitude, body.latitude],
    },
  };

  try {
    const record = new Record(recordData);
    await record.save();
    console.log(`✅ [Record] 记录保存完成`);
    ctx.body = { code: 200, message: "记录保存成功" };
  } catch (e) {
    console.error(`❌ [Record] 保存失败:`, e);
    ctx.body = { code: 500, message: "保存失败" };
  }
});

/**
 * @route POST /api/task/finish
 * @summary 结束巡检任务
 * @description 更新任务状态为已完成 (isFinished: true) 并记录结束时间。
 */
router.post("/api/task/finish", async (ctx) => {
  const { taskId, endTime } = ctx.request.body;

  console.log(`🏁 [Task Finish] 尝试结束任务: ${taskId}`);

  try {
    // 🟢 BUG FIX: 之前代码未将 updateOne 结果赋值给 res，导致 res.matchedCount 报错
    const res = await Task.updateOne(
      { taskId: taskId },
      { $set: { endTime: endTime, isFinished: true } }
    );

    // 业务逻辑检查：确保要结束的任务确实存在
    if (res.matchedCount === 0) {
      console.warn(
        `⚠️ [Task Finish] 警告: 未找到任务 ID ${taskId}，可能是非法请求`
      );
      // 这里的 200 是为了兼容性，也可以考虑返回 404
      ctx.body = { code: 200, message: "任务可能已删除或不存在" };
    } else {
      console.log(`✅ [Task Finish] 任务状态已更新`);
      ctx.body = { code: 200, message: "任务已结束" };
    }
  } catch (e) {
    console.error(`❌ [Task Finish] 失败:`, e);
    ctx.body = { code: 500, message: "操作失败" };
  }
});

// ============================================================
// 4. Server Start (服务启动)
// ============================================================

// 挂载中间件
app.use(bodyParser()); // 解析 JSON Body
app.use(router.routes()).use(router.allowedMethods());

const PORT = 3000;
app.listen(PORT, () => {
  console.log(`
🚀 Road Inspection Server Running...
-----------------------------------
📡 Local:   http://localhost:${PORT}
💾 DB:      MongoDB Atlas
☁️ Cloud:   Aliyun OSS (Shanghai)
-----------------------------------
  `);
});
