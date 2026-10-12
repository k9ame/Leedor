package com.leedor.tts.engine

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.zip.ZipInputStream

class KokoroEngine(
    private val context: Context,
    /** 模型 id，来自 assets/models.json。决定走英文音素器还是多语言词典前端。 */
    // 必须显式指定模型：以前默认值是 "kokoro_v1.0"，
    // 而 v1.0 已下架，任何漏传的地方都会去加载不存在的文件。
    private val modelId: String
) : TtsEngine {
    
    companion object {
        private const val TAG = "KokoroEngine"
        const val SAMPLE_RATE = 24000
        const val MAX_TOKENS = 150 // Increased to 150 for better context and fewer gaps
        const val STYLE_DIM = 256
        
        // Kokoro files - downloaded by ModelRepository to filesDir root
        private const val MODEL_KOKORO_ASSET = "kokoro/kokoro-v1.0.int8.onnx"  // For assets fallback (not bundled)
        private const val MODEL_KOKORO_FILE = "kokoro-v1.0.int8.onnx"  // Match ModelRepository download path
        private const val VOICES_KOKORO_ASSET = "voices-v1.0.bin"  // Match ModelRepository download path
        
        // Kitten Assets - downloaded by ModelRepository to filesDir root
        private const val MODEL_KITTEN_ASSET = "kitten/kitten_tts_nano_v0_1.onnx"  // For assets fallback (not bundled)
        private const val MODEL_KITTEN_FILE = "kitten_tts_nano_v0_1.onnx"  // Match ModelRepository
        private const val VOICES_KITTEN_ASSET = "voices.npz"  // Match ModelRepository download path

        // ---- Kokoro v1.1-zh（多语言变体）----
        // 目录与文件名对应 assets/models.json 里声明的 path。
        // 注意是 fp32 导出：int8/uint8/quantized 三个文件含 ConvInteger（ORT 1.22 建不了会话），fp16
        // 会让音高在八度间乱跳（电音）。详见 assets/models.json 的 comment。

        private const val ML_DIR = "kokoro-v1.1-zh"
        private const val ML_MODEL_FILE = "$ML_DIR/model.onnx"
        private const val ML_VOICES_FILE = "$ML_DIR/voices.bin"
        private const val ML_TOKENS_FILE = "$ML_DIR/tokens.txt"
        private const val ML_LEXICON_ZH = "$ML_DIR/lexicon-zh.txt"
        private const val ML_LEXICON_EN = "$ML_DIR/lexicon-us-en.txt"

        /** 单个语音的 style 向量长度：510 × 256 个 float。 */
        private const val ML_STYLE_VECTORS = 510
        private const val ML_VOICE_FLOATS = ML_STYLE_VECTORS * STYLE_DIM

        /** v1.1-zh 的上下文上限，比 v1.0 的 150 大得多。 */
        private const val ML_MAX_TOKENS = 512
    }
    
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var voiceCache = mutableMapOf<String, FloatArray>()
    private var initialized = false
    private var currentVoice = "af_heart"
    private var currentModelInfo = Triple(MODEL_KOKORO_ASSET, MODEL_KOKORO_FILE, VOICES_KOKORO_ASSET)

    /** 多语言（v1.1-zh）专用：词典前端 + 语音名顺序。 */
    private var multiLangFrontend: com.leedor.tts.engine.frontend.KokoroMultiLangFrontend? = null
    private var multiLangVoiceOrder: List<String> = emptyList()
    private var multiLangVoiceBin: java.io.RandomAccessFile? = null
    private var multiLangTokens: com.leedor.tts.engine.frontend.TokenTable? = null
    private var multiLangLexiconZh: com.leedor.tts.engine.frontend.Lexicon? = null
    private var multiLangLexiconEn: com.leedor.tts.engine.frontend.Lexicon? = null
    private var espeakWrapper: com.leedor.tts.engine.EspeakWrapper? = null

    private val isMultiLang: Boolean get() = modelId == "kokoro_v1.1-zh"
    
    private var inputIdsName = "input_ids"
    private var styleName = "style"
    private var speedName = "speed"
    private var useIntSpeed = false
    
    private var availableVoices = mutableListOf<String>()
    
    private var phonemizer: Phonemizer? = null
    
    @Volatile private var stopFlag = false
    
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefs = com.leedor.tts.data.PrefsManager(context)
            val threads = prefs.cpuThreads
            // 用构造参数而不是 prefs：同一个进程里可能先后创建不同模型的引擎实例
            val modelName = modelId

            Log.i(TAG, "Initializing Engine. Model: $modelName (multiLang=$isMultiLang), Threads: $threads")

            val isKitten = (modelName == "kitten_nano")
            currentModelInfo = when {
                isMultiLang -> Triple("", ML_MODEL_FILE, ML_VOICES_FILE)
                isKitten -> Triple(MODEL_KITTEN_ASSET, MODEL_KITTEN_FILE, VOICES_KITTEN_ASSET)
                else -> Triple(MODEL_KOKORO_ASSET, MODEL_KOKORO_FILE, VOICES_KOKORO_ASSET)
            }

            val (modelAsset, modelFileName, voicesAssetOrFile) = currentModelInfo

            // v1.1-zh 走「词典最长匹配」前端；v1.0 / Kitten 走原来的英文 Misaki 音素器
            if (isMultiLang) {
                if (!initMultiLangFrontend()) return@withContext false
            } else {
                phonemizer = Phonemizer(context)
                phonemizer?.load()
            }
            ortEnv = OrtEnvironment.getEnvironment()

            // Model file path - Kokoro downloads to filesDir root, Kitten to kitten subfolder
            val modelFile = File(context.filesDir, modelFileName)

            if (!modelFile.exists() || modelFile.length() < 10 * 1024 * 1024) {
                if (modelAsset.isEmpty()) {
                    Log.e(TAG, "Model not found: $modelFileName. Download required via Settings.")
                    return@withContext false
                }
                // Try to extract from assets (works for bundled models like Kitten)
                try {
                    Log.i(TAG, "Extracting model $modelFileName from assets...")
                    context.assets.open(modelAsset).use { input ->
                        modelFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 8192)
                        }
                    }
                } catch (e: Exception) {
                    // Not bundled - should be downloaded via ModelRepository
                    Log.e(TAG, "Model not found: $modelFileName. Download required via Settings.")
                    return@withContext false
                }
            }

            // Scan voices - try filesDir first (downloaded), then assets (bundled)
            availableVoices.clear()
            val voicesFile = File(context.filesDir, voicesAssetOrFile)

            if (isMultiLang) {
                // v1.1-zh 的 voices.bin 是**裸 float32 拼接**，没有名字。
                // 名字等 session 建好后填：优先 ONNX 元数据，没有则用索引。
                // voices.bin 是**可选**的：没有它也能工作，
                // 因为语音可以按需从 voices/<名字>.bin 单独下载。
                if (voicesFile.exists()) {
                    val expected = voicesFile.length()
                    val voices = expected / (ML_VOICE_FLOATS * 4L)
                    Log.i(TAG, "v1.1-zh voices.bin: $expected bytes -> 可容纳 $voices 个语音")
                    multiLangVoiceBin?.close()
                    multiLangVoiceBin = java.io.RandomAccessFile(voicesFile, "r")
                } else {
                    Log.i(TAG, "没有 voices.bin，将按需下载单个语音（voices/<名字>.bin）")
                }
            } else {
                val voicesInput: java.io.InputStream = if (voicesFile.exists()) {
                    Log.i(TAG, "Loading voices from downloaded file: ${voicesFile.absolutePath}")
                    voicesFile.inputStream()
                } else {
                    // Try assets (for bundled models)
                    try {
                        Log.i(TAG, "Loading voices from assets: $voicesAssetOrFile")
                        context.assets.open(voicesAssetOrFile)
                    } catch (e: Exception) {
                        Log.e(TAG, "Voices not found: $voicesAssetOrFile. Download required via Settings.")
                        return@withContext false
                    }
                }

                voicesInput.use { fis ->
                    ZipInputStream(fis).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (entry.name.endsWith(".npy")) {
                                availableVoices.add(entry.name.removeSuffix(".npy"))
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
                availableVoices.sort()
            }
            Log.i(TAG, "Found ${availableVoices.size} voices")
            
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            
            // Use byte array loading on 32-bit ARM to avoid mmap alignment issues
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            val is32BitArm = abi == "armeabi-v7a" || abi == "armeabi"
            
            ortSession = if (is32BitArm) {
                Log.i(TAG, "Using byte array loading for 32-bit ARM compatibility")
                val modelBytes = modelFile.readBytes()
                ortEnv?.createSession(modelBytes, options)
            } else {
                ortEnv?.createSession(modelFile.absolutePath, options)
            }
            
            // Introspect inputs
            val inputNames = ortSession?.inputNames ?: emptySet()
            Log.i(TAG, "Model Inputs: $inputNames")
            
            if ("tokens" in inputNames) inputIdsName = "tokens"
            if ("input_ids" in inputNames) inputIdsName = "input_ids"
            
            val speedInfo = ortSession?.inputInfo?.get("speed")?.info
            if (speedInfo is TensorInfo) {
                if (speedInfo.type == OnnxJavaType.INT32 || speedInfo.type == OnnxJavaType.INT64) {
                    useIntSpeed = true
                }
            }
            
            // v1.1-zh：填语音列表（优先 ONNX 元数据 speaker_names，回退索引 voices），
            // 顺序与 voices.bin 里语音的排列一一对应（都是 510x256 float32 顺序拼接）。
            if (isMultiLang) {
                loadMultiLangVoiceNames()
                if (availableVoices.isEmpty()) {
                    Log.e(TAG, "v1.1-zh 语音列表为空：ONNX 元数据没有 speaker_names，索引里也没登记 voices")
                    return@withContext false
                }
            }

            // Reset voice selection if needed
            currentVoice = prefs.currentVoice
            if (currentVoice !in availableVoices && availableVoices.isNotEmpty()) {
                currentVoice = availableVoices[0]
                prefs.currentVoice = currentVoice // Update pref
            }
            
            Log.i(TAG, "Loading saved voice: $currentVoice")
            loadVoice(currentVoice)
            
            initialized = true
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Init CRITICAL FAILURE", e)
            e.printStackTrace()
            false
        }
    }
    
    // ==================================================================
    // Kokoro v1.1-zh 多语言支持
    // ==================================================================

    /**
     * 加载词典前端：tokens.txt + lexicon-zh.txt + lexicon-us-en.txt。
     *
     * 中文 G2P 完全靠 lexicon 最长匹配，不需要 jieba / pypinyin —— 这是 sherpa-onnx
     * 把 G2P 离线预编译进词典的做法，Android 端只剩查表。
     * 英文/OOV 复用 APK 内置的 espeak-ng-data。
     */
    private fun initMultiLangFrontend(): Boolean {
        return try {
            val tokensFile = File(context.filesDir, ML_TOKENS_FILE)
            if (!tokensFile.exists()) {
                Log.e(TAG, "缺少 $ML_TOKENS_FILE，需要先在设置里下载模型")
                return false
            }
            val tokens = com.leedor.tts.engine.frontend.TokenTable.fromFile(tokensFile)

            // 中文词典是必需的
            val lexZhFile = File(context.filesDir, ML_LEXICON_ZH)
            if (!lexZhFile.exists()) {
                Log.e(TAG, "缺少 $ML_LEXICON_ZH，需要先在设置里下载模型")
                return false
            }
            val lexZh = com.leedor.tts.engine.frontend.Lexicon.fromFile(lexZhFile, tokens)

            // 英文词典可选（中英混排时用），没有就退到 eSpeak
            val lexEnFile = File(context.filesDir, ML_LEXICON_EN)
            val lexEn = if (lexEnFile.exists()) {
                com.leedor.tts.engine.frontend.Lexicon.fromFile(lexEnFile, tokens)
            } else {
                Log.w(TAG, "未找到 $ML_LEXICON_EN，英文将全部走 eSpeak")
                com.leedor.tts.engine.frontend.Lexicon.fromLines(emptySequence(), tokens)
            }

            // 合并成一份查表结构（中文词典优先，与 sherpa 的多词典加载一致）
            val merged = lexZh.mergedWith(lexEn)

            multiLangTokens = tokens
            multiLangLexiconZh = lexZh
            multiLangLexiconEn = lexEn

            multiLangFrontend = com.leedor.tts.engine.frontend.KokoroMultiLangFrontend(
                tokens = tokens,
                lexicon = merged,
                maxTokenLen = ML_MAX_TOKENS,
                oovPhonemizer = { text -> espeakPhonemes(text) }
            )
            Log.i(TAG, "多语言前端已就绪：tokens=${tokens.size}, 合并词条=${merged.size}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "初始化多语言前端失败", t)
            false
        }
    }

    /**
     * 非中文（英文 / 中文 OOV）的音素化：走 eSpeak。
     * 返回单个音素 token 列表，直接用于查 tokens.txt。
     */
    private fun espeakPhonemes(text: String): List<String> {
        val espeak = espeakWrapper ?: run {
            espeakWrapper = com.leedor.tts.engine.EspeakWrapper()
            espeakWrapper
        }
        val dataDir = File(context.filesDir, "espeak-ng-data")
        if (!dataDir.exists()) {
            com.leedor.tts.utils.AssetUtils.extractAssets(context, "espeak-ng-data", context.filesDir)
        }
        espeak?.initializeSafe(context.filesDir.absolutePath)
        val ipa = espeak?.textToPhonemesSafe(text, "en-us") ?: return emptyList()
        // eSpeak 返回的是 IPA 字符串，按码点拆成单个音素 token
        return com.leedor.tts.engine.frontend.TextUtils.splitUtf8(ipa)
    }

    /**
     * 填充多语言语音列表。
     *
     * 优先读 ONNX 元数据的 `speaker_names`（sherpa 的导出会写），
     * 没有就回退到索引里登记的 `voices` 列表 ——
     * **onnx-community 的导出没有这个元数据字段**（实测确认），
     * 而它正是 ModelScope 上那个仓库用的导出方式。
     */
    private fun loadMultiLangVoiceNames() {
        val meta: Map<String, String> = ortSession?.metadata?.customMetadata ?: emptyMap()
        var names = meta["speaker_names"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        if (names.isEmpty()) {
            names = com.leedor.tts.data.ModelRepository.find(modelId)?.voices ?: emptyList()
            if (names.isNotEmpty()) {
                Log.i(TAG, "ONNX 元数据没有 speaker_names，改用索引里登记的 ${names.size} 个语音")
            }
        }

        availableVoices.clear()
        availableVoices.addAll(names)
        multiLangVoiceOrder = names
        Log.i(TAG, "从模型元数据读到 ${names.size} 个语音名（前 3: ${names.take(3)}）")

        // 校验 voices.bin 大小是否匹配
        val bin = multiLangVoiceBin
        if (bin != null) {
            val fit = bin.length() / (ML_VOICE_FLOATS * 4L)
            if (fit != names.size.toLong()) {
                Log.w(TAG, "voices.bin 可容纳 $fit 个语音，但元数据声明 ${names.size} 个 —— 以较小者为准")
                if (fit < names.size) {
                    availableVoices.retainAll(names.take(fit.toInt()).toSet())
                }
            }
        }
    }

    /**
     * v1.1-zh：读取一个语音的 style 数据。
     *
     * 两种来源，优先用后者：
     *  1. **整包** `voices.bin`（sherpa 的 HF 仓库，51 MB）—— 按索引偏移读
     *  2. **单文件** `voices/<名字>.bin`（ModelScope，0.5 MB/个）—— 按需下载
     *
     * 单文件方案只需下载用户实际选中的语音，比整包省 50 MB。
     */
    private fun loadMultiLangVoice(name: String): FloatArray {
        // 先看有没有按需下载的单文件。
        // 路径必须和下载端用同一个函数算，否则会「下载到 A 目录、去 B 目录找」。
        val single = com.leedor.tts.data.ModelRepository.voiceFile(context, modelId, name)
        if (!single.exists()) {
            // 没有就尝试下载（需要清单里配了 voiceUrlTemplate）
            val ok = try {
                kotlinx.coroutines.runBlocking {
                    com.leedor.tts.data.ModelRepository.downloadVoiceFile(context, modelId, name)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "按需下载语音 $name 失败", t)
                false
            }
            if (!ok) {
                Log.w(TAG, "语音 $name 既没有本地文件也下载失败")
            }
        }

        if (single.exists()) {
            val bytes = single.readBytes()
            if (bytes.size < ML_VOICE_FLOATS * 4) {
                throw IllegalStateException("语音文件 $name 大小异常: ${bytes.size}")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(ML_VOICE_FLOATS) { buffer.float }
            voiceCache[name] = floats
            Log.i(TAG, "从单文件加载语音 $name")
            return floats
        }

        // 回退到整包
        val idx = multiLangVoiceOrder.indexOf(name)
        if (idx < 0) throw IllegalArgumentException("未知语音: $name")
        val bin = multiLangVoiceBin ?: throw IllegalStateException(
            "语音 $name 不可用：既没有 voices/<名字>.bin，也没有 voices.bin"
        )

        val bytes = ByteArray(ML_VOICE_FLOATS * 4)
        bin.seek(idx.toLong() * ML_VOICE_FLOATS * 4)
        bin.readFully(bytes)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(ML_VOICE_FLOATS) { buffer.float }
        voiceCache[name] = floats
        return floats
    }

    private fun loadVoice(name: String): FloatArray {
        voiceCache[name]?.let { return it }
        if (isMultiLang) return loadMultiLangVoice(name)
        val voicesAssetOrFile = currentModelInfo.third
        
        // Try filesDir first (downloaded), then assets (bundled)
        val voicesFile = File(context.filesDir, voicesAssetOrFile)
        val voicesInput: java.io.InputStream = if (voicesFile.exists()) {
            voicesFile.inputStream()
        } else {
            context.assets.open(voicesAssetOrFile)
        }
        
        voicesInput.use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "$name.npy") {
                        val bytes = zis.readBytes()
                        
                        // Parse NPY header safely
                        // Magic: 0x93 NUMPY (6 bytes)
                        if (bytes.size < 10 || bytes[0] != 0x93.toByte() || String(bytes, 1, 5) != "NUMPY") {
                             // Fallback logic could go here
                        }
                        
                        var headerLen = 0
                        var offset = 0
                        
                        if (bytes[0] == 0x93.toByte() && String(bytes, 1, 5) == "NUMPY") {
                            val headerLenShort = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
                            headerLen = headerLenShort
                            offset = 10 + headerLen
                            if (headerLen % 64 == 0) { }
                        } else {
                            offset = 128
                        }
                        
                        if (offset >= bytes.size) throw IllegalStateException("Bad NPY file")
                        
                        val dataBytes = bytes.copyOfRange(offset, bytes.size)
                        val buffer = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                        val floats = FloatArray(dataBytes.size / 4) { buffer.float }
                        
                        voiceCache[name] = floats
                        return floats
                    }
                    entry = zis.nextEntry
                }
            }
        }
        throw IllegalArgumentException("Voice $name not found")
    }
    
    override suspend fun generate(
        text: String,
        speed: Float,
        voice: String?,
        callback: (FloatArray) -> Unit
    ) = withContext(Dispatchers.Default) {
        // Reset stop flag at start of new generation
        stopFlag = false

        val session = ortSession ?: throw IllegalStateException("Not initialized")
        val env = ortEnv ?: throw IllegalStateException("Not initialized")

        // v1.1-zh 的流水线结构不同（词典前端直接产出「每句一个 token 数组」），
        // 单独走一条路径，避免把已经能跑的 v1.0 英文链路改坏。
        if (isMultiLang) {
            generateMultiLang(text, speed, voice, env, session, callback)
            return@withContext
        }

        val phonemizer = phonemizer ?: throw IllegalStateException("Phonemizer not initialized")
        
        try {
            // 1. Initial rough split by sentence terminators
            val rawSentences = text.split(Regex("(?<=[.!?])\\s+|\\n+|(?<=[;])\\s+"))
            
            // 2. Intelligence check: Is the first sentence too long? (>75 chars triggers latency)
            val sentences = if (rawSentences.isNotEmpty() && rawSentences[0].length > 75) {
                val first = rawSentences[0]
                val rest = rawSentences.drop(1)
                
                // Try splitting by comma
                val subParts = first.split(Regex("(?<=[,])\\s+"))
                if (subParts.size > 1) {
                    subParts + rest
                } else {
                    // Hard split by words approx every 60 chars
                    listOf(first.take(60), first.drop(60)) + rest
                }
            } else {
                rawSentences
            }
            
            Log.d(TAG, "Input split into ${sentences.size} chunks. First chunk len: ${sentences.getOrNull(0)?.length ?: 0}")
            
            val voiceName = voice ?: currentVoice
            // Ensure voice data is loaded if we switched
            val voiceData = if (voiceCache.containsKey(voiceName)) {
                voiceCache[voiceName]!!
            } else {
                 loadVoice(voiceName)
            }
            val numVectors = voiceData.size / STYLE_DIM
            
            // Speed Logic
            val isKitten = currentModelInfo.first.contains("kitten")
            val prefs = com.leedor.tts.data.PrefsManager(context)
            
            // Assume UI preference takes precedence for Kitten, generated speed (1.0) for Kokoro
            val finalSpeed = if (isKitten) prefs.speechSpeed else 1.0f
            
            Log.d(TAG, "Generating with Speed: $finalSpeed (Model: ${if(isKitten) "Kitten" else "Kokoro"})")
            
            // Batching logic: Accumulate tokens to fill context window (MAX_TOKENS)
            val currentBatchTokens = ArrayList<Int>()
            var isFirstBatch = true
            
            for (sentence in sentences) {
                if (sentence.isBlank()) continue
                
                // G2P + Tokenization
                val startG2p = System.currentTimeMillis()
                val phonemes = phonemizer.phonemize(sentence, "en-us")
                val tokens = phonemizer.tokenize(phonemes)
                Log.d(TAG, "G2P time: ${System.currentTimeMillis() - startG2p}ms for ${tokens.size} tokens")
                
                if (tokens.isEmpty()) continue
                
                // Check for stop request
                if (stopFlag) return@withContext
                
                // PERFORMANCE FIX: Immediate flush for first sentence
                if (isFirstBatch) {
                    val startInf = System.currentTimeMillis()
                    processBatch(tokens, env, session, voiceData, numVectors, finalSpeed, useIntSpeed, startInf, callback)
                    Log.d(TAG, "First batch inference: ${System.currentTimeMillis() - startInf}ms")
                    isFirstBatch = false
                    continue
                }
                
                // Determine MAX_TOKENS based on preferences or model defaults
                val prefTokenSize = prefs.streamTokenSize
                val currentMaxTokens = if (prefTokenSize > 0) {
                    prefTokenSize
                } else {
                    // Auto: 150 for Kokoro (Balance context/latency), 400 for Kitten
                    if (currentModelInfo.first.contains("kitten")) 400 else 150
                }
                
                Log.v(TAG, "Streaming chunk size: $currentMaxTokens tokens")
                
                // If adding these tokens exceeds limit, process current batch first
                if (currentBatchTokens.size + tokens.size > currentMaxTokens - 2) { 
                    // Process accumulated batch
                    val startInf = System.currentTimeMillis()
                    processBatch(currentBatchTokens, env, session, voiceData, numVectors, finalSpeed, useIntSpeed, startInf, callback)
                    Log.d(TAG, "Batch inference: ${System.currentTimeMillis() - startInf}ms")
                    currentBatchTokens.clear()
                }
                
                // CRITICAL FIX: Never split a sentence's tokens arbitrarily. 
                // If a single sentence is larger than currentMaxTokens, we MUST process it as a whole 
                // to preserve phoneme context. Splitting mid-word causes stuttering/garbage.
                if (tokens.size > currentMaxTokens - 2) {
                     Log.w(TAG, "Sentence exceeds token limit (${tokens.size} > $currentMaxTokens). Processing as single large batch to avoid audio artifacts.")
                     // Process immediately as its own batch
                     val startInf = System.currentTimeMillis()
                     processBatch(tokens, env, session, voiceData, numVectors, finalSpeed, useIntSpeed, startInf, callback)
                } else {
                    // Normal case: append to buffer
                    currentBatchTokens.addAll(tokens)
                }
                
                // Check for cancellation between sentences
                if (!isActive || stopFlag) break
            }
            
            // Process remaining
            if (currentBatchTokens.isNotEmpty() && isActive && !stopFlag) {
                val startInf = System.currentTimeMillis()
                processBatch(currentBatchTokens, env, session, voiceData, numVectors, finalSpeed, useIntSpeed, startInf, callback)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            throw e
        }
    }
    
    override fun getSampleRate(): Int = SAMPLE_RATE
    override fun getVoices(): List<String> = availableVoices
    override fun release() {
        ortSession?.close()
        ortEnv?.close()
        ortSession = null
        initialized = false
    }
    override fun isInitialized(): Boolean = initialized
    
    override fun stop() {
        stopFlag = true
        Log.d(TAG, "Stop requested")
    }

    /**
     * v1.1-zh 的合成路径。
     *
     * 与 v1.0 的区别：文本前端直接产出「每句一个 token 数组」（首尾已带 pad），
     * 所以这里不需要再做句子切分与 token 累积，逐句送进 ONNX 即可。
     */
    private suspend fun generateMultiLang(
        text: String,
        speed: Float,
        voice: String?,
        env: OrtEnvironment,
        session: OrtSession,
        callback: (FloatArray) -> Unit
    ) {
        val frontend = multiLangFrontend ?: throw IllegalStateException("多语言前端未初始化")

        val voiceName = voice ?: currentVoice
        val voiceData = voiceCache[voiceName] ?: loadVoice(voiceName)
        val numVectors = voiceData.size / STYLE_DIM

        val startG2p = System.currentTimeMillis()
        val sentences = frontend.textToTokenIds(text)
        Log.d(TAG, "v1.1-zh G2P 用时 ${System.currentTimeMillis() - startG2p}ms，切出 ${sentences.size} 句")

        if (sentences.isEmpty()) {
            Log.w(TAG, "前端没有产出任何 token，文本可能全是词典外的字符: '${text.take(30)}'")
            return
        }

        for ((i, ids) in sentences.withIndex()) {
            if (stopFlag) return

            // 前端产出的数组首尾已经是 pad(0)，而 processBatch 会自己再补一层，
            // 所以这里剥掉首尾，避免出现双重 pad。
            if (ids.size <= 2) continue
            val inner = ids.toList().subList(1, ids.size - 1)

            val startInf = System.currentTimeMillis()
            processBatch(inner, env, session, voiceData, numVectors, speed, useIntSpeed, startInf, callback)
            Log.d(TAG, "v1.1-zh 第 ${i + 1}/${sentences.size} 句推理完成，${inner.size} tokens")
        }
    }

    private fun processBatch(
        tokens: List<Int>,
        env: OrtEnvironment,
        session: OrtSession,
        voiceData: FloatArray,
        numVectors: Int,
        speed: Float,
        useIntSpeed: Boolean,
        startTime: Long,
        callback: (FloatArray) -> Unit
    ) {
        val paddedTokens = LongArray(tokens.size + 2)
        paddedTokens[0] = 0
        tokens.forEachIndexed { i, t -> paddedTokens[i + 1] = t.toLong() }
        paddedTokens[paddedTokens.size - 1] = 0
        
        // Match style to chunk length
        val styleIdx = tokens.size.coerceIn(0, numVectors - 1)
        val offset = styleIdx * STYLE_DIM
        val style = voiceData.sliceArray(offset until offset + STYLE_DIM)
        
        val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(paddedTokens), longArrayOf(1, paddedTokens.size.toLong()))
        val styleTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, STYLE_DIM.toLong()))
        
        val speedTensor = if (useIntSpeed) {
             val intSpeed = (speed + 0.5f).toInt().coerceAtLeast(1)
             OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(intSpeed)), longArrayOf(1))
        } else {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1))
        }
        
        val inputs = mapOf(
            inputIdsName to inputIdsTensor,
            styleName to styleTensor,
            speedName to speedTensor
        )
        
        val outputs = session.run(inputs)
        val audioTensor = outputs[0] as OnnxTensor
        val floatBuffer = audioTensor.floatBuffer
        val audioData = FloatArray(floatBuffer.remaining())
        floatBuffer.get(audioData)
        
        val genTimeMs = System.currentTimeMillis() - startTime
        
        // Model Specific Trimming
        val finalAudio = if (currentModelInfo.first.contains("kitten")) {
            val trimStart = 3000
            val trimEnd = 4000
            val minLen = trimStart + trimEnd + 2400
            
            if (audioData.size > minLen) {
                audioData.sliceArray(trimStart until (audioData.size - trimEnd))
            } else {
                audioData
            }
        } else {
            // Kokoro trimming: Scan from end for first significant sample
            var lastIndex = audioData.lastIndex
            while (lastIndex > 0 && kotlin.math.abs(audioData[lastIndex]) < 0.01f) {
                lastIndex--
            }
            lastIndex = (lastIndex + 500).coerceAtMost(audioData.lastIndex)
            
            if (lastIndex < audioData.lastIndex) {
                 audioData.sliceArray(0..lastIndex)
            } else {
                 audioData
            }
        }
        
        val audioDurationSec = finalAudio.size.toFloat() / SAMPLE_RATE
        val rtf = genTimeMs.toFloat() / (audioDurationSec * 1000f)
        
        Log.d(TAG, "Batch Processed: ${tokens.size} tokens -> ${audioDurationSec}s (Trimmed from ${audioData.size}) in ${genTimeMs}ms")
        
        callback(finalAudio)
        
        inputIdsTensor.close()
        styleTensor.close()
        speedTensor.close()
        audioTensor.close()  // Close extracted tensor
        outputs.close()
    }
}
