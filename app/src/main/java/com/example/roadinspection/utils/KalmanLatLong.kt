package com.example.roadinspection.utils

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import java.lang.Math.toRadians

/**
 * 道路巡检专用 GPS 平滑滤波器 (Production Ready)
 *
 * 专业优化点:
 * 1. 动态Q值：根据速度自动切换滤波模式（静止/城市/高速）
 * 2. 参考点自动迁移：每10km迁移参考点，消除投影误差
 * 3. 亚米级精度支持：方差下限0.1，适配RTK/双频GPS
 * 4. 企业级鲁棒性：处理所有GPS异常场景
 *
 * @param baseQ_metres_per_second 基础过程噪声 (m/s)
 *        - 建议值: 3.0f (普通手机), 1.5f (RTK设备)
 * @param maxVariance 方差上限 (m²)，防止信号丢失恢复时跳变
 *        - 建议值: 2500f (50m精度), 400f (20m精度)
 */
class KalmanLatLong(
    private val baseQ_metres_per_second: Float = 3.0f,
    private val maxVariance: Float = 2500f,
    private val minVariance: Float = 0.1f // 0.3米^2，支持亚米级设备
) {
    // 滤波状态 (局部ENU坐标系)
    private var x: Double = 0.0  // 东向 (米)
    private var y: Double = 0.0  // 北向 (米)
    private var lastX: Double = 0.0 // 用于位移计算
    private var lastY: Double = 0.0
    private var variance: Float = -1f

    // 地理参考点
    private var refLat: Double = 0.0
    private var refLng: Double = 0.0
    private var totalDistance: Double = 0.0 // 累计位移(米)

    // 时序控制
    private var lastTimestampMs: Long = 0
    private var isInitialized = false

    /**
     * 处理GPS定位点 (道路巡检优化版)
     *
     * @param latMeasurement 纬度 (十进制度)
     * @param lngMeasurement 经度 (十进制度)
     * @param accuracy 精度半径 (米)，来自Location.getAccuracy()
     * @param timestampMs 时间戳 (毫秒)，使用SystemClock.elapsedRealtime()
     * @param currentSpeed 速度 (m/s)，0=静止, -1=未知。来自Location.getSpeed()
     */
    fun process(
        latMeasurement: Double,
        lngMeasurement: Double,
        accuracy: Float,
        timestampMs: Long,
        currentSpeed: Float = -1f
    ) {
        // 1. 严格数据清洗
        if (accuracy <= 0 || accuracy.isNaN()) return
        if (timestampMs < lastTimestampMs) return // 丢弃乱序点

        // 2. 有效精度范围
        val validAccuracy = when {
            accuracy > 200f -> 200f
            accuracy < 1f -> 1f
            else -> accuracy
        }

        // 3. 首次初始化
        if (!isInitialized) {
            initializeFirstPoint(latMeasurement, lngMeasurement, validAccuracy, timestampMs)
            return
        }

        // [新增] 4. 时间断层检测 (Time Gap Check)
        // 如果两次定位间隔超过 10 秒（说明熄屏了或信号丢失了），
        // 此时预测模型失效，强行进行预测会导致方差爆炸。
        // 策略：视为重新初始化，以当前点作为新的起点。
        val timeDeltaSec = (timestampMs - lastTimestampMs) / 1000.0
        if (timeDeltaSec > 10.0) {
            // 重新初始化，切断与旧轨迹的联系，防止“瞬移”
            initializeFirstPoint(latMeasurement, lngMeasurement, validAccuracy, timestampMs)
            return
        }

        // 5. 智能动态Q
        val dynamicQ = calculateDynamicQ(currentSpeed)

        // 6. 坐标转换
        val (xMeas, yMeas) = toLocalCoordinates(latMeasurement, lngMeasurement)
        if (xMeas.isNaN() || yMeas.isNaN()) return

        // 7. 预测步骤
        predict(timeDeltaSec, dynamicQ)

        // 8. 更新步骤
        update(xMeas, yMeas, validAccuracy)

        // 9. 参考点自动迁移
        if (totalDistance > 10000) migrateReferencePoint()

        // 10. 更新状态
        lastTimestampMs = timestampMs
        lastX = x
        lastY = y
    }

    fun getLat(): Double = if (isInitialized) {
        refLat + (y / METERS_PER_DEGREE_LAT)
    } else refLat

    fun getLng(): Double = if (isInitialized) {
        refLng + (x / (METERS_PER_DEGREE_LNG * cos(toRadians(refLat))))
    } else refLng

    fun reset() {
        isInitialized = false
        variance = -1f
        // 修复点: 拆分链式赋值 (Kotlin不支持)
        x = 0.0
        y = 0.0
        lastX = 0.0
        lastY = 0.0
        totalDistance = 0.0
    }

    // =============== 核心算法 ===============

    private fun calculateDynamicQ(speed: Float): Float {
        return when {
            speed < 0f -> baseQ_metres_per_second // 速度未知
            speed < 1.0f -> baseQ_metres_per_second * 0.3f // 静止/蠕行
            speed < 8.0f -> baseQ_metres_per_second // 城市道路(<28.8km/h)
            speed < 15.0f -> baseQ_metres_per_second * 1.2f // 快速路(<54km/h)
            else -> baseQ_metres_per_second * 1.8f // 高速(>54km/h)
        }
    }

    private fun initializeFirstPoint(
        lat: Double,
        lng: Double,
        accuracy: Float,
        timestamp: Long
    ) {
        refLat = lat
        refLng = lng
        lastTimestampMs = timestamp
        // 修复点: 拆分链式赋值
        x = 0.0
        y = 0.0
        variance = accuracy * accuracy
        isInitialized = true
        totalDistance = 0.0
    }

    private fun toLocalCoordinates(lat: Double, lng: Double): Pair<Double, Double> {
        // 北向 (纬度)
        val deltaLat = lat - refLat
        val y = deltaLat * METERS_PER_DEGREE_LAT

        // 东向 (经度，动态缩放)
        val deltaLng = lng - refLng
        // 修复点: 使用 Java Math.toRadians
        val metersPerDegreeLng = METERS_PER_DEGREE_LNG * cos(toRadians(refLat))
        val x = deltaLng * metersPerDegreeLng

        return x to y
    }

    private fun predict(timeDeltaSec: Double, currentQ: Float) {
        if (timeDeltaSec <= 0) return

        // 优化: 避免pow()调用
        val dist = currentQ * timeDeltaSec
        val processNoise = (dist * dist).toFloat()

        variance = minOf(variance + processNoise, maxVariance)
    }

    private fun update(xMeas: Double, yMeas: Double, accuracy: Float) {
        val measurementNoise = accuracy * accuracy
        var kalmanGain = variance / (variance + measurementNoise)

        // 防御NaN (极端情况)
        if (kalmanGain.isNaN() || kalmanGain !in 0f..1f) kalmanGain = 0.5f

        // 状态更新
        x += kalmanGain * (xMeas - x)
        y += kalmanGain * (yMeas - y)

        // 方差更新 + 边界保护
        variance = maxOf((1 - kalmanGain) * variance, minVariance)

        // 累计位移 (用于参考点迁移)
        val dx = x - lastX
        val dy = y - lastY
        totalDistance += sqrt(dx * dx + dy * dy)
    }

    /**
     * 自动迁移参考点 (消除地球曲率误差)
     * 保持滤波状态连续性，仅重置坐标原点
     */
    private fun migrateReferencePoint() {
        // 1. 计算当前绝对位置
        val currentLat = refLat + (y / METERS_PER_DEGREE_LAT)
        // 修复点: 使用 Java Math.toRadians
        val currentLng = refLng + (x / (METERS_PER_DEGREE_LNG * cos(toRadians(refLat))))

        // 2. 设置新参考点
        refLat = currentLat
        refLng = currentLng

        // 3. 重置局部坐标 (关键!)
        // 修复点: 拆分链式赋值
        x = 0.0
        y = 0.0
        lastX = 0.0
        lastY = 0.0

        // 4. 保持方差不变 (维持不确定性状态)
        totalDistance = 0.0
    }

    companion object {
        // WGS84椭球体标准参数
        private const val METERS_PER_DEGREE_LAT = 110540.0 // 北向(恒定)
        private const val METERS_PER_DEGREE_LNG = 111320.0 // 赤道东向
    }
}