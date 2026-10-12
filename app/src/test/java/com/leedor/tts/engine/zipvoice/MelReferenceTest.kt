package com.leedor.tts.engine.zipvoice

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 梅尔频谱的**数值对照**测试。
 *
 * 现有 [MelSpectrogramTest] 只验证结构性正确（维度、帧数、峰值位置）。
 * 这里对照的是 Python 参考实现算出来的具体数值，而那份 Python 实现
 * 已经过端到端验证：用它提取参考音频的梅尔、喂给 vocos 声码器 + iSTFT，
 * 重建出的波形与原音频电平一致（RMS 0.0884 vs 0.0902），说明它就是
 * 模型期望的那个梅尔。
 *
 * 一旦这里对不上，合成就会变成杂音 —— 这类数值 bug 靠结构性测试抓不到。
 */
class MelReferenceTest {

    private val mel = MelSpectrogram(
        sampleRate = 24_000, nFft = 1024, hopLength = 256,
        winLength = 1024, numMels = 100, featScale = 0.1f
    )

    /** 与 Python 参考实现完全相同的测试信号：220Hz + 1310Hz 两个正弦，0.6 秒。 */
    private fun signal(): FloatArray {
        val n = 14_400
        return FloatArray(n) { i ->
            (0.4 * sin(2 * PI * 220 * i / 24_000) + 0.3 * sin(2 * PI * 1310 * i / 24_000)).toFloat()
        }
    }

    @Test
    fun `与 Python 参考实现逐点一致`() {
        val out = mel.compute(signal())
        assertEquals("帧数应为 1 + 14400/256 = 57", 57 * 100, out.size)

        val expected = mapOf(
            0 to doubleArrayOf(
                0.260822, 0.267294, 0.276022, 0.286442,
                0.301184, 0.320266, 0.353589, 0.414308
            ),
            28 to doubleArrayOf(
                -0.271764, -0.249638, -0.217076, -0.172801,
                -0.114467, -0.036449, 0.076283, 0.291242
            ),
            56 to doubleArrayOf(
                0.255927, 0.263010, 0.271257, 0.282190,
                0.296487, 0.316254, 0.348771, 0.412163
            )
        )

        for ((frame, values) in expected) {
            for (bin in values.indices) {
                assertEquals(
                    "帧$frame bin$bin 与参考实现不一致",
                    values[bin], out[frame * 100 + bin].toDouble(), 5e-3
                )
            }
        }
    }

    @Test
    fun `整体统计量与参考实现一致`() {
        val out = mel.compute(signal())
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var sum = 0.0
        for (v in out) {
            if (v < min) min = v
            if (v > max) max = v
            sum += v
        }
        val mean = sum / out.size
        assertEquals("mean", -0.617286, mean, 5e-3)
        assertEquals("min", -1.365408, min.toDouble(), 0.1)
        assertEquals("max", 0.466752, max.toDouble(), 0.05)
    }
}
