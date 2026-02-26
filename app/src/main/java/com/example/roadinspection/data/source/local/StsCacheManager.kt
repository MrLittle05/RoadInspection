package com.example.roadinspection.data.source.local

import com.example.roadinspection.data.source.remote.StsCredentials

object StsCacheManager {
    var accessKeyId: String? = null
    var accessKeySecret: String? = null
    var securityToken: String? = null
    // 如果你的 StsCredentials 还包含 region 和 bucket，也把它们加上
    var region: String? = null
    var bucket: String? = null

    var expirationTimeMillis: Long = 0

    // 检查缓存的 Token 是否仍然有效 (提前 5 分钟认为过期)
    fun isValid(): Boolean {
        if (accessKeyId == null || securityToken == null || region == null || bucket == null) return false
        val bufferTime = 5 * 60 * 1000L // 5分钟缓冲
        return System.currentTimeMillis() < (expirationTimeMillis - bufferTime)
    }

    // 保存凭证
    fun save(credentials: StsCredentials, expireInSeconds: Long) {
        this.accessKeyId = credentials.accessKeyId
        this.accessKeySecret = credentials.accessKeySecret
        this.securityToken = credentials.stsToken
        this.region = credentials.region
        this.bucket = credentials.bucket

        this.expirationTimeMillis = System.currentTimeMillis() + (expireInSeconds * 1000L)
    }

    // 获取组装好的凭证对象
    fun getCredentials(): StsCredentials? {
        if (!isValid()) return null

        return StsCredentials(
            accessKeyId = this.accessKeyId!!,
            accessKeySecret = this.accessKeySecret!!,
            stsToken = this.securityToken!!,
            region = this.region!!,
            bucket = this.bucket!!
        )
    }
}