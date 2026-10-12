package com.leedor.tts.engine.zipvoice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.leedor.tts.engine.EspeakWrapper
import com.leedor.tts.engine.TtsEngine
import com.leedor.tts.engine.frontend.Lexicon
import com.leedor.tts.engine.frontend.MatchaFrontend
import com.leedor.tts.engine.frontend.TextUtils
import com.leedor.tts.engine.frontend.TokenTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.sqrt

/**
 * ZipVoice 零样本语音克隆引擎（中英）。
 *
 * 与其它引擎最大的不同：**它必须有参考音频才能合成**。
 * 先录一段（或选一段）参考音频 + 它的文本，之后就能用那个音色朗读任意文本。
 *
 * 推理链路（逐行对齐 sherpa-onnx 的 `offline-tts-zipvoice-impl.h`）：
 * ```
 * 1. 参考音频 RMS 归一化到 target_rms=0.1（仅当 rms < 0.1）
 * 2. 梅尔频谱 → prompt_features [1, T, 100]
 * 3. text_condition = encoder(tokens, prompt_tokens, prompt_features_len, speed)
 * 4. x = 高斯噪声 [1, num_frames, 100]
 * 5. speech_condition = prompt_features 补齐/截断到 num_frames
 * 6. 流匹配欧拉积分（num_steps 步）
 * 7. transpose + /feat_scale → vocoder → 24kHz 音频
 * ```
 *
 * 依赖三个 ONNX 模型：encoder / decoder / vocoder（vocos_24khz 是独立声码器）。
 */
class ZipVoiceEngine(
    private val context: Context,
    private val modelId: String = "zipvoice_zh_en"
) : TtsEngine {

    companion object {
        private const val TAG = "ZipVoiceEngine"

        const val SAMPLE_RATE = 24_000

        // ---- 梅尔频谱参数（与 MelSpectrogram 保持一致）----
        private const val FEAT_DIM = 100
        private const val N_FFT = 1024
        private const val HOP_LENGTH = 256
        private const val WIN_LENGTH = 1024
        private const val NUM_MELS = 100
        private const val FEAT_SCALE = 0.1f

        // ---- 流匹配参数 ----
        private const val T_SHIFT = 0.5f
        private const val TARGET_RMS = 0.1f
        private const val GUIDANCE_SCALE = 1.0f
        private const val NUM_STEPS = 4

        /** 参考音频太短会让音色不稳定。 */
        private const val MIN_REFERENCE_SAMPLES = SAMPLE_RATE / 2   // 0.5 秒

        // ---- 文件路径（对应 assets/models.json 里的 path）----
        private const val DIR = "zipvoice"
        private const val ENCODER_FILE = "$DIR/encoder.int8.onnx"
        private const val DECODER_FILE = "$DIR/decoder.int8.onnx"
        private const val VOCODER_FILE = "$DIR/vocos_24khz.onnx"
        private const val TOKENS_FILE = "$DIR/tokens.txt"
        private const val LEXICON_FILE = "$DIR/lexicon.txt"

        /** 克隆语音存放目录。 */
        private const val CLONE_DIR = "$DIR/cloned"
        private const val REF_AUDIO_EXT = ".wav"
        private const val REF_TEXT_EXT = ".txt"

        /**
         * 没有参考文本时的兜底文本。
         * ZipVoice 需要「参考音频 + 对应文本」，缺文本时用一句通用中文，
         * 音色仍能克隆，但相似度会下降。
         */
        private const val DEFAULT_REFERENCE_TEXT = "你好，这是一段用于克隆音色的参考音频。"
    }

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null

    private var frontend: MatchaFrontend? = null
    private var melExtractor: MelSpectrogram? = null
    private var istft: IStft? = null
    private var espeak: EspeakWrapper? = null

    private var initialized = false

    @Volatile private var stopFlag = false

    /** 随机源。固定种子 → 同一段文本每次生成结果一致，便于排查问题。 */
    private val rng = Random(1234L)

    // ---- 当前参考（克隆目标）----
    @Volatile private var refSamples: FloatArray? = null
    @Volatile private var refText: String = ""
    @Volatile private var refFeatures: FloatArray? = null
    @Volatile private var refFeatureFrames: Int = 0
    @Volatile private var refTokens: LongArray = LongArray(0)
    @Volatile private var currentVoiceName: String? = null

    // ==================================================================
    // 生命周期
    // ==================================================================

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefs = com.leedor.tts.data.PrefsManager(context)
            val threads = prefs.cpuThreads

            Log.i(TAG, "初始化 ZipVoice，线程数=$threads")

            val encoderFile = File(context.filesDir, ENCODER_FILE)
            val decoderFile = File(context.filesDir, DECODER_FILE)
            val vocoderFile = File(context.filesDir, VOCODER_FILE)
            val tokensFile = File(context.filesDir, TOKENS_FILE)
            val lexiconFile = File(context.filesDir, LEXICON_FILE)

            for ((label, f) in listOf(
                "encoder" to encoderFile, "decoder" to decoderFile,
                "vocoder" to vocoderFile, "tokens" to tokensFile, "lexicon" to lexiconFile
            )) {
                if (!f.exists()) {
                    Log.e(TAG, "缺少 $label 文件: ${f.absolutePath}，需要先在设置里下载模型")
                    return@withContext false
                }
            }

            // 1. 文本前端
            val tokens = TokenTable.fromFile(tokensFile)
            val lexicon = Lexicon.fromFile(lexiconFile, tokens)
            frontend = MatchaFrontend(
                tokens = tokens,
                lexicon = lexicon,
                oovPhonemizer = { text -> espeakPhonemes(text) }
            )
            Log.i(TAG, "前端就绪：tokens=${tokens.size}, lexicon=${lexicon.size}")

            // 2. 梅尔提取器
            melExtractor = MelSpectrogram(
                sampleRate = SAMPLE_RATE, nFft = N_FFT, hopLength = HOP_LENGTH,
                winLength = WIN_LENGTH, numMels = NUM_MELS, featScale = FEAT_SCALE
            )
            // vocoder 输出的是复数谱分量（mag/x/y），必须再过一次 iSTFT 才是波形
            istft = IStft(
                nFft = N_FFT, hopLength = HOP_LENGTH, winLength = WIN_LENGTH,
                center = true, normalized = false
            )

            // 3. ONNX 会话
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            encoderSession = createSession(env!!, encoderFile, opts)
            decoderSession = createSession(env!!, decoderFile, opts)
            vocoderSession = createSession(env!!, vocoderFile, opts)

            Log.i(TAG, "encoder 输入=${encoderSession?.inputNames}")
            Log.i(TAG, "decoder 输入=${decoderSession?.inputNames}")
            Log.i(TAG, "vocoder 输入=${vocoderSession?.inputNames}")

            initialized = true
            Log.i(TAG, "ZipVoice 初始化成功")

            // 恢复上次用的克隆语音
            val saved = prefs.currentVoice
            if (saved.isNotBlank() && cloneExists(saved)) {
                loadReference(saved)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "ZipVoice 初始化失败", t)
            initialized = false
            false
        }
    }

    /** 32 位 ARM 上用字节数组加载，规避 mmap 对齐问题（与 KokoroEngine 一致）。 */
    private fun createSession(env: OrtEnvironment, file: File, opts: OrtSession.SessionOptions): OrtSession {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return if (abi == "armeabi-v7a" || abi == "armeabi") {
            env.createSession(file.readBytes(), opts)
        } else {
            env.createSession(file.absolutePath, opts)
        }
    }

    override fun release() {
        runCatching { encoderSession?.close() }
        runCatching { decoderSession?.close() }
        runCatching { vocoderSession?.close() }
        encoderSession = null
        decoderSession = null
        vocoderSession = null
        initialized = false
        Log.i(TAG, "ZipVoice 已释放")
    }

    override fun isInitialized(): Boolean = initialized

    override fun getSampleRate(): Int = SAMPLE_RATE

    override fun stop() {
        stopFlag = true
    }

    // ==================================================================
    // 语音克隆
    // ==================================================================

    override fun supportsVoiceCloning(): Boolean = true

    override suspend fun cloneVoice(audioPath: String, voiceName: String, transcript: String?): String? =
        withContext(Dispatchers.IO) {
            try {
                val src = File(audioPath)
                if (!src.exists()) {
                    Log.e(TAG, "参考音频不存在: $audioPath")
                    return@withContext null
                }

                // 1. 读 WAV
                val (samples, rate) = readWav(src) ?: run {
                    Log.e(TAG, "无法解析 WAV: $audioPath")
                    return@withContext null
                }
                if (samples.size < MIN_REFERENCE_SAMPLES) {
                    Log.e(TAG, "参考音频太短（${samples.size} 采样），至少需要 $MIN_REFERENCE_SAMPLES")
                    return@withContext null
                }

                // 2. 采样率必须匹配（录音器固定 24kHz，一般不会走到这里）
                val resampled = if (rate == SAMPLE_RATE) samples else {
                    Log.w(TAG, "参考音频采样率 $rate != $SAMPLE_RATE，做线性重采样")
                    linearResample(samples, rate, SAMPLE_RATE)
                }

                // 3. 落盘保存
                val dir = File(context.filesDir, CLONE_DIR).apply { mkdirs() }
                val id = sanitize(voiceName).ifBlank { "voice_${System.currentTimeMillis()}" }
                File(dir, "$id$REF_AUDIO_EXT").writeBytes(src.readBytes())
                File(dir, "$id$REF_TEXT_EXT").writeText(
                    transcript?.takeIf { it.isNotBlank() } ?: DEFAULT_REFERENCE_TEXT,
                    Charsets.UTF_8
                )

                // 4. 立刻加载为当前参考
                refSamples = resampled
                refText = transcript?.takeIf { it.isNotBlank() } ?: DEFAULT_REFERENCE_TEXT
                currentVoiceName = id
                computeReferenceConditioning()

                Log.i(TAG, "克隆完成: id=$id, 参考文本='${refText.take(30)}', 音频 ${resampled.size} 采样")
                id
            } catch (t: Throwable) {
                Log.e(TAG, "克隆失败", t)
                null
            }
        }

    override fun getClonedVoices(): List<String> {
        val dir = File(context.filesDir, CLONE_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(REF_AUDIO_EXT) }
            ?.map { it.name.removeSuffix(REF_AUDIO_EXT) }
            ?.sorted()
            ?: emptyList()
    }

    override fun deleteClonedVoice(voiceId: String): Boolean {
        val dir = File(context.filesDir, CLONE_DIR)
        val a = File(dir, "$voiceId$REF_AUDIO_EXT")
        val t = File(dir, "$voiceId$REF_TEXT_EXT")
        val ok = if (a.exists()) a.delete() else false
        if (t.exists()) t.delete()
        if (currentVoiceName == voiceId) {
            refSamples = null
            refFeatures = null
            refTokens = LongArray(0)
            currentVoiceName = null
        }
        return ok
    }

    override fun getVoices(): List<String> = getClonedVoices()

    private fun cloneExists(id: String): Boolean =
        File(context.filesDir, "$CLONE_DIR/$id$REF_AUDIO_EXT").exists()

    /** 加载指定克隆语音作为当前参考。 */
    private fun loadReference(id: String) {
        try {
            val dir = File(context.filesDir, CLONE_DIR)
            val audio = File(dir, "$id$REF_AUDIO_EXT")
            if (!audio.exists()) return
            val textFile = File(dir, "$id$REF_TEXT_EXT")
            // 参考文本的权威来源：
            //   1. **索引的 voicePrompts**（自带音色）—— 必须优先，
            //      不能缓存到 .txt 就算了：索引改了文本（例如把阿拉伯数字改成中文数字）
            //      之后，老缓存会让修复失效。
            //   2. 同目录的 `<名字>.txt`（用户自己克隆的音色，索引里没有）
            //   3. 默认文本兜底
            val fromManifest = com.leedor.tts.data.ModelRepository.voicePromptOf(modelId, id)
            val text = when {
                fromManifest != null -> {
                    Log.i(TAG, "自带音色 $id 的参考文本取自索引")
                    fromManifest
                }
                textFile.exists() -> textFile.readText(Charsets.UTF_8)
                else -> {
                    Log.w(TAG, "语音 $id 没有参考文本，使用默认文本")
                    DEFAULT_REFERENCE_TEXT
                }
            }

            val (samples, rate) = readWav(audio) ?: return
            refSamples = if (rate == SAMPLE_RATE) samples else linearResample(samples, rate, SAMPLE_RATE)
            refText = text
            currentVoiceName = id
            computeReferenceConditioning()
            Log.i(TAG, "已加载克隆语音: $id")
        } catch (t: Throwable) {
            Log.e(TAG, "加载克隆语音失败: $id", t)
        }
    }

    /**
     * 计算参考音频的条件量：梅尔特征 + 参考文本 token。
     * 这两样在每次合成时都要用，所以提前算好缓存起来。
     */
    private fun computeReferenceConditioning() {
        val samples = refSamples ?: return
        val mel = melExtractor ?: return

        // RMS 归一化：只在偏小时放大到 target_rms
        val scaled = samples.copyOf()
        var sumSq = 0.0
        var peak = 0f
        for (s in scaled) {
            sumSq += s.toDouble() * s
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
        }
        val rms = sqrt(sumSq / scaled.size)
        // 打出来很有用：读到"整流"过的音频时 peak 会 > 1（16-bit PCM 的合法范围是 [-1,1)），
        // 之前那个把 PCM 当无符号读的 bug 就是靠这个一眼可见。
        Log.i(TAG, "参考音频: ${scaled.size} 采样, peak=${"%.3f".format(peak)}, rms=${"%.4f".format(rms)}")
        if (peak > 1.001f) {
            Log.w(TAG, "参考音频峰值超过 1.0（$peak），PCM 可能被当成无符号读了")
        }
        if (rms > 0.0 && rms < TARGET_RMS) {
            val scale = (TARGET_RMS / rms).toFloat()
            for (i in scaled.indices) scaled[i] *= scale
            Log.i(TAG, "参考音频 RMS=${"%.4f".format(rms)} < $TARGET_RMS，放大 ${"%.2f".format(scale)} 倍")
        }

        val features = mel.compute(scaled)
        refFeatures = features
        refFeatureFrames = features.size / FEAT_DIM
        Log.i(TAG, "参考梅尔特征: ${refFeatureFrames} 帧 × $FEAT_DIM")

        // 参考文本 → token
        val ids = frontend?.textToTokenIds(refText) ?: emptyList()
        val flat = ArrayList<Long>()
        for (arr in ids) for (v in arr) flat.add(v.toLong())
        refTokens = flat.toLongArray()
        Log.i(TAG, "参考文本 token 数: ${refTokens.size}")
    }

    // ==================================================================
    // 合成
    // ==================================================================

    override suspend fun generate(
        text: String,
        speed: Float,
        voice: String?,
        callback: (FloatArray) -> Unit
    ) = withContext(Dispatchers.Default) {
        stopFlag = false

        if (!initialized) throw IllegalStateException("ZipVoice 未初始化")

        // 指定了别的克隆语音 → 切过去
        if (!voice.isNullOrBlank() && voice != currentVoiceName && cloneExists(voice)) {
            loadReference(voice)
        }

        val promptFeatures = refFeatures
        if (promptFeatures == null || refFeatureFrames <= 0) {
            Log.e(TAG, "还没有参考音频。ZipVoice 必须先克隆一个音色才能朗读。")
            return@withContext
        }

        val fe = frontend ?: throw IllegalStateException("前端未初始化")
        val envLocal = env ?: throw IllegalStateException("ONNX 环境未初始化")
        val enc = encoderSession ?: throw IllegalStateException("encoder 未加载")
        val dec = decoderSession ?: throw IllegalStateException("decoder 未加载")
        val voc = vocoderSession ?: throw IllegalStateException("vocoder 未加载")

        val sentences = fe.textToTokenIds(text)
        if (sentences.isEmpty()) {
            Log.w(TAG, "前端没有产出 token: '${text.take(30)}'")
            return@withContext
        }
        Log.i(TAG, "文本切成 ${sentences.size} 句")

        for ((idx, ids) in sentences.withIndex()) {
            if (stopFlag) return@withContext
            if (ids.isEmpty()) continue

            try {
                val audio = synthesizeChunk(
                    tokens = ids.map { it.toLong() }.toLongArray(),
                    promptTokens = refTokens,
                    promptFeatures = promptFeatures,
                    promptFrames = refFeatureFrames,
                    speed = speed,
                    envLocal = envLocal, enc = enc, dec = dec, voc = voc
                )
                if (audio.isNotEmpty()) callback(audio)
                Log.d(TAG, "第 ${idx + 1}/${sentences.size} 句完成，${audio.size} 采样")
            } catch (t: Throwable) {
                Log.e(TAG, "第 ${idx + 1} 句合成失败", t)
            }
        }
    }

    /**
     * 单句合成：encoder → 流匹配 → vocoder。
     *
     * ONNX 张量必须显式 close，否则会泄漏 native 内存，
     * 所以这里统一用 [withTensors] 管理生命周期。
     */
    private fun synthesizeChunk(
        tokens: LongArray,
        promptTokens: LongArray,
        promptFeatures: FloatArray,
        promptFrames: Int,
        speed: Float,
        envLocal: OrtEnvironment,
        enc: OrtSession,
        dec: OrtSession,
        voc: OrtSession
    ): FloatArray {
        // ---- 1. encoder ----
        val tokensT = OnnxTensor.createTensor(
            envLocal, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())
        )
        val promptT = OnnxTensor.createTensor(
            envLocal, LongBuffer.wrap(promptTokens), longArrayOf(1, promptTokens.size.toLong())
        )
        val lenT = OnnxTensor.createTensor(
            envLocal, LongBuffer.wrap(longArrayOf(promptFrames.toLong())), longArrayOf(1)
        )
        val speedT = OnnxTensor.createTensor(
            envLocal, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)
        )

        val (textCondition, numFrames) = withTensors(tokensT, promptT, lenT, speedT) {
            enc.run(
                mapOf(
                    "tokens" to tokensT,
                    "prompt_tokens" to promptT,
                    "prompt_features_len" to lenT,
                    "speed" to speedT
                )
            ).use { out ->
                val t = out[0] as OnnxTensor
                readFloats(t) to t.info.shape[1].toInt()
            }
        }

        if (numFrames <= 0) {
            Log.w(TAG, "encoder 输出帧数为 0")
            return FloatArray(0)
        }
        Log.d(TAG, "encoder 输出: $numFrames 帧")

        // ---- 2. 初始噪声 + speech_condition ----
        val n = numFrames * FEAT_DIM
        val x = FloatArray(n) { rng.nextGaussian().toFloat() }

        val speechCondition = FloatArray(n)
        val copyFrames = minOf(promptFrames, numFrames)
        System.arraycopy(promptFeatures, 0, speechCondition, 0, copyFrames * FEAT_DIM)

        // ---- 3. 流匹配欧拉积分 ----
        val timesteps = FloatArray(NUM_STEPS + 1) { i ->
            val t = i.toFloat() / NUM_STEPS
            T_SHIFT * t / (1f + (T_SHIFT - 1f) * t)
        }
        for (step in 0 until NUM_STEPS) {
            val v = runDecoder(envLocal, dec, timesteps[step], x, textCondition, speechCondition)
            val dt = timesteps[step + 1] - timesteps[step]
            for (i in 0 until n) x[i] += v[i] * dt
        }

        // ---- 4. 丢掉参考音频那一段，转置 (T,100) -> (100,T)，并除以 feat_scale ----
        //
        // 模型输出的帧数是 promptFrames + 合成帧数：它把参考音频也"重建"在序列开头
        // （实测：参考 568 帧 + 文本 212 帧 = encoder 输出 780 帧）。
        // 只有后面那段才是我们要的语音；整段喂给声码器会先输出 6 秒参考音频的
        // 重建结果（听感就是一片乱音）。
        // 对齐 sherpa：kept_frames = num_frames - prompt_feat_len。
        val keptFrames = numFrames - promptFrames
        if (keptFrames <= 0) {
            Log.w(TAG, "解码帧数($numFrames) 不大于参考帧数($promptFrames)，没有可合成的内容")
            return FloatArray(0)
        }
        Log.d(TAG, "解码 $numFrames 帧，丢掉前 $promptFrames 帧参考音频，合成 $keptFrames 帧")

        val permuted = selectGeneratedFrames(x, numFrames, FEAT_DIM, promptFrames, 1f / FEAT_SCALE)

        // ---- 5. vocoder：输出 mag/x/y 三个复数谱分量，不是波形 ----
        val melT = OnnxTensor.createTensor(
            envLocal, FloatBuffer.wrap(permuted),
            longArrayOf(1, FEAT_DIM.toLong(), keptFrames.toLong())
        )
        val (real, imag, frames) = withTensors(melT) {
            // 输入名不硬编码，用模型自己声明的第一个输入（实测是 "mels"）
            val inputName = voc.inputNames.first()
            voc.run(mapOf(inputName to melT)).use { out ->
                val magT = out[0] as OnnxTensor
                val xT = out[1] as OnnxTensor
                val yT = out[2] as OnnxTensor

                val shape = magT.info.shape            // (1, n_fft/2+1, num_frames)
                val bins = shape[1].toInt()
                val nf = shape[2].toInt()

                val mag = readFloats(magT)
                val px = readFloats(xT)
                val py = readFloats(yT)

                // 布局是 (bins, frames)，转成 iSTFT 要的 (frames, bins)：
                //   real[t][k] = mag[k][t] · x[k][t]
                //   imag[t][k] = mag[k][t] · y[k][t]
                val re = FloatArray(nf * bins)
                val im = FloatArray(nf * bins)
                for (f in 0 until nf) {
                    for (b in 0 until bins) {
                        val src = b * nf + f
                        re[f * bins + b] = mag[src] * px[src]
                        im[f * bins + b] = mag[src] * py[src]
                    }
                }
                Triple(re, im, nf)
            }
        }

        // ---- 6. iSTFT → 波形 ----
        val stft = istft ?: throw IllegalStateException("iSTFT 未初始化")
        return stft.compute(real, imag, frames)
    }

    private fun runDecoder(
        envLocal: OrtEnvironment,
        dec: OrtSession,
        t: Float,
        x: FloatArray,
        textCondition: FloatArray,
        speechCondition: FloatArray
    ): FloatArray {
        val numFrames = (x.size / FEAT_DIM).toLong()
        val shape = longArrayOf(1, numFrames, FEAT_DIM.toLong())

        val tT = OnnxTensor.createTensor(envLocal, FloatBuffer.wrap(floatArrayOf(t)), longArrayOf(1))
        val xT = OnnxTensor.createTensor(envLocal, FloatBuffer.wrap(x), shape)
        val tcT = OnnxTensor.createTensor(envLocal, FloatBuffer.wrap(textCondition), shape)
        val scT = OnnxTensor.createTensor(envLocal, FloatBuffer.wrap(speechCondition), shape)
        val gT = OnnxTensor.createTensor(
            envLocal, FloatBuffer.wrap(floatArrayOf(GUIDANCE_SCALE)), longArrayOf(1)
        )

        return withTensors(tT, xT, tcT, scT, gT) {
            dec.run(
                mapOf(
                    "t" to tT, "x" to xT,
                    "text_condition" to tcT, "speech_condition" to scT,
                    "guidance_scale" to gT
                )
            ).use { out -> readFloats(out[0] as OnnxTensor) }
        }
    }

    /** 把张量内容读成 FloatArray。 */
    private fun readFloats(t: OnnxTensor): FloatArray {
        val buf = t.floatBuffer
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }

    /** 统一管理 ONNX 张量的释放，避免 native 内存泄漏。 */
    private inline fun <T> withTensors(vararg tensors: OnnxTensor, block: () -> T): T {
        try {
            return block()
        } finally {
            for (t in tensors) runCatching { t.close() }
        }
    }

    // ==================================================================
    // 音频工具
    // ==================================================================

    /** eSpeak 音素化（英文 / 词典外的词）。 */
    private fun espeakPhonemes(text: String): List<String> {
        val w = espeak ?: EspeakWrapper().also { espeak = it }
        val dataDir = File(context.filesDir, "espeak-ng-data")
        if (!dataDir.exists()) {
            com.leedor.tts.utils.AssetUtils.extractAssets(context, "espeak-ng-data", context.filesDir)
        }
        w.initializeSafe(context.filesDir.absolutePath)
        val ipa = w.textToPhonemesSafe(text, "en-us") ?: return emptyList()
        return TextUtils.splitUtf8(ipa)
    }

    /**
     * 读取 16-bit PCM 单声道 WAV。
     * @return Pair(采样, 采样率)，失败返回 null
     */
    private fun readWav(file: File): Pair<FloatArray, Int>? {
        return try {
            // 解析逻辑抽到 WavReader.kt（可单元测试）——
            // 之前这里把 16-bit PCM 当无符号读，负样本全变成正数，
            // 参考音频被整流，克隆出来全是杂音。
            val decoded = decodeWav(file.readBytes())
            if (decoded == null) {
                Log.e(TAG, "不是合法的 16-bit WAV: ${file.name}")
                null
            } else {
                decoded.samples to decoded.sampleRate
            }
        } catch (t: Throwable) {
            Log.e(TAG, "读 WAV 失败", t)
            null
        }
    }

    /**
     * 线性插值重采样。
     *
     * 正常情况下用不到 —— 应用内的录音器固定 24kHz，与 ZipVoice 一致。
     * 这里只作为「用户导入了外部音频」时的兜底，音质要求不高。
     */
    private fun linearResample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = toRate.toDouble() / fromRate
        val outLen = (input.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val src = i / ratio
            val i0 = src.toInt()
            val i1 = minOf(i0 + 1, input.size - 1)
            val frac = (src - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40)
}

/**
 * 把解码器的输出变成声码器要的输入。
 *
 * 模型输出的帧数是 **promptFrames + 合成帧数**：它会把参考音频也"重建"在序列开头。
 * （实测：参考 568 帧 + 文本 212 帧 = encoder 输出 780 帧。）
 * 只有后面那段是我们要的语音；整段喂给声码器会先输出几秒参考音频的重建结果，
 * 听感就是一片乱音。对齐 sherpa 的 `kept_frames = num_frames - prompt_feat_len`。
 *
 * 另外要做两件事：把 (T, C) 转成声码器要的 (C, T)，并把训练用的 feat_scale 除回去。
 *
 * @param x 解码器输出，行优先 (numFrames, featDim)
 * @return 声码器输入 (featDim, keptFrames)；没有可合成的内容时返回空数组
 */
internal fun selectGeneratedFrames(
    x: FloatArray,
    numFrames: Int,
    featDim: Int,
    promptFrames: Int,
    invFeatScale: Float
): FloatArray {
    val keptFrames = numFrames - promptFrames
    if (keptFrames <= 0 || featDim <= 0) return FloatArray(0)
    val out = FloatArray(keptFrames * featDim)
    for (t in 0 until keptFrames) {
        val src = (promptFrames + t) * featDim
        for (c in 0 until featDim) {
            out[c * keptFrames + t] = x[src + c] * invFeatScale
        }
    }
    return out
}
