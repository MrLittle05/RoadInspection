package com.example.roadinspection.util

import kotlin.math.sqrt

/**
 * 简单的卡尔曼滤波器实现，用于平滑 GPS 轨迹。
 * 它可以有效过滤掉 GPS 的随机噪声，使轨迹更连贯。
 */
class KalmanFilter(
    private var processNoise: Float = 3f // 过程噪声 (越小越相信预测模型，轨迹越平滑但滞后)
) {
    private var timeStampMs: Long = 0
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0

    // 估计协方差 (表示我们对自己当前估计值的"不确定程度")
    private var variance: Float = 0f

    /**
     * 处理新的 GPS 测量数据
     *
     * @param latMeasurement 当前测量的纬度
     * @param lngMeasurement 当前测量的经度
     * @param accuracy GPS 返回的精度半径 (米)
     * @param timestampMs 时间戳 (毫秒)
     * @param currentSpeed 当前速度 (米/秒)
     */
    fun process(
        latMeasurement: Double,
        lngMeasurement: Double,
        accuracy: Float,
        timestampMs: Long,
        currentSpeed: Float
    ) {
        if (this.timeStampMs == 0L) {
            // 初始化：如果是第一个点，直接信任它
            this.timeStampMs = timestampMs
            this.latitude = latMeasurement
            this.longitude = lngMeasurement
            this.variance = accuracy * accuracy
            return
        }

        // 1. 计算时间增量 (秒)
        val duration = (timestampMs - this.timeStampMs) / 1000.0f
        this.timeStampMs = timestampMs

        // 2. 预测阶段 (Predict)
        if (duration > 0) {
            // 这里模型很简单：假设物体静止或匀速，方差随时间增加
            // 如果速度很大，方差增加得更快 (过程噪声变大)
            val dynamicNoise = processNoise + (currentSpeed * duration) / 10.0f
            this.variance += duration * dynamicNoise * dynamicNoise
        }

        // 3. 更新阶段 (Update)
        // 测量噪声：直接使用 GPS 的 accuracy (精度)
        // 精度越高(accuracy越小)，测量噪声越小，我们越相信这次测量
        val measurementNoise = accuracy * accuracy

        // 卡尔曼增益 (K)：决定了我们要相信"预测值"还是"测量值"
        // K 越大，越相信测量值；K 越小，越相信预测值
        val k = this.variance / (this.variance + measurementNoise)

        // 更新经纬度
        this.latitude += k * (latMeasurement - this.latitude)
        this.longitude += k * (lngMeasurement - this.longitude)

        // 更新新的协方差
        this.variance = (1 - k) * this.variance
    }

    // 获取平滑后的纬度
    fun getLat(): Double = latitude

    // 获取平滑后的经度
    fun getLng(): Double = longitude

    // 重置滤波器 (例如重新开始巡检时)
    fun reset() {
        timeStampMs = 0L
        latitude = 0.0
        longitude = 0.0
        variance = 0f
    }
}