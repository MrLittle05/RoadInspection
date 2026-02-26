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

import bcrypt from 'bcryptjs'
import jwt from 'jsonwebtoken'
import Koa from 'koa'
import bodyParser from 'koa-bodyparser'
import Router from 'koa-router'
import { connect } from 'mongoose'
import { mongoUrl } from './config/config.js'
import { Record, Task, User } from './model/models.js'
import { getStsToken } from './utils/oss_helper.js'
import {
  apiLimiter,
  authLimiter,
  recordSubmitLimiter,
} from './utils/rateLimit.js'
import { generateTokens } from './utils/token.js'

const app = new Koa()
const router = new Router()

// ============================================================
// 1. Global Middleware (全局中间件)
// ============================================================

/**
 * 全局请求日志中间件 (Access Logger)
 * 遵循 "3W原则" (Who, When, Result) 记录所有流入服务器的 HTTP 请求。
 * 用于生产环境的性能监控和故障排查。
 */
app.use(async (ctx, next) => {
  const start = Date.now()
  try {
    await next() // 放行请求进入下游路由
    const ms = Date.now() - start

    if (ctx.body && ctx.body.code && !ctx.headerSent) {
      ctx.status = ctx.body.code
    }

    // [INFO] 格式：[METHOD] URL - STATUS - TIME
    // 示例：[POST] /api/record/submit - 200 - 45ms
    console.log(`[${ctx.method}] ${ctx.url} - ${ctx.status} - ${ms}ms`)
  } catch (err) {
    const ms = Date.now() - start

    // [ERROR] 捕获所有未被下游 try-catch 处理的异常
    console.error(`[${ctx.method}] ${ctx.url} - ${err.status || 500} - ${ms}ms`)
    console.error('❌ 全局错误捕获:', err)

    ctx.status = err.status || 500
    ctx.body = {
      code: ctx.status,
      message: err.message || 'Internal Server Error',
    }
  }
})

app.use(bodyParser()) // 解析 JSON Body

/**
 * JWT Authentication Middleware (全局鉴权中间件)
 * @description
 * 1. 拦截非白名单请求。
 * 2. 验证 Access Token 有效性。
 * 3. 处理过期 (401) 与 无效 (403) 两种情况，供前端区分处理。
 */
app.use(async (ctx, next) => {
  // 1. 定义白名单 (无需登录即可访问的接口)
  // 注意：/api/auth/refresh 也必须在白名单中，因为它是用来换取新 Token 的，
  // 调用它时 Access Token 通常已经过期了。
  const whiteList = [
    '/api/auth/login',
    '/api/auth/register',
    '/api/auth/refresh',
    '/favicon.ico',
  ]

  if (whiteList.includes(ctx.path)) {
    return await next()
  }

  // 2. 提取 Token
  const authHeader = ctx.header.authorization
  if (!authHeader) {
    ctx.status = 401
    ctx.body = { code: 401, message: '未提供认证令牌' }
    return
  }

  const token = authHeader.split(' ')[1] // Remove "Bearer " prefix

  try {
    // 3. 验证 Access Token
    const decoded = jwt.verify(token, process.env.JWT_ACCESS_SECRET)

    // 4. 挂载用户信息到 Context
    ctx.state.user = decoded

    await next() // 验证通过，放行
  } catch (err) {
    // 5. 错误区分处理
    if (err.name === 'TokenExpiredError') {
      // ✅ 关键：返回 401，告诉前端/Android端 Access Token 过期了，
      // Android 的 Authenticator 会捕获这个 401 并触发刷新逻辑。
      ctx.status = 401
      ctx.body = { code: 401, message: 'TokenExpired' }
    } else {
      // 其他错误（被篡改、格式错误），返回 403 禁止访问，前端应强制登出
      console.warn(`⛔ 非法 Token 访问: ${ctx.path}`)
      ctx.status = 403
      ctx.body = { code: 403, message: 'TokenInvalid' }
    }
  }
})

// ============================================================
// 2. Database Connection (数据库连接)
// ============================================================

console.log('MongoDB 开始连接...')

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
  .then(() => console.log('✅ MongoDB 连接成功\n'))
  .catch((err) => {
    // 数据库连接是致命错误，建议在生产环境接入报警系统 (如钉钉/邮件)
    console.error('❌ MongoDB 连接失败:', err)
    // process.exit(1); // 可选：连接失败直接退出进程，让 PM2 重启
  })

// ============================================================
// 3. API Routes (业务路由)
// ============================================================

/**
 * @route POST /api/auth/register
 * @summary 注册并直接返回 Token (注册即登录)
 */
router.post('/api/auth/register', authLimiter, async (ctx) => {
  const { username, password, role } = ctx.request.body

  if (!username || !password) {
    ctx.status = 400
    ctx.body = { code: 400, message: '参数不完整' }
    return
  }

  try {
    const existingUser = await User.findOne({ username })
    if (existingUser) {
      ctx.status = 409
      ctx.body = { code: 409, message: '用户名已被占用' }
      return
    }

    const salt = await bcrypt.genSalt(10)
    const hashedPassword = await bcrypt.hash(password, salt)

    const newUser = new User({
      username,
      hashedPassword,
      role: role || 'inspector',
    })

    // 生成 Token
    const tokens = generateTokens(newUser)
    // 保存 Refresh Token 到数据库 (用于后续验证和注销)
    newUser.refreshToken = tokens.refreshToken

    await newUser.save()

    console.log(`✅ 用户注册成功: ${newUser.username}`)

    ctx.body = {
      code: 200,
      message: '注册成功',
      data: {
        id: newUser.id,
        username: newUser.username,
        role: newUser.role,
        accessToken: tokens.accessToken,
        refreshToken: tokens.refreshToken,
      },
    }
  } catch (e) {
    console.error(e)
    ctx.status = 500
    ctx.body = { code: 500, message: '注册失败' }
  }
})

/**
 * @route POST /api/auth/login
 * @summary 登录并下发双 Token
 */
router.post('/api/auth/login', authLimiter, async (ctx) => {
  const { username, password } = ctx.request.body

  try {
    const user = await User.findOne({ username }).select('+hashedPassword')

    if (!user) {
      ctx.status = 401
      ctx.body = { code: 401, message: '用户名或密码错误' }
      return
    }

    if (user.deletedAt) {
      ctx.status = 403
      ctx.body = { code: 403, message: '账号已停用' }
      return
    }

    const isMatch = await bcrypt.compare(password, user.hashedPassword)
    if (!isMatch) {
      ctx.status = 401
      ctx.body = { code: 401, message: '用户名或密码错误' }
      return
    }

    // ✅ 登录成功，签发 Token
    const tokens = generateTokens(user)

    // ✅ 将 Refresh Token 更新到数据库 (覆盖旧的，实现单点登录效果)
    // 如果需要支持多设备同时登录，这里需要改为数组存储 [token1, token2...]
    user.refreshToken = tokens.refreshToken
    await user.save() // 使用 save 触发 schema 校验，或使用 updateOne

    console.log(`✅ [Login] 用户登录: ${username}`)

    ctx.body = {
      code: 200,
      message: '登录成功',
      data: {
        id: user.id,
        username: user.username,
        role: user.role,
        accessToken: tokens.accessToken,
        refreshToken: tokens.refreshToken,
      },
    }
  } catch (e) {
    console.error(e)
    ctx.status = 500
    ctx.body = { code: 500, message: '登录异常' }
  }
})

/**
 * @route POST /api/auth/refresh
 * @summary 刷新 Token (Exchange Refresh Token for new Pair)
 * @description
 * 客户端 Access Token 过期后 (401)，调用此接口换取新 Token。
 * 采用了 "Token Rotation" 策略：刷新后，旧的 Refresh Token 作废，颁发全新的。
 */
router.post('/api/auth/refresh', authLimiter, async (ctx) => {
  const { refreshToken } = ctx.request.body

  if (!refreshToken) {
    ctx.status = 400
    ctx.body = { code: 400, message: 'Refresh Token 缺失' }
    return
  }

  try {
    // 1. 验证 Refresh Token 签名
    const decoded = jwt.verify(refreshToken, process.env.JWT_REFRESH_SECRET)
    const userId = decoded.id

    // 2. 数据库比对 (防盗用核心检查)
    // 检查前端传来的 Refresh Token 是否与数据库中存储的一致
    // select('+refreshToken') 因为该字段通常设为 select: false
    const user = await User.findById(userId).select('+refreshToken')

    if (!user || user.refreshToken !== refreshToken) {
      console.warn(`⛔ [Risk] Refresh Token 重放或已失效: User=${userId}`)
      // 如果 Token 不匹配，说明可能该 Token 已被使用过（或者用户已注销）
      // 此时应视为安全风险，强制前端登出
      ctx.status = 403
      ctx.body = { code: 403, message: '无效的刷新令牌，请重新登录' }
      return
    }

    // 3. 签发新的双 Token (Rotation)
    const newTokens = generateTokens(user)

    // 4. 更新数据库，废弃旧的 Refresh Token
    user.refreshToken = newTokens.refreshToken
    await user.save()

    console.log(`🔄 [Refresh] Token 刷新成功: ${user.username}`)

    ctx.body = {
      code: 200,
      message: 'Token 刷新成功',
      data: {
        accessToken: newTokens.accessToken,
        refreshToken: newTokens.refreshToken,
      },
    }
  } catch (err) {
    console.warn(`❌ [Refresh] 异常: ${err.message}`)

    // 1. 如果是 JWT 相关的错误，说明是凭证问题 -> 403
    if (err.name === 'JsonWebTokenError' || err.name === 'TokenExpiredError') {
      ctx.status = 403
      ctx.body = { code: 403, message: '登录凭证无效，请重新登录' }
    }
    // 2. 否则，视作服务器内部错误 -> 500 (或其他状态码，不要用 403)
    else {
      console.error(err) // 打印具体堆栈
      ctx.status = 500
      ctx.body = { code: 500, message: '服务器繁忙，请稍后重试' }
    }
  }
})

/**
 * @route POST /api/auth/logout
 * @summary 退出登录
 * @description
 * 安全的注销逻辑：
 * 1. 优先从 Access Token 获取身份。
 * 2. 如果 Access Token 失效，则校验 Body 中的 Refresh Token 获取身份。
 * 3. 两者都无效，则认为用户已经离线，直接返回成功 (前端自行清除本地缓存即可)。
 */
router.post('/api/auth/logout', authLimiter, async (ctx) => {
  let userId

  // ---------------------------------------------------------
  // 方式 A: 从 Access Token 解析 (由鉴权中间件 ctx.state.user 提供)
  // ---------------------------------------------------------
  if (ctx.state.user && ctx.state.user.id) {
    userId = ctx.state.user.id
  }

  // ---------------------------------------------------------
  // 方式 B: Access Token 已过期，尝试验证 Body 里的 Refresh Token
  // ---------------------------------------------------------
  else {
    const { refreshToken } = ctx.request.body
    if (refreshToken) {
      try {
        // 关键步骤：验证 Token 签名，防止伪造 ID
        // 这里使用之前定义的 JWT_REFRESH_SECRET
        const decoded = jwt.verify(
          refreshToken,
          process.env.JWT_REFRESH_SECRET ||
            'road_inspection_refresh_secret_secure_key',
        )
        userId = decoded.id
      } catch (e) {
        console.warn(`⚠️ [Logout] 无效的 Refresh Token，无法在服务端注销`)
        // Token 既然是假的或过期的，说明服务端本来就无法刷新，视作"已注销"即可
      }
    }
  }

  // ---------------------------------------------------------
  // 执行注销操作
  // ---------------------------------------------------------
  if (userId) {
    console.log(`👋 [Logout] 用户离线: ${userId}`)
    // 核心操作：将数据库中的 refreshToken 置空，断绝其刷新后路
    await User.updateOne({ _id: userId }, { $set: { refreshToken: null } })
  } else {
    console.log(`👋 [Logout] 本地注销 (服务端未识别身份或已过期)`)
  }

  // 无论服务端是否执行了 DB 操作，对前端来说结果都是"已退出"
  ctx.body = { code: 200, message: '已退出登录' }
})

/**
 * @route PATCH /api/user/:id
 * @summary 修改用户信息 (用户名或密码)
 * @description
 * 采用 Partial Update 策略：
 * 1. 如果只传 newUsername，仅修改用户名（会校验唯一性）。
 * 2. 如果只传 newPassword，仅修改密码（会自动加盐哈希）。
 * 3. 两个都传，则同时修改。
 * * @param {string} id - URL路径参数，目标用户的 ID
 * @param {string} [newUsername] - 新用户名 (可选)
 * @param {string} [newPassword] - 新密码 (可选)
 */
router.patch('/api/user/:id', apiLimiter, async (ctx) => {
  const userId = ctx.params.id
  const { newUsername, newPassword } = ctx.request.body

  // 1. 参数防御：确保至少有一个参数需要修改
  if (!newUsername && !newPassword) {
    ctx.status = 400
    ctx.body = { code: 400, message: '请提供需要修改的用户名或密码' }
    return
  }

  console.log(`🔧 [User Update] 收到修改请求: User=${userId}`)

  try {
    // 2. 查找目标用户
    const user = await User.findById(userId)
    if (!user) {
      console.warn(`⚠️ [User Update] 用户不存在: ${userId}`)
      ctx.status = 404
      ctx.body = { code: 404, message: '用户不存在' }
      return
    }

    // 3. 处理用户名修改
    if (newUsername) {
      // 检查是否与当前一致（避免无意义的数据库查重）
      if (newUsername !== user.username) {
        // 检查唯一性：查看是否有“别人”用了这个名字
        // $ne (Not Equal) 排除了当前用户自己
        const existingUser = await User.findOne({
          username: newUsername,
          _id: { $ne: userId },
        })

        if (existingUser) {
          ctx.status = 409
          ctx.body = { code: 409, message: '该用户名已被其他人占用' }
          return
        }

        console.log(
          `📝 [User Update] 更新用户名: ${user.username} -> ${newUsername}`,
        )
        user.username = newUsername
      }
    }

    // 4. 处理密码修改
    if (newPassword) {
      // 只有当提供了新密码时，才进行昂贵的哈希计算
      const salt = await bcrypt.genSalt(10)
      const hashedPassword = await bcrypt.hash(newPassword, salt)

      console.log(`🔐 [User Update] 更新密码: User=${userId}`)
      user.hashedPassword = hashedPassword
    }

    // 5. 保存更改
    // 使用 save() 而不是 updateOne()，是为了触发 Mongoose 可能存在的 pre-save 钩子 (虽然目前你的 model 没写，但这是好习惯)
    await user.save()

    console.log(`✅ [User Update] 修改成功: User=${userId}`)

    ctx.body = {
      code: 200,
      message: '用户信息更新成功',
      data: {
        id: user.id,
        username: user.username,
        role: user.role,
      },
    }
  } catch (e) {
    console.error(`❌ [User Update] 修改失败:`, e)
    ctx.status = 500
    ctx.body = { code: 500, message: '更新用户信息失败' }
  }
})

/**
 * @route GET /api/oss/sts
 * @summary 获取阿里云 OSS 临时上传凭证 (STS Token)
 * @description Android 端上传文件前，必须先调用此接口获取临时权限。
 * 安全策略：只返回 Token，绝不在日志中打印 AccessKeySecret。
 */
router.get('/api/oss/sts', apiLimiter, async (ctx) => {
  console.log('🔑 [STS] 正在请求阿里云 Token...') // Audit Log: 记录谁在申请权限
  try {
    const credentials = await getStsToken()
    console.log('✅ [STS] Token 签发成功')
    ctx.body = { code: 200, data: credentials }
  } catch (e) {
    // 生产环境脱敏：隐藏具体堆栈，只返回 "联系管理员"
    console.error('❌ [STS] 签发失败:', e.message)
    ctx.status = 500
    ctx.body = { code: 500, message: '无法获取上传凭证，请联系管理员' }
  }
})

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
router.post('/api/task/create', apiLimiter, async (ctx) => {
  const { taskId, title, inspectorId, startTime, endTime } = ctx.request.body

  // 关键业务日志：记录核心 ID，方便日后排查 "某人说他建了任务但库里没有" 的扯皮问题
  console.log(
    `📋 [Task Create] 收到请求: User=${inspectorId}, Task=${taskId}, Title=${title}`,
  )

  const isFinished = !!endTime

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
    )

    console.log(`✅ [Task Create] 任务入库成功: ${taskId}`)
    ctx.body = { code: 200, message: '任务创建成功' }
  } catch (e) {
    console.error(`❌ [Task Create] 失败 (ID: ${taskId}):`, e)
    ctx.status = 500
    ctx.body = { code: 500, message: '任务创建失败' }
  }
})

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
router.post('/api/record/submit', recordSubmitLimiter, async (ctx) => {
  const body = ctx.request.body

  // 日志作用：排查 "位置漂移" 问题。
  // 如果用户投诉定位不准，可对比此处日志中的 Loc 与用户实际位置。
  console.log(
    `📷 [Record] 收到记录: Task=${body.taskId}, Loc=[${body.longitude}, ${body.latitude}], IRI=${body.iri}`,
  )

  // Data Transformation (数据清洗与适配)
  // 将扁平化的请求参数转换为符合 GeoJSON 标准的嵌套结构
  const recordData = {
    recordId: body.recordId,
    taskId: body.taskId,
    serverUrl: body.serverUrl,
    captureTime: body.captureTime,
    address: body.address,

    // 冗余存储原始经纬度，作为 "冷备" 数据，防止 GeoJSON 解析出问题时无据可查
    rawLat: body.latitude,
    rawLng: body.longitude,

    iri: body.iri ?? null,
    pavementDistress: body.pavementDistress ?? null,

    // GeoJSON Point 对象
    // ⚠️ 严正注意：MongoDB/GeoJSON 规范经纬度顺序为 [经度(Lng), 纬度(Lat)]
    // 这与 Google Maps API (Lat, Lng) 是相反的，切勿搞反！
    location: {
      type: 'Point',
      coordinates: [body.longitude, body.latitude],
    },
  }

  try {
    const record = new Record(recordData)
    await record.save()
    console.log(`✅ [Record] 记录保存完成`)
    ctx.body = { code: 200, message: '记录保存成功' }
  } catch (e) {
    console.error(`❌ [Record] 保存失败:`, e)
    ctx.status = 500
    ctx.body = { code: 500, message: '记录保存失败' }
  }
})

/**
 * @route POST /api/task/finish
 * @summary 结束巡检任务
 * @description 更新任务状态为已完成 (isFinished: true) 并记录结束时间。
 */
router.post('/api/task/finish', apiLimiter, async (ctx) => {
  const { taskId, endTime } = ctx.request.body

  console.log(`🏁 [Task Finish] 尝试结束任务: ${taskId}`)

  try {
    // 🟢 BUG FIX: 之前代码未将 updateOne 结果赋值给 res，导致 res.matchedCount 报错
    const res = await Task.updateOne(
      { taskId: taskId },
      { $set: { endTime: endTime, isFinished: true } },
    )

    // 业务逻辑检查：确保要结束的任务确实存在
    if (res.matchedCount === 0) {
      console.warn(
        `⚠️ [Task Finish] 警告: 未找到任务 ID ${taskId}，可能是非法请求`,
      )
      // 这里的 200 是为了兼容性，也可以考虑返回 404
      ctx.body = { code: 200, message: '任务可能已删除或不存在' }
    } else {
      console.log(`✅ [Task Finish] 任务状态已更新`)
      ctx.body = { code: 200, message: '任务已结束' }
    }
  } catch (e) {
    console.error(`❌ [Task Finish] 失败:`, e)
    ctx.status = 500
    ctx.body = { code: 500, message: '同步任务结束失败' }
  }
})

/**
 * @route GET /api/task/list
 * @summary 获取指定用户的任务列表
 * @description
 * 根据 userId (inspectorId) 拉取该巡检员的所有任务。
 * 结果按任务开始时间 (startTime) 倒序排列 (最新的在前)。
 *
 * @param {string} userId - 用户 ID (Query Param, e.g., ?userId=xxx)
 */
router.get('/api/task/list', apiLimiter, async (ctx) => {
  // 1. 获取查询参数
  const { userId } = ctx.query

  // 2. 参数校验
  if (!userId) {
    console.warn(`⚠️ [Task List] 请求缺失 userId`)
    ctx.status = 400
    ctx.body = { code: 400, message: '参数 userId 不能为空' }
    return
  }

  console.log(`🔍 [Task List] 正在查询用户任务: ${userId}`)

  try {
    // 3. 数据库查询
    // 过滤条件: inspectorId 匹配 userId
    // 排序: startTime: -1 (降序/最新的在最上面)
    const tasks = await Task.find({
      inspectorId: userId,
      deletedAt: null,
    }).sort({
      startTime: -1,
    })

    console.log(`✅ [Task List] 查询成功: 找到 ${tasks.length} 个任务`)

    ctx.body = {
      code: 200,
      data: tasks,
      message: '获取任务列表成功',
    }
  } catch (e) {
    console.error(`❌ [Task List] 查询失败 (User: ${userId}):`, e)
    ctx.status = 500
    ctx.body = { code: 500, message: '获取任务列表失败' }
  }
})

/**
 * @route GET /api/record/list
 * @summary 前端获取指定任务下的所有病害记录
 * @description
 * 根据 taskId 拉取该任务关联的所有 Record 数据。
 * 通常用于 "任务详情页" 或 "历史记录回放" 功能。
 *
 * @param {string} taskId - 任务 ID (通过 Query Param 传递, e.g., ?taskId=xxx)
 */
router.get('/api/record/list', apiLimiter, async (ctx) => {
  // 1. 从 URL 查询参数中获取 taskId (GET 请求不读取 body)
  const { taskId } = ctx.query

  // 2. 参数校验
  if (!taskId) {
    console.warn(`⚠️ [Record List] 请求缺失 taskId`)
    ctx.status = 400 // Bad Request
    ctx.body = { code: 400, message: '参数 taskId 不能为空' }
    return
  }

  console.log(`🔍 [Record List] 正在查询任务记录: ${taskId}`)

  try {
    // 3. 数据库查询
    // find: 查找所有匹配文档
    // sort: 按拍摄时间 (captureTime) 正序排列，方便前端按时间轴展示
    const records = await Record.find({ taskId: taskId, deletedAt: null }).sort(
      {
        captureTime: 1,
      },
    )

    // 4. 组装响应
    const count = records.length
    console.log(`✅ [Record List] 查询成功: 找到 ${count} 条记录`)

    ctx.body = {
      code: 200,
      data: records,
      message: '获取成功',
    }
  } catch (e) {
    console.error(`❌ [Record List] 查询出错 (ID: ${taskId}):`, e)
    ctx.status = 500
    ctx.body = { code: 500, message: '获取记录失败，请稍后重试' }
  }
})

/**
 * @route DELETE /api/task/:taskId
 * @summary 软删除任务及其关联数据
 * @description
 * 1. 校验 userId 是否存在。
 * 2. 查找 taskId 且 inspectorId 匹配的任务 (权限控制)。
 * 3. 级联更新 deletedAt。
 */
router.delete('/api/task/:taskId', apiLimiter, async (ctx) => {
  const { taskId } = ctx.params
  const { userId } = ctx.query

  // 1. 参数校验
  if (!userId) {
    ctx.status = 400
    ctx.body = { code: 400, message: '参数 userId 不能为空' }
    return
  }

  console.log(`🗑️ [Task Delete] 请求删除: ${taskId} (User: ${userId})`)

  try {
    const now = new Date()

    // 2. 软删除任务 (增加 inspectorId 匹配条件，确保只能删自己的)
    const taskRes = await Task.updateOne(
      { taskId: taskId, inspectorId: userId },
      { $set: { deletedAt: now } },
    )

    // 3. 结果判断
    if (taskRes.matchedCount === 0) {
      // 没匹配到，可能是任务不存在，也可能是 userId 对不上（无权删除）
      console.warn(`⚠️ [Task Delete] 任务不存在或无权删除: ${taskId}`)
      ctx.status = 404
      ctx.body = { code: 404, message: '任务不存在或无权删除' }
      return // ⛔ 任务没删掉，绝对不能去删 Records
    }

    // 4. 级联软删除关联的 Record
    const recordRes = await Record.updateMany(
      { taskId: taskId },
      { $set: { deletedAt: now } },
    )

    console.log(
      `✅ [Task Delete] 删除成功: 任务x${taskRes.modifiedCount}, 记录x${recordRes.modifiedCount}`,
    )
    ctx.body = { code: 200, message: '删除成功' }
  } catch (e) {
    console.error(`❌ [Task Delete] 异常:`, e)
    ctx.status = 500
    ctx.body = { code: 500, message: '删除失败' }
  }
})

// ============================================================
// 4. Server Start (服务启动)
// ============================================================

// 挂载中间件
app.use(router.routes()).use(router.allowedMethods())

// 导出 app 实例供测试使用
export { app }

// 只有当文件直接被运行时，才启动服务器
if (process.env.NODE_ENV !== 'test') {
  const PORT = process.env.PORT || 3000
  const HOST = process.env.HOST || '0.0.0.0'
  app.listen(PORT, HOST, () => {
    console.log(`
🚀 Road Inspection Server Running...
-----------------------------------
📡 Local:   http://${HOST}:${PORT}
💾 DB:      MongoDB Atlas
☁️ Cloud:   Aliyun OSS (Shanghai)
-----------------------------------
  `)
  })
}
