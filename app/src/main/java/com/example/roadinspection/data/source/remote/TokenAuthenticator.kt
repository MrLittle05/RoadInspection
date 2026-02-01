package com.example.roadinspection.data.source.remote

import android.util.Log
import com.example.roadinspection.BuildConfig
import com.example.roadinspection.data.source.local.TokenManager
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Token 自动刷新认证器 (兼容 OkHttp 3.x).
 *
 * **工作原理：**
 * 1. 当 OkHttp 收到 HTTP 401 Unauthorized 响应时，系统会自动调用 [authenticate] 方法。
 * 2. 此方法运行在非 UI 线程。
 * 3. 我们需要在此处**同步**请求后端刷新接口。
 * 4. 如果刷新成功，返回携带新 Token 的 Request，OkHttp 会自动重试。
 * 5. 如果刷新失败 (Refresh Token 也过期)，返回 null，请求终止，触发登出。
 */
class TokenAuthenticator : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        Log.i(TAG, "检测到 ${response.code()} 错误，准备自动刷新 Token...")

        val currentAccessToken = TokenManager.accessToken
        val currentRefreshToken = TokenManager.refreshToken

        // 1. 快速检查：如果本地没有 Token，没必要刷新，直接失败
        if (currentAccessToken == null || currentRefreshToken == null) {
            Log.w(TAG, "本地无 Token，放弃刷新")
            return null
        }

        // 2. 线程安全锁：防止多个并发请求同时触发 401 导致多次刷新
        synchronized(this) {
            // double-check：可能在等待锁的过程中，别的线程已经刷新好了
            val updatedAccessToken = TokenManager.accessToken
            if (updatedAccessToken != null && updatedAccessToken != currentAccessToken) {
                Log.i(TAG, "Token 已被其他线程刷新，直接重试")
                // [修改] 使用方法调用 response.request()
                return newRequestWithToken(response.request(), updatedAccessToken)
            }

            // 3. 构建刷新请求的 API 服务
            // 注意：这里必须创建一个新的、干净的 Retrofit/Client 实例，
            // 绝对不能复用 NetworkModule.api，否则会因为拦截器死循环。
            val refreshApi = createRefreshApi()

            try {
                // 4. 同步调用刷新接口 (.execute())
                // 注意：这里调用的 refreshToken 必须是返回 Call<T> 的方法，不能是 suspend
                val call = refreshApi.refreshToken(RefreshTokenReq(currentRefreshToken))
                val retrofitResponse = call.execute()

                if (retrofitResponse.isSuccessful && retrofitResponse.body()?.code == 200) {
                    val newData = retrofitResponse.body()?.data
                    if (newData != null) {
                        Log.i(TAG, "✅ Token 刷新成功")

                        // 5. 保存新 Token 到本地
                        TokenManager.saveTokens(newData.accessToken, newData.refreshToken)

                        // 6. 返回携带新 Token 的请求，OkHttp 会自动重试
                        // [修改] 使用方法调用 response.request()
                        return newRequestWithToken(response.request(), newData.accessToken)
                    }
                } else {
                    // [修改] Retrofit 的 response.code() 也是方法
                    Log.e(TAG, "❌ Refresh Token 接口调用失败或拒绝: ${retrofitResponse.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 刷新 Token 网络异常", e)
            }
        }

        // 7. 刷新彻底失败 (Refresh Token 过期或网络持续错误)
        // 执行强制登出逻辑
        handleLogout()
        return null // 返回 null 表示放弃重试，将 401 抛给上层业务
    }

    /**
     * 创建一个新的 Request，将 Authorization Header 替换为新 Token
     */
    private fun newRequestWithToken(request: Request, newToken: String): Request {
        return request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    /**
     * 强制登出处理。
     * 建议通过 EventBus/LiveData 通知 UI 跳转登录页。
     */
    private fun handleLogout() {
        Log.e(TAG, "Refresh Token 失效，强制登出")
        TokenManager.clearTokens()
        // TODO: 发送全局广播或事件，通知 MainActivity 跳转到登录页
        // EventBus.getDefault().post(LogoutEvent())
    }

    /**
     * 创建一个专门用于刷新的 API 实例。
     * 不包含 Authenticator 和 AuthInterceptor，避免死循环。
     */
    private fun createRefreshApi(): InspectionApiService {
        val client = OkHttpClient.Builder().build() // 纯净的 Client
        return Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(InspectionApiService::class.java)
    }

    companion object {
        private const val TAG = "TokenAuthenticator"
    }
}