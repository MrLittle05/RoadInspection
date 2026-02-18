package com.example.roadinspection.domain.iri

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sin

/**
 * IriCalculator 核心算法单元测试
 * * 测试策略：Mock 硬件依赖 -> 注入模拟波形数据 -> 验证计算结果
 */
class IriCalculatorTest {

    // 被测对象
    private lateinit var calculator: IriCalculator

    // Mock 对象
    private lateinit var mockContext: Context
    private lateinit var mockSensorManager: SensorManager
    private lateinit var mockAccelSensor: Sensor
    private lateinit var mockGravitySensor: Sensor

    @Before
    fun setUp() {
        // 1. Mock Android 日志类 (否则 Log.d 会报错 "Method not mocked")
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0

        // 2. Mock 系统服务和传感器
        mockContext = mockk(relaxed = true)
        mockSensorManager = mockk(relaxed = true)
        mockAccelSensor = mockk(relaxed = true)
        mockGravitySensor = mockk(relaxed = true)

        // 3. 设定 Context 返回 SensorManager
        every { mockContext.getSystemService(Context.SENSOR_SERVICE) } returns mockSensorManager

        // 4. 设定 SensorManager 返回具体的传感器 (防止 init 报错)
        every { mockSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) } returns mockAccelSensor
        every { mockSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) } returns mockGravitySensor

        // 5. 初始化计算器 (假设你已经注释掉了 DEFAULT_UNCALIBRATED_FACTOR 的检查)
        // 使用 6.0 作为测试标定系数
        calculator = IriCalculator(mockContext, 6.0f)
    }

    @After
    fun tearDown() {
        unmockkAll() // 释放 Mock 资源
    }

    @Test
    fun `test Happy Path - standard sine wave vibration returns valid IRI`() {
        // === 1. 准备场景 ===
        // 模拟速度：60 km/h (约 16.67 m/s)
        val speedKmh = 60f
        val speedMps = speedKmh / 3.6f

        // 模拟时长：10 秒
        val durationSec = 10
        val sampleRateHz = 50 // 50Hz
        val totalSamples = durationSec * sampleRateHz

        // 预期距离：16.67 m/s * 10s = 166.7m (满足 > 10m 的要求)
        val distanceMeters = speedMps * durationSec

        // === 2. 注入数据 (模拟路面颠簸) ===
        val startTime = System.nanoTime()

        for (i in 0 until totalSamples) {
            // 时间戳递增 (20ms = 20,000,000 ns)
            val timestamp = startTime + i * 20_000_000L

            // 构造一个 2Hz 的正弦波震动，振幅 0.5 m/s²
            // 这个频率(2Hz)位于带通滤波器(0.5-15Hz)通带内，应该被保留
            val timeSec = i.toFloat() / sampleRateHz
            val verticalAcc = 0.5f * sin(2 * Math.PI * 2.0 * timeSec).toFloat()

            // 注入后门
            calculator.injectSampleForTesting(timestamp, verticalAcc)
        }

        // === 3. 执行计算 ===
        // 注意：这里 calibrationFactor = 6.0 (在 setUp 中设置)
        val result = calculator.computeAndClear(speedKmh, distanceMeters)

        // === 4. 验证结果 ===
        assertNotNull("计算结果不应为 null", result)

        // 打印结果方便调试
        println("Test Result: $result")

        // 断言逻辑：
        // 1. 输入了震动，IRI 应该 > 0
        assertTrue("IRI 值应大于 0", result!!.iriValue > 0f)

        // 2. 质量指数应该是满分 (因为样本覆盖率完美，速度在 30-80 之间)
        assertEquals("数据质量应为 1.0", 1.0f, result.qualityIndex, 0.01f)

        // 3. 距离应该一致
        assertEquals("计算距离应匹配", distanceMeters, result.distanceMeters, 0.1f)
    }

    @Test
    fun `test Edge Case - short distance returns null`() {
        // === 模拟短距离 (< 10m) ===
        // 注入少量数据
        val startTime = System.nanoTime()
        for (i in 0 until 50) { // 1秒数据
            calculator.injectSampleForTesting(startTime + i * 20_000_000L, 0.1f)
        }

        // 距离 5米 (小于阈值 10m)
        val result = calculator.computeAndClear(avgSpeedKmh = 60f, distanceMeters = 5.0f)

        // === 验证 ===
        assertNull("距离不足时应返回 null", result)
    }

    @Test
    fun `test Edge Case - low speed returns null`() {
        // === 模拟低速 (< 5km/h) ===
        // 注入足够多的数据
        val startTime = System.nanoTime()
        for (i in 0 until 500) {
            calculator.injectSampleForTesting(startTime + i * 20_000_000L, 0.1f)
        }

        // 速度 3 km/h
        val result = calculator.computeAndClear(avgSpeedKmh = 3.0f, distanceMeters = 50.0f)

        // === 验证 ===
        assertNull("速度过低时应返回 null", result)
    }

    @Test
    fun `test Insufficient Samples - returns null`() {
        // === 模拟样本不足 (< 15个) ===
        val startTime = System.nanoTime()
        for (i in 0 until 5) {
            calculator.injectSampleForTesting(startTime + i * 20_000_000L, 0.1f)
        }

        val result = calculator.computeAndClear(avgSpeedKmh = 60f, distanceMeters = 100f)

        assertNull("样本数量不足时应返回 null", result)
    }

    @Test
    fun `test State Isolation - previous vibration does not leak into next calculation`() {
        // === 第 1 轮：剧烈颠簸 (使用 2Hz 正弦波，振幅 5.0) ===
        val startTime = System.nanoTime()
        val sampleRateHz = 50

        for (i in 0 until 100) {
            val timeSec = i.toFloat() / sampleRateHz
            // 修改点：使用 sin 波形，频率 2Hz，振幅 5.0
            // 2Hz 在通带 (0.5-15Hz) 内，不会被滤除
            val vibration = 5.0f * kotlin.math.sin(2 * Math.PI * 2.0 * timeSec).toFloat()

            calculator.injectSampleForTesting(startTime + i * 20_000_000L, vibration)
        }

        val result1 = calculator.computeAndClear(60f, 50f)

        // 验证第 1 轮
        assertNotNull(result1)
        println("Round 1 IRI: ${result1!!.iriValue}") // 调试用
        // RMS of sine wave (A=5) is A/sqrt(2) ≈ 3.53
        // IRI = RMS * Factor(6.0) ≈ 21.2
        assertTrue("第一次计算应该有数值", result1.iriValue > 5.0f)

        // === 第 2 轮：完全静止 (平路) ===
        // 如果 resetFilterStates() 失效，滤波器里残留的上一次的高能量会导致这次结果不为 0
        val startTime2 = startTime + 100 * 20_000_000L + 20_000_000L // 继续时间轴 (加一个间隔防止重叠)

        for (i in 0 until 100) {
            calculator.injectSampleForTesting(startTime2 + i * 20_000_000L, 0.0f) // 0.0 m/s² (绝对平滑)
        }

        val result2 = calculator.computeAndClear(60f, 50f)

        // 验证第 2 轮
        assertNotNull(result2)
        println("Round 2 IRI: ${result2!!.iriValue}")
        // 允许微小的浮点误差 (0.1)
        assertEquals("第二次计算应不受第一次影响，接近 0", 0.0f, result2.iriValue, 0.1f)
    }

    @Test
    fun `test Time Gap - ignores large time gaps (frame drops)`() {
        val startTime = System.nanoTime()

        // 1. 注入前 10 个正常点 (使用正弦波更稳妥，虽然这里只测 null)
        for (i in 0 until 10) {
            calculator.injectSampleForTesting(startTime + i * 20_000_000L, 0.5f)
        }

        // 2. 突然卡顿 2 秒 (模拟系统负载过高)
        val gapTime = startTime + 10 * 20_000_000L + 2_000_000_000L // +2秒

        // 3. 注入后 10 个正常点
        for (i in 0 until 10) {
            calculator.injectSampleForTesting(gapTime + i * 20_000_000L, 0.5f)
        }

        // === 核心修改点 ===
        // 原来是 20f。
        // 但实际有效数据只有 0.4秒 (10+10个点) * 16.67m/s ≈ 6.7米。
        // 中间那 2秒的空窗期被算法正确丢弃了。
        // 所以我们告诉算法这段路只有 8米 或 10米，这样 6.7米的有效数据就能满足 >50% 的密度要求。
        val result = calculator.computeAndClear(60f, 10f)

        // 验证
        assertNotNull("即使有丢帧，只要剩余有效数据的密度足够，也应该能计算", result)

        // 可选验证：确认距离没有被那个 2秒 拉长
        // 实际上算法算出的 distanceMeters 应该是有效部分的近似值
        println("Calculated Distance: ${result!!.distanceMeters}")
    }

    @Test
    fun `test High Speed Boundary`() {
        val startTime = System.nanoTime()
        for (i in 0 until 100) {
            calculator.injectSampleForTesting(startTime + i * 20_000_000L, 0.5f)
        }

        // 1. 测试 139 km/h (应该通过)
        val validResult = calculator.computeAndClear(139f, 100f)
        assertNotNull("139 km/h 应该是有效的", validResult)

        // 注入数据补充 (因为 computeAndClear 会清空)
        for (i in 0 until 100) {
            calculator.injectSampleForTesting(startTime + i * 20_000_000L, 0.5f)
        }

        // 2. 测试 141 km/h (应该失败)
        val invalidResult = calculator.computeAndClear(141f, 100f)
        assertNull("141 km/h 应该被拒绝", invalidResult)
    }

    @Test
    fun `test Data Density - prevents calculation when samples do not match distance`() {
        val startTime = System.nanoTime()

        // 场景：只有 1 秒的数据 (50个点)
        // 速度 60km/h (16.6 m/s) -> 理论上这 1 秒只代表 16.6米
        for (i in 0 until 50) {
            calculator.injectSampleForTesting(startTime + i * 20_000_000L, 0.5f)
        }

        // 错误输入：告诉计算器我们跑了 100 米 (明显与数据量不符)
        // 代码里的 expectedCount = 100 / 0.1 = 1000 个空间点
        // 实际 spatialSamples 只有约 160 个
        // 160 < 1000 * 0.5 -> 触发 "Sparse spatial samples"
        val result = calculator.computeAndClear(60f, 100f)

        assertNull("如果样本量远小于距离对应的预期量，应拒绝计算", result)
    }
}