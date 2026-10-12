package com.leedor.tts.engine.zipvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 梅尔频谱提取的单元测试。
 *
 * 参数与 ZipVoice 一致：sr=24000, n_fft=1024, hop=256, win=1024, num_mels=100, feat_scale=0.1。
 *
 * 这些测试验证的是**结构性正确性**（维度、帧数、频率定位、数值范围），
 * 足以抓住「窗函数写错、频点数写错、滤波器组写错」这类问题。
 */
class MelSpectrogramTest {

    private val mel = MelSpectrogram(
        sampleRate = 24_000, nFft = 1024, hopLength = 256,
        winLength = 1024, numMels = 100, featScale = 0.1f
    )

    private fun sine(freq: Double, seconds: Double, sr: Int = 24_000): FloatArray {
        val n = (sr * seconds).toInt()
        return FloatArray(n) { i -> (0.5 * sin(2 * PI * freq * i / sr)).toFloat() }
    }

    @Test
    fun `帧数与 knf 的公式一致`() {
        // knf: center=true 时 padded_len = n + n_fft，num_frames = 1 + (padded - n_fft)/hop = 1 + n/hop
        for (n in listOf(24_000, 4_800, 1_024, 256, 1)) {
            val out = mel.compute(FloatArray(n))
            val expectedFrames = 1 + n / 256
            assertEquals(
                "n=$n 时输出维度不对",
                expectedFrames * 100,
                out.size
            )
        }
    }

    @Test
    fun `输出维度是 帧数 × 100`() {
        val out = mel.compute(sine(440.0, 1.0))
        assertEquals(0, out.size % 100)
        assertTrue("应有多个帧", out.size / 100 > 10)
    }

    @Test
    fun `数值都是有限的`() {
        val out = mel.compute(sine(440.0, 0.5))
        assertTrue("不应出现 NaN/Inf", out.all { it.isFinite() })
    }

    @Test
    fun `静音的梅尔值等于 log(1e-10) 缩放`() {
        val out = mel.compute(FloatArray(24_000))
        // log(1e-10) * 0.1 ≈ -2.302585
        val expected = (kotlin.math.ln(1e-10) * 0.1).toFloat()
        for (v in out) {
            assertEquals("静音应落到对数下限", expected, v, 1e-3f)
        }
    }

    @Test
    fun `正弦波的能量落在对应频段的梅尔 bin 上`() {
        // 1000 Hz 的正弦：峰值应该出现在低频段的某几个 bin 里，
        // 而不是跑到高频或全平。
        val out = mel.compute(sine(1000.0, 1.0))
        val frames = out.size / 100
        // 取中间一帧，避开 reflect 边界
        val frameIdx = frames / 2
        val frame = FloatArray(100) { out[frameIdx * 100 + it] }

        val peakBin = frame.indices.maxByOrNull { frame[it] }!!
        // 由滤波器组设计推得：1000 Hz 大致落在第 25~35 个 bin
        assertTrue(
            "1000Hz 的峰值 bin=$peakBin 超出预期区间 25..35",
            peakBin in 25..35
        )
        // 峰值必须明显高于最低值
        assertTrue("峰值不够突出", frame[peakBin] - frame.min() > 1.0f)
    }

    @Test
    fun `不同频率的峰值 bin 单调递增`() {
        fun peakBin(freq: Double): Int {
            val out = mel.compute(sine(freq, 1.0))
            val frames = out.size / 100
            val f = frames / 2
            return (0 until 100).maxByOrNull { out[f * 100 + it] }!!
        }
        val b500 = peakBin(500.0)
        val b2000 = peakBin(2000.0)
        val b6000 = peakBin(6000.0)
        assertTrue("500Hz($b500) 应低于 2000Hz($b2000)", b500 < b2000)
        assertTrue("2000Hz($b2000) 应低于 6000Hz($b6000)", b2000 < b6000)
    }

    @Test
    fun `空输入返回空数组而不是崩溃`() {
        assertEquals(0, mel.compute(FloatArray(0)).size)
    }

    @Test
    fun `极短输入不会崩溃`() {
        val out = mel.compute(floatArrayOf(0.1f))
        assertEquals(100, out.size)   // 1 帧
    }
}
