package com.example.roadinspection.data.source.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.example.roadinspection.data.source.remote.UserDto
import com.google.gson.Gson

/**
 * 安全会话管理器 (TokenManager)
 *
 * 职责：
 * 1. 持久化存储：利用 SharedPreferences 存储 Token 和用户信息。
 * 2. 内存缓存层：在内存中备份核心凭证，防止程序运行期间磁盘文件被意外删除导致任务中断。
 * 3. 自动登录支持：提供快速获取缓存用户信息的方法。
 */
object TokenManager {
    private const val TAG = "TokenManager"
    private const val PREF_NAME = "auth_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_INFO = "user_info"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    // --- 内存缓存层 (Memory Cache) ---
    // 使用 @Volatile 确保多线程环境下的可见性（例如 Interceptor 在子线程访问）
    @Volatile
    private var _accessToken: String? = null

    @Volatile
    private var _refreshToken: String? = null

    @Volatile
    private var _cachedUser: UserDto? = null

    /**
     * 初始化管理器。
     * 建议在 Application 类中调用，确保 App 启动时预加载磁盘数据到内存。
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        preloadToMemory()
    }

    /**
     * 从磁盘预加载数据到内存变量中。
     * 实现“内存容灾”的关键步骤，即使磁盘文件后续被删，内存引用依然有效。
     */
    private fun preloadToMemory() {
        _accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        _refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val userJson = prefs.getString(KEY_USER_INFO, null)
        _cachedUser = try {
            if (!userJson.isNullOrEmpty()) {
                gson.fromJson(userJson, UserDto::class.java)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "解析本地用户信息失败: ${e.message}")
            null
        }
    }

    /**
     * 获取或设置 AccessToken。
     * 读取时优先从内存获取，速度极快且防磁盘删除；写入时同步更新内存与磁盘。
     */
    var accessToken: String?
        get() = _accessToken ?: prefs.getString(KEY_ACCESS_TOKEN, null).also { _accessToken = it }
        set(value) {
            _accessToken = value
            prefs.edit { putString(KEY_ACCESS_TOKEN, value) }
        }

    /**
     * 获取或设置 RefreshToken。
     */
    var refreshToken: String?
        get() = _refreshToken ?: prefs.getString(KEY_REFRESH_TOKEN, null).also { _refreshToken = it }
        set(value) {
            _refreshToken = value
            prefs.edit { putString(KEY_REFRESH_TOKEN, value) }
        }

    val currentUserId: String? get() = getCachedUser()?.id

    /**
     * 保存完整的登录会话。
     * 通常在登录接口成功返回后调用。
     *
     * @param access 访问令牌
     * @param refresh 刷新令牌
     * @param user 用户信息实体
     */
    fun saveLoginSession(access: String, refresh: String, user: UserDto) {
        _accessToken = access
        _refreshToken = refresh
        _cachedUser = user

        prefs.edit {
            putString(KEY_ACCESS_TOKEN, access)
            putString(KEY_REFRESH_TOKEN, refresh)
            putString(KEY_USER_INFO, gson.toJson(user))
        }
    }

    /**
     * 仅更新 Token 对。
     * 通常在执行 Refresh Token 操作成功后调用，不覆盖已有的用户信息。
     */
    fun saveTokens(access: String, refresh: String) {
        _accessToken = access
        _refreshToken = refresh

        prefs.edit {
            putString(KEY_ACCESS_TOKEN, access)
            putString(KEY_REFRESH_TOKEN, refresh)
        }
    }

    /**
     * 获取当前缓存的用户信息。
     * 优先返回内存中的对象，如果内存为空则尝试从磁盘恢复。
     *
     * @return UserDto 或 null
     */
    fun getCachedUser(): UserDto? {
        if (_cachedUser != null) return _cachedUser

        val userJson = prefs.getString(KEY_USER_INFO, null)
        return if (!userJson.isNullOrEmpty()) {
            try {
                _cachedUser = gson.fromJson(userJson, UserDto::class.java)
                _cachedUser
            } catch (e: Exception) {
                Log.e(TAG, "从磁盘恢复用户信息失败: ${e.message}")
                null
            }
        } else null
    }

    /**
     * 判断当前是否处于已登录状态。
     * 依据是 RefreshToken 是否存在。
     */
    fun isUserLoggedIn(): Boolean {
        return !refreshToken.isNullOrEmpty()
    }

    /**
     * 清除所有会话数据。
     * 用于退出登录或 Token 彻底失效的场景。
     */
    fun clearTokens() {
        _accessToken = null
        _refreshToken = null
        _cachedUser = null
        // 使用 commit = true 确保在某些同步登出场景下数据立即抹除
        prefs.edit(commit = true) {
            clear()
        }
    }
}