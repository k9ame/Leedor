package com.leedor.tts.engine.zipvoice

import org.jtransforms.fft.DoubleFFT_1D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * iSTFT 的单元测试。
 *
 * 核心是一个**往返测试**：自己做一次前向 STFT（同样的窗、hop、reflect 居中填充），
 * 再交给 [IStft] 重建，比较与原信号的差异。能通过说明
 * 「复数谱组装 / 逆变换缩放 / 加窗重叠相加 / 窗平方归一化 / 去居中填充」这几步都对。
 */
class IStftTest {

    private val nFft = 1024
    private val hop = 256
    private val win = 1024

    private val window = FloatArray(win) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / win)).toFloat()
    }

    /** 前向 STFT：hann 窗 + reflect 居中填充，返回 (real, imag, numFrames)。 */
    private fun forwardStft(samples: FloatArray): Triple<FloatArray, FloatArray, Int> {
        val pad = nFft / 2
        val padded = FloatArray(samples.size + 2 * pad)
        System.arraycopy(samples, 0, padded, pad, samples.size)
        for (i in 0 until pad) {
            padded[pad - 1 - i] = samples[minOf(1 + i, samples.size - 1)]
            padded[pad + samples.size + i] = samples[maxOf(samples.size - 2 - i, 0)]
        }

        val numFrames = 1 + (padded.size - nFft) / hop
        val bins = nFft / 2 + 1
        val real = FloatArray(numFrames * bins)
        val imag = FloatArray(numFrames * bins)

        val fft = DoubleFFT_1D(nFft.toLong())
        val buf = DoubleArray(2 * nFft)
        for (t in 0 until numFrames) {
            java.util.Arrays.fill(buf, 0.0)
            val start = t * hop
            for (i in 0 until win) {
                buf[2 * i] = (padded[start + i] * window[i]).toDouble()
            }
            fft.complexForward(buf)
            for (k in 0 until bins) {
                real[t * bins + k] = buf[2 * k].toFloat()
                imag[t * bins + k] = buf[2 * k + 1].toFloat()
            }
        }
        return Triple(real, imag, numFrames)
    }

    @Test
    fun `往返重建能还原正弦波`() {
        val sr = 24_000
        val n = 24_000
        val original = FloatArray(n) { i -> (0.5 * sin(2 * PI * 440.0 * i / sr)).toFloat() }

        val (re, im, frames) = forwardStft(original)
        val out = IStft(nFft, hop, win, center = true, normalized = false).compute(re, im, frames)

        // 重建长度 = (frames-1)*hop，比原始略短（STFT 会丢掉尾部不足一帧的部分）
        val expectedLen = (frames - 1) * hop
        assertEquals(expectedLen, out.size)

        // 跳过两端各一帧，避开边界效应
        val from = nFft
        val to = minOf(out.size - nFft, original.size)
        var maxErr = 0f
        for (i in from until to) maxErr = maxOf(maxErr, abs(out[i] - original[i]))
        assertTrue("重建误差过大: $maxErr", maxErr < 0.02f)
    }

    @Test
    fun `静音输入重建后仍是静音`() {
        val (re, im, frames) = forwardStft(FloatArray(24_000))
        val out = IStft(nFft, hop, win, center = true, normalized = false).compute(re, im, frames)
        assertTrue("静音重建不应有能量", out.all { abs(it) < 1e-6f })
    }

    @Test
    fun `全零频谱输出全零`() {
        val bins = nFft / 2 + 1
        val frames = 10
        val out = IStft(nFft, hop, win, center = true, normalized = false)
            .compute(FloatArray(frames * bins), FloatArray(frames * bins), frames)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `输出幅度与输入一致`() {
        val sr = 24_000
        val n = 24_000
        // 满幅正弦，重建后 RMS 应接近 1/sqrt(2)
        val original = FloatArray(n) { i -> sin(2 * PI * 440.0 * i / sr).toFloat() }
        val (re, im, frames) = forwardStft(original)
        val out = IStft(nFft, hop, win, center = true, normalized = false).compute(re, im, frames)

        var sum = 0.0
        val from = nFft
        val to = minOf(out.size - nFft, original.size)
        for (i in from until to) sum += out[i].toDouble() * out[i]
        val rms = sqrt(sum / (to - from))
        assertEquals("满幅正弦的 RMS 应约为 0.707", 0.707, rms, 0.05)
    }

    @Test
    fun `帧数为 0 时返回空数组`() {
        assertTrue(IStft(nFft, hop, win).compute(FloatArray(0), FloatArray(0), 0).isEmpty())
    }
}
