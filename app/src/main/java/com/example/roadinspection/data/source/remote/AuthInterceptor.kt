package com.example.roadinspection.data.source.remote

import com.example.roadinspection.data.source.local.TokenManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 认证拦截器 (兼容 OkHttp 3.x).
 *
 * **职责：**
 * 自动将本地存储的 Access Token 添加到每个 HTTP 请求的 Header 中。
 * 格式: Authorization: Bearer <token>
 */
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // 【关键修改】 OkHttp 3.x 必须使用方法调用 url() 和 encodedPath()
        val path = originalRequest.url().encodedPath()

        // 白名单判断：如果是刷新 Token 的请求，本身就不需要带 Access Token，直接放行
        if (path.contains("/api/auth/refresh") ||
            path.contains("/api/auth/login") ||
            path.contains("/api/auth/register")) {
            return chain.proceed(originalRequest)
        }

        val token = TokenManager.accessToken

        return if (token.isNullOrEmpty()) {
            chain.proceed(originalRequest)
        } else {
            // 重新构建请求，添加 Header
            val newRequest = originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            chain.proceed(newRequest)
        }
    }
}