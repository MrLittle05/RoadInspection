/**
 * @module oss_helper
 * @description 阿里云 OSS 辅助工具类
 * 主要负责与阿里云 STS 服务交互，签发临时上传凭证。
 */

import OSS from "ali-oss";
const { STS } = OSS;
import { aliyun } from "../config/config.js";

// 初始化 STS 客户端
// 这里使用的是拥有 AssumeRole 权限的 RAM 用户 (backend-server)
const client = new STS({
  accessKeyId: aliyun.accessKeyId,
  accessKeySecret: aliyun.accessKeySecret,
});

/**
 * 获取 OSS 临时上传凭证 (STS Token)
 * * 原理：
 * 后端使用高权限账号向阿里云申请一个临时的、权限受限的 Token。
 * Android 端拿到这个 Token 后，直接直传文件到 OSS，无需经过后端服务器中转。
 * * @returns {Promise<Object>} 包含 accessKeyId, accessKeySecret, stsToken 等信息的对象
 */
async function getStsToken() {
  // 定义权限策略
  // 这是一个 "最小权限原则" 的安全策略
  // 含义：只允许对指定 Bucket 下的所有资源执行 PutObject (上传) 操作
  // 禁止了 GetObject (查看) 和 DeleteObject (删除)
  const policy = {
    Statement: [
      {
        Action: ["oss:PutObject"],
        Effect: "Allow",
        Resource: [`acs:oss:*:*:${aliyun.bucket}/*`],
      },
    ],
    Version: "1",
  };

  try {
    // 调用 assumeRole 接口扮演角色
    const result = await client.assumeRole(
      aliyun.roleArn, // 调用者扮演哪个角色
      policy, // 限制该角色的权限
      aliyun.tokenExpireTime // 有效期
    );

    // 返回给前端的数据结构
    return {
      accessKeyId: result.credentials.AccessKeyId,
      accessKeySecret: result.credentials.AccessKeySecret,
      stsToken: result.credentials.SecurityToken, // 核心：临时令牌
      region: aliyun.region,
      bucket: aliyun.bucket,
    };
  } catch (e) {
    console.error("STS 签发失败 - 请检查 RAM 权限或 RoleArn 配置:", e);
    throw e;
  }
}

export { getStsToken };
