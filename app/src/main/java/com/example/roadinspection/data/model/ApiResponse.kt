package com.example.roadinspection.data.model

/**
 * 统一的 API 响应包装类。
 * 用于规范 Native 与 Web 及其它层级的数据交互格式。
 *
 * @param T 数据载体的泛型类型
 */
data class ApiResponse<T>(
    val code: Int,      // 状态码：200=成功, 500=错误
    val msg: String,    // 提示信息："success" 或 具体的错误堆栈/提示
    val data: T?        // 实际业务数据
) {
    companion object {
        /**
         * 构建成功响应
         */
        fun <T> success(data: T): ApiResponse<T> {
            return ApiResponse(200, "success", data)
        }

        /**
         * 构建失败响应
         */
        fun <T> error(msg: String, data: T? = null): ApiResponse<T> {
            return ApiResponse(500, msg, data)
        }
    }
}