package com.leedor.tts.engine.zipvoice

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log
import kotlin.math.sqrt

/**
 * ZipVoice 的梅尔频谱提取 —— 逐行对齐 sherpa-onnx 用的 `kaldi-native-fbank`。
 *
 * 参数（来自模型元数据）：
 * ```
 * sample_rate = 24000   n_fft = 1024   hop_length = 256
 * win_length  = 1024    num_mels = 100 feat_scale = 0.1
 * ```
 *
 * 流程：
 * 1. STFT（hann 窗、**周期型**、`center=true`、**reflect 填充** n_fft/2）
 * 2. 取模长 `sqrt(re² + im²)` → 513 个频点
 * 3. 梅尔滤波器组（**HTK 刻度**、librosa 风格、**不做 slaney 归一化**）
 * 4. `log(mel + 1e-10) * feat_scale`
 *
 * ⚠️ 三个容易搞错的点，都是从 knf 源码里确认的：
 *
 * - **窗函数是周期型**：`a = 2π/win_length`，`w[i] = 0.5 - 0.5*cos(a*i)`，
 *   不是对称型（分母不是 `win_length-1`）。见 knf `feature-window.cc`。
 * - **滤波器组有 `n_fft/2 + 1 = 513` 个频点**，不是 512。knf 注释明确写了
 *   "It is not an error to use num_fft_bins + 1 here. It is different from Kaldi."
 * - **`center` 用 reflect 填充**（knf `StftConfig::pad_mode` 默认值），不是补零。
 */
class MelSpectrogram(
    private val sampleRate: Int = 24_000,
    private val nFft: Int = 1024,
    private val hopLength: Int = 256,
    private val winLength: Int = 1024,
    private val numMels: Int = 100,
    private val featScale: Float = 0.1f
) {
    companion object {
        private const val LOG_EPS = 1e-10f
        /** HTK 梅尔刻度的两个常数。 */
        private const val MEL_BREAK = 700.0
        private const val MEL_HZ = 1127.0
    }

    /** FFT 频点数 = n_fft/2 + 1。 */
    private val fftBins = nFft / 2 + 1

    private val fft = DoubleFFT_1D(nFft.toLong())

    /** 周期型 hann 窗。 */
    private val window = FloatArray(winLength) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / winLength)).toFloat()
    }

    /** 梅尔滤波器组，[numMels][fftBins]。 */
    private val melFilters: Array<FloatArray> = buildMelFilters()

    // ------------------------------------------------------------------
    // 梅尔刻度（HTK）
    // ------------------------------------------------------------------

    private fun hzToMel(freq: Double): Double = MEL_HZ * ln(1.0 + freq / MEL_BREAK)

    private fun melToHz(mel: Double): Double = MEL_BREAK * (exp(mel / MEL_HZ) - 1.0)

    /**
     * 构造滤波器组 —— 对齐 knf 的 `InitLibrosaMelBanks`，
     * 参数：`is_librosa=true`、`use_slaney_mel_scale=false`、`norm=""`、`floor_to_int_bin=false`。
     */
    private fun buildMelFilters(): Array<FloatArray> {
        val nyquist = 0.5 * sampleRate
        val lowFreq = 0.0
        val highFreq = nyquist          // ZipVoice 传的是 high_freq = 12000 = nyquist

        val fftBinWidth = sampleRate.toDouble() / nFft

        val melLow = hzToMel(lowFreq)
        val melHigh = hzToMel(highFreq)
        // knf: divide by num_bins+1（不是 num_bins）—— 端部 bins 会向外摊开
        val melDelta = (melHigh - melLow) / (numMels + 1)

        return Array(numMels) { bin ->
            val leftMel = melLow + bin * melDelta
            val centerMel = melLow + (bin + 1) * melDelta
            val rightMel = melLow + (bin + 2) * melDelta

            val leftHz = melToHz(leftMel)
            val centerHz = melToHz(centerMel)
            val rightHz = melToHz(rightMel)

            val out = FloatArray(fftBins)
            for (i in 0 until fftBins) {
                val hz = fftBinWidth * i
                if (hz > leftHz && hz < rightHz) {
                    val weight = if (hz <= centerHz) {
                        (hz - leftHz) / (centerHz - leftHz)
                    } else {
                        (rightHz - hz) / (rightHz - centerHz)
                    }
                    // norm="" → 不做 slaney 归一化（不乘 2/(right-left)）
                    out[i] = weight.toFloat()
                }
            }
            out
        }
    }

    // ------------------------------------------------------------------
    // 主入口
    // ------------------------------------------------------------------

    /**
     * @param samples 单声道 float 采样，范围约 [-1, 1]，采样率必须等于 [sampleRate]
     * @return 展平的梅尔特征 `[numFrames * numMels]`，行优先
     */
    fun compute(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val numFrames = frameCount(samples.size)
        if (numFrames <= 0) return FloatArray(0)

        val padded = reflectPad(samples, nFft / 2)
        val out = FloatArray(numFrames * numMels)

        val frame = FloatArray(nFft)
        // ⚠️ JTransforms 的 complexForward 用「实部/虚部交错」格式，
        // 缓冲区长度必须是 2*n_fft（不是 n_fft）。
        val fftBuf = DoubleArray(2 * nFft)
        val magnitude = FloatArray(fftBins)

        for (t in 0 until numFrames) {
            val start = t * hopLength

            // 加窗（窗长可能小于 n_fft，其余补零）
            java.util.Arrays.fill(frame, 0f)
            val copyLen = minOf(winLength, padded.size - start)
            for (i in 0 until copyLen) {
                frame[i] = padded[start + i] * window[i]
            }

            // FFT：JTransforms 用「实部虚部交错」的格式
            for (i in 0 until nFft) {
                fftBuf[2 * i] = frame[i].toDouble()
                fftBuf[2 * i + 1] = 0.0
            }
            fft.complexForward(fftBuf)

            for (k in 0 until fftBins) {
                val re = fftBuf[2 * k]
                val im = fftBuf[2 * k + 1]
                magnitude[k] = sqrt(re * re + im * im).toFloat()
            }

            val base = t * numMels
            for (b in 0 until numMels) {
                var sum = 0f
                val f = melFilters[b]
                for (k in 0 until fftBins) {
                    val w = f[k]
                    if (w != 0f) sum += w * magnitude[k]
                }
                out[base + b] = log(sum + LOG_EPS, Math.E.toFloat()) * featScale
            }
        }
        return out
    }

    /** 与 knf 的 `NumFrames` 一致：center=true 时，帧数 = 1 + n/hop。 */
    private fun frameCount(n: Int): Int = 1 + n / hopLength

    /**
     * reflect 填充（对齐 knf 的 `Stft::Pad`）：
     * 左边界镜像 `data[1..pad]` 的反转，右边界镜像 `data[n-pad-1 .. n-1]` 的反转。
     */
    private fun reflectPad(data: FloatArray, pad: Int): FloatArray {
        val n = data.size
        val out = FloatArray(n + 2 * pad)
        System.arraycopy(data, 0, out, pad, n)

        // 左侧：data[1], data[2], ... 逆序填到最前面
        for (i in 0 until pad) {
            val src = minOf(1 + i, n - 1)
            out[pad - 1 - i] = data[src]
        }
        // 右侧：data[n-2], data[n-3], ... 逆序填到最后
        for (i in 0 until pad) {
            val src = maxOf(n - 2 - i, 0)
            out[pad + n + i] = data[src]
        }
        return out
    }
}
