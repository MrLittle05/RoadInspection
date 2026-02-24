package com.example.roadinspection.data.source.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.roadinspection.BuildConfig
import com.example.roadinspection.data.source.local.TokenManager
import com.google.gson.Gson
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.greenrobot.eventbus.EventBus
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Token 自动刷新认证器.
 *
 * @param context Application Context，用于在网络层直接弹出系统 Toast
 */
class TokenAuthenticator(private val context: Context) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        Log.i(TAG, "检测到 ${response.code()} 错误，准备自动刷新 Token...")

        val currentAccessToken = TokenManager.accessToken
        val currentRefreshToken = TokenManager.refreshToken

        // 1. 本地无 Token，直接放弃，不触发后续逻辑
        if (currentAccessToken == null || currentRefreshToken == null) {
            return null
        }

        synchronized(this) {
            // Double Check: 防止多线程并发刷新
            val updatedAccessToken = TokenManager.accessToken
            if (updatedAccessToken != null && updatedAccessToken != currentAccessToken) {
                return newRequestWithToken(response.request(), updatedAccessToken)
            }

            // 创建独立的 API 实例（避免拦截器死循环）
            val refreshApi = createRefreshApi()

            try {
                // 同步请求刷新
                val call = refreshApi.refreshToken(RefreshTokenReq(currentRefreshToken))
                val retrofitResponse = call.execute()

                // === 场景 A: 刷新成功 (200) ===
                if (retrofitResponse.isSuccessful && retrofitResponse.body()?.code == 200) {
                    val newData = retrofitResponse.body()?.data
                    if (newData != null) {
                        TokenManager.saveTokens(newData.accessToken, newData.refreshToken)
                        return newRequestWithToken(response.request(), newData.accessToken)
                    }
                }

                // === 场景 B: 凭证彻底失效 (403) ===
                // 此时需强制登出，通知 UI 层调用 JS 重定向
                else if (retrofitResponse.code() == 403) {
                    Log.w(TAG, "Refresh Token 失效 (403)，执行强制登出")
                    TokenManager.clearTokens()

                    // 使用 EventBus 通知当前活动的 Activity (MainActivity 或 InspectionActivity)
                    EventBus.getDefault().post(AuthEvent.ForceLogout("登录凭证已过期，请重新登录"))

                    return null // 停止重试
                }

                // === 场景 C: 服务器异常 (500/502/503) ===
                // 仅提示用户，不登出，保留本地 Token 等待服务器恢复
                else {
                    Log.e(TAG, "服务端异常: ${retrofitResponse.code()}")
                    showSystemToast("服务器繁忙 (${retrofitResponse.code()})，请稍后重试")
                    return null
                }

            } catch (e: Exception) {
                // === 场景 D: 网络层异常 (超时/断网) ===
                // 仅提示用户检查网络
                Log.e(TAG, "刷新 Token 网络异常", e)
                if (e is java.io.IOException) {
                    showSystemToast("网络连接不稳定，请检查网络")
                }
                return null
            }
        }
        return null
    }

    /**
     * 辅助方法：在主线程显示原生 Toast
     * 解决了在后台网络线程无法操作 UI 的问题
     */
    private fun showSystemToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun newRequestWithToken(request: Request, newToken: String): Request {
        return request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private fun createRefreshApi(): InspectionApiService {
        val client = OkHttpClient.Builder().build()
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

    // 事件定义
    sealed class AuthEvent {
        data class ForceLogout(val message: String) : AuthEvent()
    }
}