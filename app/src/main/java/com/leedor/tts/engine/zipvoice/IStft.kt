package com.leedor.tts.engine.zipvoice

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 逆短时傅里叶变换（iSTFT）—— 对齐 kaldi-native-fbank 的 `IStft::Compute`。
 *
 * **为什么需要它**：ZipVoice 的 vocoder（`vocos_24khz.onnx`）**不直接输出波形**，
 * 它输出三个张量 `mag` / `x` / `y`，形状都是 `(1, n_fft/2+1, num_frames)`，
 * 表示复数谱的「幅度 × 单位相位分量」：
 * ```
 * real = mag · x
 * imag = mag · y
 * ```
 * 必须再经过 iSTFT 才能得到 24kHz 波形。少了这一步，输出是噪声。
 *
 * 参数（来自 vocoder 的 ONNX 元数据）：
 * ```
 * n_fft = 1024   hop_length = 256   win_length = 1024
 * window_type = "hann"   center = true   normalized = false
 * ```
 */
class IStft(
    private val nFft: Int = 1024,
    private val hopLength: Int = 256,
    private val winLength: Int = 1024,
    private val center: Boolean = true,
    private val normalized: Boolean = false
) {
    private val fft = DoubleFFT_1D(nFft.toLong())

    /** 周期型 hann 窗（与 [MelSpectrogram] 保持一致）。 */
    private val window = FloatArray(winLength) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / winLength)).toFloat()
    }

    /**
     * @param real 展平的实部，`[numFrames * (n_fft/2+1)]`，行优先
     * @param imag 展平的虚部，同形状
     * @param numFrames 帧数
     * @return 波形（已去掉 center 填充）
     */
    fun compute(real: FloatArray, imag: FloatArray, numFrames: Int): FloatArray {
        if (numFrames <= 0) return FloatArray(0)

        val fftBins = nFft / 2 + 1
        val numSamples = nFft + (numFrames - 1) * hopLength
        val samples = FloatArray(numSamples)

        val tmp = DoubleArray(2 * nFft)

        for (frame in 0 until numFrames) {
            val base = frame * fftBins

            // 1. 组装完整的 N 点复数谱。
            //
            // ⚠️ 关键：只能拿到前 n_fft/2+1 个频点，后半段必须按
            // **共轭对称**补出来（X[N-k] = conj(X[k])），否则逆变换结果不对。
            // 早期版本直接把后半段留空，导致输出幅度只有正确值的一半。
            java.util.Arrays.fill(tmp, 0.0)

            // DC 与 Nyquist 都只有实部
            tmp[0] = real[base].toDouble()
            tmp[nFft] = real[base + nFft / 2].toDouble()

            // 正频率部分 + 共轭镜像
            for (k in 1 until nFft / 2) {
                val re = real[base + k].toDouble()
                val im = imag[base + k].toDouble()
                tmp[2 * k] = re
                tmp[2 * k + 1] = im
                val mirror = 2 * (nFft - k)
                tmp[mirror] = re
                tmp[mirror + 1] = -im
            }

            // 2. 逆 FFT（JTransforms 不做 1/N 缩放，手动除）
            fft.complexInverse(tmp, false)
            val inv = 1.0f / nFft

            // 3. 加合成窗 + 重叠相加
            val start = frame * hopLength
            val copyLen = minOf(winLength, numSamples - start)
            for (i in 0 until copyLen) {
                samples[start + i] += tmp[2 * i].toFloat() * inv * window[i]
            }
        }

        // 4. 归一化：除以窗平方的叠加（避免边界处音量偏低）
        val denom = FloatArray(numSamples)
        for (frame in 0 until numFrames) {
            val start = frame * hopLength
            val copyLen = minOf(winLength, numSamples - start)
            for (i in 0 until copyLen) {
                denom[start + i] += window[i] * window[i]
            }
        }
        for (i in 0 until numSamples) {
            if (denom[i] > 0f) samples[i] /= denom[i]
        }

        // 5. center=true → 去掉首尾各 n_fft/2
        if (!center) return samples
        val pad = nFft / 2
        if (numSamples <= 2 * pad) return FloatArray(0)
        return samples.copyOfRange(pad, numSamples - pad)
    }
}
