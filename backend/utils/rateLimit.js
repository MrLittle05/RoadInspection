import { RateLimit } from 'koa2-ratelimit'

// 1. 登录注册接口限流 (防暴力破解，按 IP)
// 策略：每个 IP 每分钟最多调用 5 次
export const authLimiter = RateLimit.middleware({
  interval: { min: 1 },
  max: 5,
  message: { code: 429, message: '请求过于频繁，请稍后再试' },
})

// 2. 业务接口限流 (防恶意刷量，按 userId)
// 策略：针对 /api/record/submit 等接口，每分钟最多 60 次
export const apiLimiter = RateLimit.middleware({
  interval: { min: 1 },
  max: 60,
  message: { code: 429, message: '接口调用超限，请稍后重试' },
  // 核心：基于鉴权中间件挂载的 ctx.state.user.id 进行精准限流
  keyGenerator: async (ctx) => {
    // 优先使用 userId 限流
    if (ctx.state.user && ctx.state.user.id) {
      return ctx.state.user.id
    }
    // 兜底：如果没拿到 userId（比如鉴权失败），降级为 IP 限流
    return ctx.request.ip
  },
})
