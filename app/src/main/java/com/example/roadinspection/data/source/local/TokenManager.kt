package com.example.roadinspection.data.source.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.roadinspection.data.source.remote.UserDto // 确保引入了 UserDto
import com.google.gson.Gson

/**
 * Token 及用户会话管理器。
 *
 * **更新内容：**
 * 新增了用户基本信息的持久化存储，用于实现 App 启动时的自动登录。
 */
object TokenManager {
    private const val PREF_NAME = "auth_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_INFO = "user_info"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_ACCESS_TOKEN, value) }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_REFRESH_TOKEN, value) }

    /**
     * 保存登录会话（Token + 用户信息）
     * 登录成功时调用。
     */
    fun saveLoginSession(access: String, refresh: String, user: UserDto) {
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, access)
            putString(KEY_REFRESH_TOKEN, refresh)
            putString(KEY_USER_INFO, gson.toJson(user)) // 序列化存入
        }
    }

    /**
     * 保存双 Token (仅刷新 Token 时调用，不涉及用户信息变更)
     */
    fun saveTokens(access: String, refresh: String) {
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, access)
            putString(KEY_REFRESH_TOKEN, refresh)
        }
    }

    /**
     * 尝试获取本地缓存的用户信息
     * 用于 App 启动时的自动登录判断。
     *
     * @return 如果 RefreshToken 存在且用户信息存在，返回 UserDto；否则返回 null。
     */
    fun getCachedUser(): UserDto? {
        val refresh = refreshToken
        val userJson = prefs.getString(KEY_USER_INFO, null)

        if (!refresh.isNullOrEmpty() && !userJson.isNullOrEmpty()) {
            return try {
                gson.fromJson(userJson, UserDto::class.java)
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    /**
     * 清除所有会话信息。
     */
    fun clearTokens() {
        prefs.edit { clear() }
    }
}