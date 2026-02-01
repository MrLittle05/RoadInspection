import { NativeApiResponse, User, AuthData } from "../types";

const API_BASE_URL =
  window.AndroidNative.getApiBaseUrl() || "http://localhost:3000";

// 定义标准响应结构
interface ApiResponse<T> {
  code: number;
  message: string;
  data?: T;
}

/**
 * 核心请求封装：包含超时控制和错误标准化
 * @param endpoint 接口路径
 * @param options fetch配置
 * @param timeout 超时时间 (默认 10秒)
 */
async function fetchWithTimeout(
  endpoint: string,
  options: RequestInit = {},
  timeout = 10000,
): Promise<any> {
  const controller = new AbortController();
  const id = setTimeout(() => controller.abort(), timeout);

  const url = `${API_BASE_URL}${endpoint}`;

  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal, // 绑定信号
      headers: {
        "Content-Type": "application/json",
        ...options.headers,
      },
    });

    clearTimeout(id); // 请求成功，清除定时器

    // 解析 JSON
    const data = await response.json();

    // 这里虽然网络通了，但需要处理 HTTP 状态码非 200 的情况
    // 你的后端约定：只有 code 200 是成功，其他都是业务/服务器错误
    // 这一步直接返回后端数据，由 Service 层处理具体的 code 逻辑
    return data;
  } catch (error: any) {
    clearTimeout(id);

    // 区分错误类型
    if (error.name === "AbortError") {
      // 这里的 Error 会被下方的 catch 捕获
      throw new Error("服务器响应超时，请检查网络或稍后重试");
    } else if (error instanceof TypeError) {
      // TypeError 通常意味着网络断开或无法连接到服务器 (Connection Refused)
      throw new Error("无法连接到服务器，请检查网络设置");
    } else {
      throw error;
    }
  }
}

export const authService = {
  /**
   * 登录接口
   * @route POST /api/auth/login
   */
  login: async (
    username: string,
    password: string,
  ): Promise<ApiResponse<AuthData>> => {
    try {
      const res = await fetchWithTimeout("/api/auth/login", {
        method: "POST",
        body: JSON.stringify({ username, password }),
      });

      // 透传后端返回的结构
      return res;
    } catch (error: any) {
      // 捕获 fetchWithTimeout 抛出的网络/超时错误
      return { code: 500, message: error.message };
    }
  },

  /**
   * 注册接口
   * @route POST /api/auth/register
   */
  register: async (
    username: string,
    password: string,
  ): Promise<ApiResponse<AuthData>> => {
    try {
      const res = await fetchWithTimeout("/api/auth/register", {
        method: "POST",
        body: JSON.stringify({ username, password, role: "inspector" }),
      });
      return res;
    } catch (error: any) {
      return { code: 500, message: error.message };
    }
  },
};
