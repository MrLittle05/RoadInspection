/**
 * @module config
 * @description 全局配置文件
 * 负责集中管理环境变量、数据库连接串和阿里云 OSS 配置信息。
 * * 依赖环境变量 (.env):
 * - MONGO_USERNAME, MONGO_PASSWORD: 数据库凭证
 * - ALIYUN_BUCKET, ALIYUN_REGION: OSS 基础信息
 * - ALIYUN_ACCESS_KEY_ID, ALIYUN_ACCESS_KEY_SECRET: RAM 用户凭证
 * - ALIYUN_ROLE_ARN: STS 授权角色
 */

// 构建 MongoDB 连接字符串
// 注意：生产环境建议对 username/password 进行 URL 编码防止特殊字符导致解析错误
const mongoUrl = `mongodb+srv://${process.env.MONGO_USERNAME}:${process.env.MONGO_PASSWORD}@lis464.reywwyg.mongodb.net/road_inspection`;

// [Debug] 检查环境变量读取状态
// TODO: 上线生产环境前请移除或注释掉此类敏感信息的日志
console.log("用户:", process.env.MONGO_USERNAME);
console.log(
  "密码:",
  process.env.MONGO_PASSWORD ? "****** (已读取)" : "❌ 未读取到 (undefined)"
);

/**
 * @typedef {Object} AliyunConfig
 * @property {string} bucket - OSS 存储桶名称
 * @property {string} region - OSS 地域
 * @property {string} accessKeyId - RAM 用户的 AccessKey ID
 * @property {string} accessKeySecret - RAM 用户的 AccessKey Secret
 * @property {string} roleArn - RAM 角色的 ARN 标识符
 * @property {number} tokenExpireTime - STS Token 有效期(秒)
 */

/** @type {AliyunConfig} */
const aliyun = {
  bucket: process.env.ALIYUN_BUCKET,
  region: process.env.ALIYUN_REGION,
  accessKeyId: process.env.ALIYUN_ACCESS_KEY_ID,
  accessKeySecret: process.env.ALIYUN_ACCESS_KEY_SECRET,

  // RAM 角色 ARN (核心配置：决定了 Token 拥有什么权限)
  // 格式示例: acs:ram::123456789:role/app-upload-role
  roleArn: process.env.ALIYUN_ROLE_ARN,

  // Token 有效期 (秒)
  // 建议设置短一点 (如 900s/15min)，以此降低 Token 泄露后的风险
  tokenExpireTime: 900,
};

export { aliyun, mongoUrl };
