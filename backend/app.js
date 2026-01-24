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

import bcrypt from "bcryptjs";
import Koa from "koa";
import bodyParser from "koa-bodyparser";
import Router from "koa-router";
import { connect } from "mongoose";
import { mongoUrl } from "./config/config.js";
import { Record, Task, User } from "./model/models.js";
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

    if (ctx.body && ctx.body.code && !ctx.headerSent) {
      ctx.status = ctx.body.code;
    }

    // [INFO] 格式：[METHOD] URL - STATUS - TIME
    // 示例：[POST] /api/record/submit - 200 - 45ms
    console.log(`[${ctx.method}] ${ctx.url} - ${ctx.status} - ${ms}ms`);
  } catch (err) {
    const ms = Date.now() - start;

    // [ERROR] 捕获所有未被下游 try-catch 处理的异常
    console.error(
      `[${ctx.method}] ${ctx.url} - ${err.status || 500} - ${ms}ms`,
    );
    console.error("❌ 全局错误捕获:", err);

    ctx.status = err.status || 500;
    ctx.body = {
      code: ctx.status,
      message: err.message || "Internal Server Error",
    };
  }
});

app.use(bodyParser()); // 解析 JSON Body

/**
 * 全局身份鉴权中间件
 * @description
 * 拦截除“白名单”外的所有请求，校验 JWT Token 有效性。
 * 校验通过会将用户信息挂载到 ctx.state.user，供下游路由使用。
 */
app.use(async (ctx, next) => {
  // ----------------------------------------------------------
  // TODO: [配置] 后续请将密钥移入 config.js 文件，并使用更复杂的随机字符串
  // ----------------------------------------------------------
  const JWT_SECRET = "temporary_secret_key_change_me_later";

  // 1. 定义白名单
  const whiteList = ["/api/auth/login", "/api/auth/register", "/favicon.ico"];

  // 如果请求路径在白名单中，直接放行
  if (whiteList.includes(ctx.path)) {
    return await next();
  }

  // 2. 获取 Authorization Header
  // 约定前端 Header 格式为: "Authorization: Bearer <token_string>"
  const authHeader = ctx.header.authorization;

  // if (!authHeader) {
  //   console.warn(`⛔ [Auth] 拦截未授权访问: ${ctx.path}`);
  //   ctx.status = 401;
  //   ctx.body = { code: 401, message: "未登录或 Token 缺失" };
  //   return;
  // }

  // 3. 提取并验证 Token
  try {
    // split(' ')[1] 是为了去掉前缀 "Bearer "
    // const token = authHeader.split(" ")[1];

    // if (!token) {
    //   throw new Error("Token 格式错误");
    // }

    // 验证 Token (如果过期或被篡改，verify 会抛出异常)
    // const decoded = jwt.verify(token, JWT_SECRET);

    // 4. 挂载用户信息
    // 成功后，后续路由可以通过 ctx.state.user 获取当前用户 ID 和 Role
    // ctx.state.user = decoded;

    // TODO: [可选] 这里可以添加检查用户是否被封禁的逻辑 (需查库，会有性能损耗)

    await next(); // 验证通过，放行
  } catch (err) {
    // 区分 Token 过期还是 Token 无效
    const isExpired = err.name === "TokenExpiredError";
    const msg = isExpired ? "登录已过期，请重新登录" : "Token 无效或非法";

    console.warn(`⛔ [Auth] 鉴权失败 (${err.name}): ${ctx.path}`);

    ctx.status = 401;
    ctx.body = { code: 401, message: msg };
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

// ============================================================
// Auth Routes (用户认证)
// ============================================================

/**
 * @route POST /api/auth/register
 * @summary 用户注册
 * @description
 * 1. 校验用户名是否已存在
 * 2. 对密码进行 BCrypt 哈希加密
 * 3. 创建用户文档
 */
router.post("/api/auth/register", async (ctx) => {
  const { username, password, role } = ctx.request.body;

  // 1. 基础参数校验
  if (!username || !password) {
    ctx.status = 400;
    ctx.body = { code: 400, message: "用户名和密码不能为空" };
    return;
  }

  console.log(`👤 [Auth Register] 收到注册请求: ${username}`);

  try {
    // 2. 检查用户名是否已存在
    const existingUser = await User.findOne({ username });
    if (existingUser) {
      console.warn(`⚠️ [Auth Register] 用户名已存在: ${username}`);
      ctx.status = 409;
      ctx.body = { code: 409, message: "用户名已被占用" }; // 409 Conflict
      return;
    }

    // 3. 密码加密 (Salt Rounds = 10)
    const salt = await bcrypt.genSalt(10);
    const hashedPassword = await bcrypt.hash(password, salt);

    // 4. 创建用户
    const newUser = new User({
      username,
      hashedPassword,
      role: role || "inspector", // 默认为巡检员
    });

    await newUser.save();

    console.log(`✅ [Auth Register] 用户注册成功: ${newUser.id}`);

    ctx.body = {
      code: 200,
      message: "注册成功",
      // 返回基本信息，注意：User 模型配置了 transform，会自动包含 id，隐藏 _id
      data: {
        id: newUser.id,
        username: newUser.username,
        role: newUser.role,
      },
    };
  } catch (e) {
    console.error(`❌ [Auth Register] 注册失败:`, e);
    ctx.status = 500;
    ctx.body = { code: 500, message: "注册失败，服务器内部错误" };
  }
});

/**
 * @route POST /api/auth/login
 * @summary 用户登录
 * @description
 * 验证用户名密码，返回用户 ID 给移动端暂存。
 * 后续移动端在上传 Task 时，需将此 ID 填入 inspectorId 字段。
 */
router.post("/api/auth/login", async (ctx) => {
  const { username, password } = ctx.request.body;

  if (!username || !password) {
    ctx.status = 400;
    ctx.body = { code: 400, message: "请输入用户名和密码" };
    return;
  }

  console.log(`🔐 [Auth Login] 尝试登录: ${username}`);

  try {
    // 1. 查找用户
    //  .select('+hashedPassword') 才能将user的hashedPassward取出。
    const user = await User.findOne({ username }).select("+hashedPassword");

    // 2. 账号不存在校验
    if (!user) {
      console.warn(`⚠️ [Auth Login] 用户不存在: ${username}`);
      ctx.status = 401;
      ctx.body = { code: 401, message: "用户名或密码错误" };
      return;
    }

    // 3. 软删除校验 (离职员工禁止登录)
    if (user.deletedAt) {
      console.warn(`⛔ [Auth Login] 已离职用户尝试登录: ${username}`);
      ctx.status = 403;
      ctx.body = { code: 403, message: "账号已停用" };
      return;
    }

    // 4. 密码比对
    const isMatch = await bcrypt.compare(password, user.hashedPassword);
    if (!isMatch) {
      console.warn(`⚠️ [Auth Login] 密码错误: ${username}`);
      ctx.status = 401;
      ctx.body = { code: 401, message: "用户名或密码错误" };
      return;
    }

    console.log(`✅ [Auth Login] 登录成功: ${user.id} (${user.role})`);

    // 5. 返回结果
    // 目前阶段：直接返回 User ID 给安卓端保存
    // 未来阶段：这里会改为生成 JWT Token 返回
    ctx.body = {
      code: 200,
      message: "登录成功",
      data: {
        id: user.id,
        username: user.username,
        role: user.role,
      },
    };
  } catch (e) {
    console.error(`❌ [Auth Login] 登录异常:`, e);
    ctx.status = 500;
    ctx.body = { code: 500, message: "登录服务异常" };
  }
});

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
  const { taskId, title, inspectorId, startTime, endTime } = ctx.request.body;

  // 关键业务日志：记录核心 ID，方便日后排查 "某人说他建了任务但库里没有" 的扯皮问题
  console.log(
    `📋 [Task Create] 收到请求: User=${inspectorId}, Task=${taskId}, Title=${title}`,
  );

  const isFinished = !!endTime;

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
          endTime: endTime || null,
          isFinished: isFinished,
        },
      },
      // 如果任务不存在则插入，存在则忽略($setOnInsert不生效)
      { upsert: true },
    );

    console.log(`✅ [Task Create] 任务入库成功: ${taskId}`);
    ctx.body = { code: 200, message: "任务创建成功" };
  } catch (e) {
    console.error(`❌ [Task Create] 失败 (ID: ${taskId}):`, e);
    ctx.status = 500;
    ctx.body = { code: 500, message: "任务创建失败" };
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
    `📷 [Record] 收到图片: Task=${body.taskId}, Loc=[${body.longitude}, ${body.latitude}]`,
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
    ctx.status = 500;
    ctx.body = { code: 500, message: "记录保存失败" };
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
      { $set: { endTime: endTime, isFinished: true } },
    );

    // 业务逻辑检查：确保要结束的任务确实存在
    if (res.matchedCount === 0) {
      console.warn(
        `⚠️ [Task Finish] 警告: 未找到任务 ID ${taskId}，可能是非法请求`,
      );
      // 这里的 200 是为了兼容性，也可以考虑返回 404
      ctx.body = { code: 200, message: "任务可能已删除或不存在" };
    } else {
      console.log(`✅ [Task Finish] 任务状态已更新`);
      ctx.body = { code: 200, message: "任务已结束" };
    }
  } catch (e) {
    console.error(`❌ [Task Finish] 失败:`, e);
    ctx.status = 500;
    ctx.body = { code: 500, message: "同步任务结束失败" };
  }
});

/**
 * @route GET /api/record/list
 * @summary 前端获取指定任务下的所有病害记录
 * @description
 * 根据 taskId 拉取该任务关联的所有 Record 数据。
 * 通常用于 "任务详情页" 或 "历史记录回放" 功能。
 *
 * @param {string} taskId - 任务 ID (通过 Query Param 传递, e.g., ?taskId=xxx)
 */
router.get("/api/record/list", async (ctx) => {
  // 1. 从 URL 查询参数中获取 taskId (GET 请求不读取 body)
  const { taskId } = ctx.query;

  // 2. 参数校验
  if (!taskId) {
    console.warn(`⚠️ [Record List] 请求缺失 taskId`);
    ctx.status = 400; // Bad Request
    ctx.body = { code: 400, message: "参数 taskId 不能为空" };
    return;
  }

  console.log(`🔍 [Record List] 正在查询任务记录: ${taskId}`);

  try {
    // 3. 数据库查询
    // find: 查找所有匹配文档
    // sort: 按拍摄时间 (captureTime) 正序排列，方便前端按时间轴展示
    const records = await Record.find({ taskId: taskId }).sort({
      captureTime: 1,
    });

    // 4. 组装响应
    const count = records.length;
    console.log(`✅ [Record List] 查询成功: 找到 ${count} 条记录`);

    ctx.body = {
      code: 200,
      data: records,
      message: "获取成功",
    };
  } catch (e) {
    console.error(`❌ [Record List] 查询出错 (ID: ${taskId}):`, e);
    ctx.status = 500;
    ctx.body = { code: 500, message: "获取记录失败，请稍后重试" };
  }
});

// ============================================================
// 4. Server Start (服务启动)
// ============================================================

// 挂载中间件
app.use(router.routes()).use(router.allowedMethods());

// 导出 app 实例供测试使用
export { app };

// 只有当文件直接被运行时，才启动服务器
if (process.env.NODE_ENV !== "test") {
  const PORT = process.env.PORT || 3000;
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
}
