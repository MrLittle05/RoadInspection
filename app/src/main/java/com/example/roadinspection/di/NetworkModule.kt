package com.example.roadinspection.di

import com.example.roadinspection.data.source.remote.InspectionApiService
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.example.roadinspection.BuildConfig

/**
 * 简单的网络模块单例，用于提供 Retrofit 服务实例。
 * (在实际项目中，通常使用 Hilt/Dagger 注入，这里使用 Object 单例简化实现)
 */
object NetworkModule {
    private const val BASE_URL = BuildConfig.SERVER_URL

    val api: InspectionApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(InspectionApiService::class.java)
    }
}