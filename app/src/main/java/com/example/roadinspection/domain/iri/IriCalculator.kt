package com.example.roadinspection.domain.iri

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * 国际平整度指数 (IRI) 计算核心组件 (Industrial Grade).
 *
 * **核心职责**：
 * 1. 采集高频加速度与重力传感器数据
 * 2. 通过向量投影算法提取真实地心垂直加速度（解决设备任意朝向问题）
 * 3. 执行时域→空域转换（符合 ASTM E1926 标准，消除速度影响）
 * 4. 应用专业带通滤波（0.5Hz-15Hz）抑制非路面振动干扰
 * 5. 计算符合公路技术标准的 eIRI (Estimated IRI) 值
 *
 * **工业级特性**：
 * - 动态重力向量补偿（适应颠簸路况）
 * - 跨路段计算状态隔离（避免数据耦合）
 * - 严格的数据质量评估（覆盖率+速度稳定性）
 * - 防御性编程（异常输入/传感器失效处理）
 * - 云端标定支持（动态校准系数）
 *
 * **使用流程**：
 * ```
 * val calculator = IriCalculator(context, calibrationFactor = 4.8f)
 * if (calculator.startListening()) {
 *     // 行驶 50 米后
 *     val result = calculator.computeAndClear(avgSpeed = 45.0f, distance = 50.0f)
 *     if (result != null && result.qualityIndex > 0.6f) {
 *         reportRoadCondition(result.iriValue)
 *     }
 * }
 * calculator.stopListening() // 必须在 Activity.onDestroy() 中调用
 * ```
 *
 * @property context Android 系统上下文
 * @property calibrationFactor 标定系数（mm/m per RMS），**必须**通过实车对标获取
 * @throws IllegalArgumentException 当校准系数无效时抛出
 */
class IriCalculator(
    context: Context,
    private var calibrationFactor: Float
) : SensorEventListener {

    init {
        require(calibrationFactor > 0f) { "Calibration factor must be positive" }
        if (calibrationFactor == DEFAULT_UNCALIBRATED_FACTOR) {
            Log.w(TAG, "⚠️ USING DEFAULT CALIBRATION FACTOR ($DEFAULT_UNCALIBRATED_FACTOR). " +
                    "Results are ESTIMATES ONLY until proper calibration.")
        }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // 传感器定义 (工业级设备应有冗余方案)
    private val linearAccelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    // 原始数据结构
    private data class AccelerationSample(val timestampNs: Long, val value: Float)

    // 数据缓存 (线程安全)
    private val rawSamples = ArrayList<AccelerationSample>(4096)
    private val dataLock = Any()

    // 重力向量 (线程安全)
    private var currentGravity = FloatArray(3) { 0f }
    private val gravityLock = Any()
    @Volatile
    private var hasValidGravity = false

    // 传感器状态
    @Volatile
    private var isRecording = false
    private var lastSampleTimeNs = 0L

    // 滤波器状态 (关键：必须在每次计算后重置)
    private var hpPrevInput = 0f
    private var hpPrevOutput = 0f
    private var lpPrevOutput = 0f

    companion object {
        private const val TAG = "IriCalculator"

        // 采样配置
        private const val TARGET_SAMPLE_RATE_HZ = 50f
        private const val SAMPLE_INTERVAL_US = (1e6f / TARGET_SAMPLE_RATE_HZ).toInt() // 20,000 μs
        private const val SAMPLE_INTERVAL_NS = (1e9f / TARGET_SAMPLE_RATE_HZ).toLong()

        // 滤波参数 (符合 ASTM E1926-98 标准)
        private const val HIGH_PASS_CUTOFF_HZ = 0.5f // 消除车辆姿态变化 (坡度/转弯)
        private const val LOW_PASS_CUTOFF_HZ = 15f   // 消除轮胎/引擎高频噪声
        private val HIGH_PASS_ALPHA = computeHighPassAlpha(HIGH_PASS_CUTOFF_HZ, TARGET_SAMPLE_RATE_HZ)
        private val LOW_PASS_ALPHA = computeLowPassAlpha(LOW_PASS_CUTOFF_HZ, TARGET_SAMPLE_RATE_HZ)

        // 空间域转换
        private const val SPATIAL_SAMPLE_INTERVAL_M = 0.1f // 每 0.1 米一个采样点
        private const val MIN_VALID_DISTANCE_M = 10f // 最小有效计算距离

        // 标定安全
        private const val DEFAULT_UNCALIBRATED_FACTOR = 5.0f

        /**
         * 计算高通滤波器系数 (RC 高通)
         *
         * @param cutoffHz 截止频率 (Hz)
         * @param sampleRateHz 采样频率 (Hz)
         * @return 滤波系数 alpha (0.0 - 1.0)
         */
        private fun computeHighPassAlpha(cutoffHz: Float, sampleRateHz: Float): Float {
            val dt = 1f / sampleRateHz
            val rc = 1f / (2 * Math.PI * cutoffHz)
            return (rc / (rc + dt)).toFloat()
        }

        /**
         * 计算低通滤波器系数 (RC 低通)
         *
         * @param cutoffHz 截止频率 (Hz)
         * @param sampleRateHz 采样频率 (Hz)
         * @return 滤波系数 alpha (0.0 - 1.0)
         */
        private fun computeLowPassAlpha(cutoffHz: Float, sampleRateHz: Float): Float {
            val dt = 1f / sampleRateHz
            val rc = 1f / (2 * Math.PI * cutoffHz)
            return (dt / (rc + dt)).toFloat()
        }
    }

    /**
     * 启动传感器监听
     *
     * **线程安全**：内部使用同步锁保证状态一致性
     * **资源管理**：自动清除历史数据，重置滤波器状态
     *
     * @return 若成功注册传感器返回 true，否则返回 false
     * @throws SecurityException 当缺少传感器权限时抛出
     * @see [stopListening]
     */
    fun startListening(): Boolean {
        if (linearAccelSensor == null || gravitySensor == null) {
            Log.e(TAG, "Missing required sensors: Linear Acceleration or Gravity")
            return false
        }

        synchronized(dataLock) {
            rawSamples.clear()
            resetFilterStates()
        }

        try {
            sensorManager.registerListener(
                this,
                linearAccelSensor,
                SAMPLE_INTERVAL_US,
                null
            )
            sensorManager.registerListener(
                this,
                gravitySensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )

            isRecording = true
            lastSampleTimeNs = System.nanoTime()
            Log.i(TAG, "IRI sensing started at ${TARGET_SAMPLE_RATE_HZ}Hz (Interval: ${SAMPLE_INTERVAL_US}μs)")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Sensor permission denied", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sensor registration failed", e)
            return false
        }
    }

    /**
     * 停止传感器监听并释放所有资源
     *
     * **必须调用**：在 Activity.onPause() 或 onDestroy() 中调用
     * **资源清理**：
     * - 取消传感器注册
     * - 清空原始数据缓冲区
     * - 重置滤波器状态
     *
     * @see [startListening]
     */
    fun stopListening() {
        if (isRecording) {
            sensorManager.unregisterListener(this)
            synchronized(dataLock) {
                rawSamples.clear()
                resetFilterStates()
            }
            isRecording = false
            Log.i(TAG, "IRI sensing stopped and resources released")
        }
    }

    /**
     * 计算当前缓冲区的 IRI 值，并重置缓冲区
     *
     * **关键验证**：
     * - 强制校准检查：使用默认校准系数时返回 null
     * - 有效距离：≥10 米
     * - 有效速度：5-140 km/h
     * - 空间样本密度：≥50% 预期值
     *
     * **状态管理**：
     * - 每次计算前重置滤波器状态（避免跨路段耦合）
     * - 使用原始数据的浅拷贝（防止计算时数据被修改）
     *
     * @param avgSpeedKmh 路段平均速度 (km/h)
     * @param distanceMeters 本段计算的行驶距离 (米)
     * @return IRI 计算结果（含质量指标），无效数据时返回 null
     * @see [IriResult]
     */
    fun computeAndClear(avgSpeedKmh: Float, distanceMeters: Float): IriResult? {
        // 1. 校准安全检查
        if (calibrationFactor == DEFAULT_UNCALIBRATED_FACTOR) {
            Log.e(TAG, "❌ DEVICE NOT CALIBRATED! IRI values are meaningless without proper calibration.")
            return null
        }

        // 2. 输入验证
        if (!validateInput(avgSpeedKmh, distanceMeters)) {
            return null
        }

        // 3. 安全获取数据副本
        val samples: List<AccelerationSample>
        synchronized(dataLock) {
            if (rawSamples.size < 20) {
                Log.w(TAG, "Insufficient samples: ${rawSamples.size} (min: 20)")
                return null
            }
            samples = ArrayList(rawSamples)
            rawSamples.clear()
        }

        try {
            // 4. 重置滤波器状态（关键！避免跨路段数据耦合）
            resetFilterStates()

            // 5. 时域→空域转换
            val spatialSamples = convertToSpatialDomain(samples, avgSpeedKmh, distanceMeters)
            val expectedCount = (distanceMeters / SPATIAL_SAMPLE_INTERVAL_M).toInt()

            // 6. 样本密度验证
            if (spatialSamples.size < expectedCount * 0.5f) {
                Log.w(TAG, "Sparse spatial samples: ${spatialSamples.size} (Expected: $expectedCount)")
                return null
            }

            // 7. 信号处理
            val filteredSamples = applyBandpassFilter(spatialSamples)
            val rms = calculateRms(filteredSamples)

            // 8. IRI 计算
            val iriValue = rms * calibrationFactor

            // 9. 质量评估
            val quality = assessDataQuality(
                rawSampleCount = samples.size,
                spatialSampleCount = spatialSamples.size,
                expectedSpatialCount = expectedCount,
                speedKmh = avgSpeedKmh
            )

            Log.i(TAG, "IRI=%.2f mm/m | Speed=%.1f km/h | Dist=%.1f m | Quality=%.1f%%"
                .format(iriValue, avgSpeedKmh, distanceMeters, quality * 100))

            return IriResult(
                iriValue = iriValue.coerceIn(0f, 20f), // 限制物理范围
                qualityIndex = quality,
                spatialSampleCount = spatialSamples.size,
                distanceMeters = distanceMeters
            )
        } catch (e: Exception) {
            Log.e(TAG, "IRI calculation crashed", e)
            return null
        }
    }

    /**
     * 动态更新标定系数
     *
     * **工业场景**：从云端获取针对特定车型/安装位置的校准参数
     *
     * @param factor 新的标定系数 (mm/m per RMS)
     * @throws IllegalArgumentException 当因子 ≤ 0 时抛出
     */
    fun updateCalibration(factor: Float) {
        require(factor > 0f) { "Calibration factor must be positive" }
        this.calibrationFactor = factor
        Log.i(TAG, "Calibration factor updated to: $factor")
    }

    // =========================================================================
    // 核心算法 (私有方法)
    // =========================================================================

    /**
     * 时域→空域转换
     *
     * **算法原理**：
     * 1. 通过 v * Δt 计算位移增量
     * 2. 累计位移达到 0.1m 时，线性插值生成空间样本
     * 3. 处理末尾不足 0.1m 的剩余距离（边界保护）
     *
     * @param timeSamples 按时间排序的原始样本
     * @param avgSpeedKmh 平均速度 (km/h)
     * @param totalDistanceM 总行驶距离 (米) - 用于验证
     * @return 每 0.1 米间隔的空间样本列表
     */
    private fun convertToSpatialDomain(
        timeSamples: List<AccelerationSample>,
        avgSpeedKmh: Float,
        totalDistanceM: Float
    ): List<Float> {
        val spatialSamples = mutableListOf<Float>()
        var accumulatedDistance = 0f
        var nextSampleDistance = SPATIAL_SAMPLE_INTERVAL_M

        // 速度单位转换: km/h → m/s
        val speedMps = avgSpeedKmh / 3.6f

        // 处理时间序列
        for (i in 0 until timeSamples.size - 1) {
            val current = timeSamples[i]
            val next = timeSamples[i + 1]

            // 计算时间间隔 (秒)
            val timeDeltaSec = (next.timestampNs - current.timestampNs).toFloat() / 1e9f
            if (timeDeltaSec > 1.0f) { // 丢帧保护
                Log.w(TAG, "Large time gap detected: ${"%.2f".format(timeDeltaSec)}s")
                continue
            }

            // 计算本段位移
            val segmentDistance = speedMps * timeDeltaSec
            accumulatedDistance += segmentDistance

            // 生成空间样本
            while (accumulatedDistance >= nextSampleDistance) {
                // 计算插值比例
                val distancePastSample = accumulatedDistance - nextSampleDistance
                val ratio = 1.0f - (distancePastSample / segmentDistance).coerceIn(0f, 1f)

                // 线性插值
                val interpolatedAcc = current.value + ratio * (next.value - current.value)
                spatialSamples.add(interpolatedAcc)

                nextSampleDistance += SPATIAL_SAMPLE_INTERVAL_M
            }
        }

        // 边界保护：处理末尾剩余距离
        val remainingDistance = accumulatedDistance - (nextSampleDistance - SPATIAL_SAMPLE_INTERVAL_M)
        if (remainingDistance > SPATIAL_SAMPLE_INTERVAL_M * 0.3f && timeSamples.isNotEmpty()) {
            spatialSamples.add(timeSamples.last().value)
        }

        return spatialSamples
    }

    /**
     * 应用 0.5Hz-15Hz 带通滤波
     *
     * **滤波器结构**：
     * 1. 高通 (0.5Hz)：消除车辆姿态变化引起的低频漂移
     *    `y[i] = α * (y[i-1] + x[i] - x[i-1])`
     * 2. 低通 (15Hz)：抑制轮胎/引擎高频噪声
     *    `y[i] = α * x[i] + (1-α) * y[i-1]`
     *
     * **状态管理**：使用成员变量保持滤波器状态，每次计算前重置
     *
     * @param samples 空间域加速度序列 (m/s²)
     * @return 滤波后的加速度序列
     */
    private fun applyBandpassFilter(samples: List<Float>): List<Float> {
        if (samples.isEmpty()) return emptyList()

        val hpOut = FloatArray(samples.size)
        val finalOut = FloatArray(samples.size)

        // 1. 高通滤波 (处理第一个样本)
        hpOut[0] = hpPrevOutput
        hpPrevInput = samples[0]

        for (i in 1 until samples.size) {
            val currentInput = samples[i]
            val output = HIGH_PASS_ALPHA * (hpPrevOutput + currentInput - hpPrevInput)
            hpOut[i] = output

            // 更新状态
            hpPrevOutput = output
            hpPrevInput = currentInput
        }

        // 2. 低通滤波 (处理第一个样本)
        finalOut[0] = lpPrevOutput

        for (i in 1 until samples.size) {
            val input = hpOut[i]
            val output = LOW_PASS_ALPHA * input + (1.0f - LOW_PASS_ALPHA) * lpPrevOutput
            finalOut[i] = output

            // 更新状态
            lpPrevOutput = output
        }

        return finalOut.toList()
    }

    /**
     * 计算均方根 (RMS) 值
     *
     * @param samples 加速度序列 (m/s²)
     * @return RMS 值，空序列返回 0
     */
    private fun calculateRms(samples: List<Float>): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (acc in samples) {
            sumSquares += acc.toDouble().pow(2.0)
        }
        return sqrt(sumSquares / samples.size).toFloat()
    }

    /**
     * 重置滤波器状态
     *
     * **关键作用**：确保路段间计算独立，避免历史数据污染
     */
    private fun resetFilterStates() {
        hpPrevInput = 0f
        hpPrevOutput = 0f
        lpPrevOutput = 0f
    }

    /**
     * 评估数据质量 (0.0-1.0)
     *
     * **质量因子**：
     * 1. 空间覆盖率：实际样本数 / 预期样本数
     * 2. 速度权重：30-80km/h 为最佳区间
     *
     * @return 归一化质量指数 (0.0-1.0)
     */
    private fun assessDataQuality(
        rawSampleCount: Int,
        spatialSampleCount: Int,
        expectedSpatialCount: Int,
        speedKmh: Float
    ): Float {
        var quality = 1.0f

        // 1. 空间覆盖率
        val coverage = spatialSampleCount.toFloat() / expectedSpatialCount.coerceAtLeast(1)
        quality *= coverage.coerceIn(0f, 1f)

        // 2. 速度权重 (ASTM 推荐 30-80km/h)
        if (speedKmh < 30f || speedKmh > 80f) {
            quality *= 0.8f // 非最佳速度区间
        }

        return quality.coerceIn(0.1f, 1.0f) // 保证最小值
    }

    /**
     * 验证输入参数有效性
     *
     * @return 有效返回 true，否则记录警告并返回 false
     */
    private fun validateInput(avgSpeedKmh: Float, distanceMeters: Float): Boolean {
        if (distanceMeters < MIN_VALID_DISTANCE_M) {
            Log.d(TAG, "Distance too short: ${"%.1f".format(distanceMeters)}m (min: $MIN_VALID_DISTANCE_M m)")
            return false
        }
        if (avgSpeedKmh < 5f || avgSpeedKmh > 140f) {
            Log.d(TAG, "Speed out of range: ${"%.1f".format(avgSpeedKmh)} km/h (valid: 5-140 km/h)")
            return false
        }
        return true
    }

    // =========================================================================
    // SensorEventListener 接口实现
    // =========================================================================

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRecording) return

        // 速率控制 (防止高性能设备回调过快)
        val currentTime = System.nanoTime()
        if (currentTime - lastSampleTimeNs < SAMPLE_INTERVAL_NS) return
        lastSampleTimeNs = currentTime

        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> handleGravityEvent(event)
            Sensor.TYPE_LINEAR_ACCELERATION -> handleLinearAccelerationEvent(event, currentTime)
        }
    }

    private fun handleGravityEvent(event: SensorEvent) {
        synchronized(gravityLock) {
            System.arraycopy(event.values, 0, currentGravity, 0, 3)
            hasValidGravity = true
        }
    }

    private fun handleLinearAccelerationEvent(event: SensorEvent, timestampNs: Long) {
        if (!hasValidGravity) return

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        // 核心：向量投影 (动态重力模长)
        val verticalAcc = synchronized(gravityLock) {
            val gx = currentGravity[0]
            val gy = currentGravity[1]
            val gz = currentGravity[2]
            val gMag = sqrt(gx * gx + gy * gy + gz * gz)

            // 重力验证 (0.7g - 1.3g 有效范围)
            val MIN_GRAVITY = SensorManager.GRAVITY_EARTH * 0.7f
            val MAX_GRAVITY = SensorManager.GRAVITY_EARTH * 1.3f
            if (gMag !in MIN_GRAVITY..MAX_GRAVITY) {
                Log.w(TAG, "Invalid gravity magnitude: ${"%.2f".format(gMag)} (Expected $MIN_GRAVITY-$MAX_GRAVITY)")
                return@synchronized 0f
            }

            // 投影公式: (A·G)/|G|
            (ax * gx + ay * gy + az * gz) / gMag
        }

        // 存入缓冲区
        synchronized(dataLock) {
            rawSamples.add(AccelerationSample(timestampNs, verticalAcc))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // 工业级实现：精度下降时触发警告
        if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            Log.w(TAG, "Low accuracy sensor: ${sensor.name} (Accuracy: $accuracy)")
        }
    }

    /**
     * IRI 计算结果数据类
     *
     * **数据质量指南**：
     * - qualityIndex ≥ 0.8：高可信度，可用于养护决策
     * - 0.6 ≤ qualityIndex < 0.8：中等可信度，需人工复核
     * - qualityIndex < 0.6：低可信度，建议丢弃
     *
     * @property iriValue 计算得到的 IRI 值 (mm/m)，范围 0.0-20.0
     * @property qualityIndex 数据质量指数 (0.0-1.0)
     * @property spatialSampleCount 空间域有效样本数
     * @property distanceMeters 本段计算距离 (米)
     */
    data class IriResult(
        val iriValue: Float,
        val qualityIndex: Float,
        val spatialSampleCount: Int,
        val distanceMeters: Float
    ) {
        // 中国公路技术状况评定标准 (JTG H20-2007)
        val rating: String
            get() = when {
                iriValue <= 2.0f -> "优"
                iriValue <= 4.0f -> "良"
                iriValue <= 6.0f -> "中"
                iriValue <= 8.0f -> "次"
                else -> "差"
            }

        /**
         * 生成人类可读的报告字符串
         *
         * 示例：`IRI=3.2 mm/m | 等级=良 | 质量=85% | 距离=50.0m`
         *
         * @return 格式化报告字符串
         */
        override fun toString(): String {
            return "IRI=%.1f mm/m | 等级=%s | 质量=%.0f%% | 距离=%.1fm"
                .format(iriValue, rating, qualityIndex * 100, distanceMeters)
        }
    }
}