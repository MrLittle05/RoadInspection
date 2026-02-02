package com.example.roadinspection.data.model

data class VersionInfo(
    val versionCode: Int,     // 例如: 2
    val versionName: String,  // 例如: "1.1.0"
    val downloadUrl: String,  // 例如: "https://your-company.com/app/road_inspection_v2.apk"
    val forceUpdate: Boolean, // 是否强制更新
    val description: String   // 更新日志
)
