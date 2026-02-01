package com.example.roadinspection.di

import com.example.roadinspection.BuildConfig
import com.example.roadinspection.data.source.remote.AuthInterceptor
import com.example.roadinspection.data.source.remote.InspectionApiService
import com.example.roadinspection.data.source.remote.TokenAuthenticator
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 网络模块单例。
 * 提供经过配置的 Retrofit 和 OkHttpClient 实例。
 * 包含：日志拦截、超时设置、自动 Token 注入、自动 Token 刷新。
 */
object NetworkModule {
    private const val BASE_URL = BuildConfig.SERVER_URL

    // 配置 OkHttpClient
    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()

        // 1. 设置超时时间 (根据上传图片的需求适当调大)
        builder.connectTimeout(30, TimeUnit.SECONDS)
        builder.readTimeout(30, TimeUnit.SECONDS)
        builder.writeTimeout(60, TimeUnit.SECONDS) // 上传图片比较慢

        // 2. 添加 Auth 拦截器 (自动加 Access Token)
        builder.addInterceptor(AuthInterceptor())

        // 3. 添加 Authenticator (自动处理 401 刷新 Token)
        builder.authenticator(TokenAuthenticator())

        builder.build()
    }

    // 配置 API Service
    val api: InspectionApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(InspectionApiService::class.java)
    }
}