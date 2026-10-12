package com.leedor.tts

import android.content.Intent
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.media.AudioFormat
import android.util.Log
import com.leedor.tts.support.SupportLogStore
import com.leedor.tts.engine.TtsEngine
import com.leedor.tts.engine.KokoroEngine
import com.leedor.tts.engine.VoiceLocales
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 侍读郎 Text-to-Speech Service
 * 
 * Provides TTS functionality using ONNX-based engines:
 * - Kokoro: Fast, simple TTS with multiple voices
 * - Pocket-TTS: Streaming, voice cloning support
 * - Piper: Multiple voice models
 * 
 * Thread-safety: Uses Mutex to serialize synthesis and engine reload.
 * This prevents "engine released while generating" races.
 */
class NekoTtsService : TextToSpeechService() {
    
    companion object {
        private const val TAG = "NekoTtsService"
        private const val INIT_TIMEOUT_MS = 5000L  // 5 seconds max wait for engine init

        /**
         * 请求中文、但用户尚未下载任何中文语音时回传的语音 ID。
         * 对应的模型文件需要放在 piper_downloads/ 下，否则 PiperEngine 初始化会失败。
         * 见 docs/zh-models.md。
         */
        const val DEFAULT_ZH_VOICE = "zh_CN-huayan-x_low"

        /** 汉字（含扩展 A 区与兼容区）。 */
        private val HAN = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    }
    
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "CRITICAL: Uncaught Coroutine Exception", throwable)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    
    // Engine access is serialized via synthMutex
    @Volatile private var currentEngine: TtsEngine? = null

    /**
     * 当前**真正加载成功**的模型 id（回退之后的结果，不一定等于用户选择的模型）。
     * 用来判断这个引擎能不能读中文。
     */
    @Volatile private var activeModelId: String? = null
    
    // Mutex for thread-safe engine access: covers engine swap AND synthesis
    // This prevents releasing an engine while it's synthesizing
    private val synthMutex = Mutex()
    
    // Per-request stop flag (global for simplicity - serialized access means only one request at a time)
    @Volatile private var stopRequested = false
    
    private var initJob: kotlinx.coroutines.Deferred<TtsEngine?>? = null

    /**
     * 最近一次 onLoadLanguage / onIsLanguageAvailable 确认过的语言。
     * 默认中文，因为这是本应用的主语言。
     */
    @Volatile private var currentLocale: Locale = Locale.SIMPLIFIED_CHINESE
    
    private lateinit var prefsManager: com.leedor.tts.data.PrefsManager
    
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "current_model", "cpu_threads" -> {
                Log.i(TAG, "Preference '$key' changed. Reloading engine...")
                reloadEngine()
            }
            "current_voice" -> Log.i(TAG, "Voice changed. Will be applied on next synthesis.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NekoTtsService created")
        SupportLogStore.log(this, TAG, "Service created")
        
        // Register Prefs Listener
        prefsManager = com.leedor.tts.data.PrefsManager(this)
        getSharedPreferences(com.leedor.tts.data.PrefsManager.PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        
        // Start engine initialization
        reloadEngine()
    }

    private fun reloadEngine() {
        // Cancel any pending init to avoid race conditions
        initJob?.cancel()
        
        initJob = serviceScope.async(Dispatchers.IO) {
            Log.i(TAG, "Starting engine initialization (Async)...")
            try {
                val prefs = com.leedor.tts.data.PrefsManager(applicationContext)
                val preferredModel = prefs.currentModel

                // 候选模型：先试用户当前选择的，再按回退顺序补齐。
                // 这样即使用户没下载中文模型，也能回退到随 APK 打包的 en_US-amy-low，
                // 不会出现"引擎装好了却一句话都读不出来"。
                val candidates = (listOf(preferredModel) + com.leedor.tts.data.PrefsManager.FALLBACK_MODELS)
                    .distinct()

                var newEngine: TtsEngine? = null
                var usedModel: String? = null

                for (model in candidates) {
                    if (!isActive) return@async null
                    Log.i(TAG, "Trying model '$model'...")

                    val engine = try {
                        com.leedor.tts.engine.EngineFactory.createEngine(applicationContext, model)
                    } catch (t: Throwable) {
                        Log.w(TAG, "createEngine('$model') threw", t)
                        continue
                    }

                    val ok = try {
                        engine.initialize()
                    } catch (t: Throwable) {
                        Log.w(TAG, "initialize('$model') threw", t)
                        false
                    }

                    if (ok) {
                        newEngine = engine
                        usedModel = model
                        break
                    }

                    Log.w(TAG, "Model '$model' failed to initialize; trying next fallback.")
                    runCatching { engine.release() }
                }

                if (newEngine == null) {
                    Log.e(TAG, "All candidate models failed to initialize: $candidates")
                    SupportLogStore.log(
                        applicationContext, TAG,
                        "Engine initialization failed for all candidates: $candidates"
                    )
                    // 保留旧引擎，不覆盖
                    return@async null
                }

                if (usedModel != preferredModel) {
                    Log.w(TAG, "Fell back from '$preferredModel' to '$usedModel'")
                    SupportLogStore.log(
                        applicationContext, TAG,
                        "Fell back to model '$usedModel' (preferred '$preferredModel' unavailable)"
                    )
                } else {
                    Log.i(TAG, "Engine initialized successfully with '$usedModel'.")
                    SupportLogStore.log(applicationContext, TAG, "Engine initialized successfully for model=$usedModel")
                }

                // Acquire mutex before swapping engines
                synthMutex.withLock {
                    val oldEngine = currentEngine
                    currentEngine = newEngine
                    activeModelId = usedModel

                    // Safely release old engine (no synthesis can be running while we hold mutex)
                    if (oldEngine != null && oldEngine !== newEngine) {
                        Log.i(TAG, "Releasing old engine instance.")
                        oldEngine.release()
                    }
                }

                newEngine
            } catch (t: Throwable) {
                Log.e(TAG, "CRITICAL: InitJob crashed", t)
                SupportLogStore.log(applicationContext, TAG, "Engine initialization crashed", t)
                // Don't swap on exception either
                null
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences(com.leedor.tts.data.PrefsManager.PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)

        runBlocking {
            synthMutex.withLock {
                currentEngine?.release()
                currentEngine = null
            }
        }
        serviceScope.cancel()
        Log.i(TAG, "NekoTtsService destroyed")
    }
    
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val locale = VoiceLocales.normalize(lang, country)
            ?: return TextToSpeech.LANG_NOT_SUPPORTED

        val voices = runCatching { onGetVoices() }.getOrDefault(emptyList())

        // 引擎尚未初始化完成时会拿不到语音列表。
        // 这种情况下按语言本身判断，避免系统在启动瞬间把中文误判为"不支持"。
        if (voices.isEmpty()) {
            return if (country.isNullOrBlank()) TextToSpeech.LANG_AVAILABLE
            else TextToSpeech.LANG_COUNTRY_AVAILABLE
        }

        val sameLanguage = voices.filter { it.locale.language == locale.language }
        if (sameLanguage.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED

        if (country.isNullOrBlank()) return TextToSpeech.LANG_AVAILABLE

        return if (sameLanguage.any { it.locale.country.equals(locale.country, ignoreCase = true) }) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        val locale = VoiceLocales.normalize(lang, country)

        if (locale != null) {
            return try {
                val availableVoices = currentEngine?.getVoices() ?: emptyList()
                val prefVoice = prefsManager.currentVoice
                // 传模型 id：ZipVoice 的语音名（`leijun-1` 之类）从名字推不出语言
                val modelForLocales = activeModelId ?: prefsManager.currentModel
                val sameLanguage = availableVoices.filter {
                    VoiceLocales.localeFor(it, modelForLocales).language == locale.language
                }

                when {
                    // 1. 用户当前选中的语音正好是请求的语言 → 用它
                    availableVoices.contains(prefVoice) &&
                        VoiceLocales.localeFor(prefVoice, modelForLocales).language == locale.language -> prefVoice

                    // 2. 该语言下有可用语音 → 取第一个
                    sameLanguage.isNotEmpty() -> sameLanguage.first()

                    // 3. 请求中文但用户还没下载中文语音：
                    //    回传默认中文语音 ID，由 PiperEngine 自行处理（找不到就回退）。
                    //    这样至少不会把中文请求静默地接到英文语音上。
                    locale.language == "zh" -> DEFAULT_ZH_VOICE

                    // 4. 其他语言：退回用户当前语音
                    availableVoices.contains(prefVoice) -> prefVoice
                    availableVoices.isNotEmpty() -> availableVoices.first()

                    else -> null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting default voice for $locale", e)
                if (locale.language == "zh") DEFAULT_ZH_VOICE else null
            }
        }

        return super.onGetDefaultVoiceNameFor(lang, country, variant)
    }

    override fun onGetLanguage(): Array<String> {
        return VoiceLocales.toFrameworkTriple(currentLocale)
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result != TextToSpeech.LANG_NOT_SUPPORTED) {
            VoiceLocales.normalize(lang, country)?.let { currentLocale = it }
        }
        return result
    }
    
    override fun onStop() {
        Log.d(TAG, "onStop called")
        stopRequested = true
        // Request engine to stop current generation
        currentEngine?.stop()
    }
    
    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString() ?: return
        val reqId = System.identityHashCode(request)
        
        Log.i(TAG, "[$reqId] onSynthesizeText received. Length: ${text.length} chars")
        Log.v(TAG, "[$reqId] Text: ${text.take(100)}...")
        
        stopRequested = false
        
        if (text.isBlank()) {
            Log.i(TAG, "[$reqId] Text is blank, done.")
            callback.done()
            return
        }
        
        // Wait for engine init with short timeout (don't block binder thread long)
        val engine = runBlocking {
            try {
                val job = initJob
                if (job == null) {
                    Log.e(TAG, "[$reqId] InitJob is null!")
                    return@runBlocking currentEngine  // Try current engine
                }
                
                kotlinx.coroutines.withTimeoutOrNull(INIT_TIMEOUT_MS) { 
                    job.await() 
                } ?: currentEngine  // If timeout, try current engine
            } catch (e: Exception) {
                Log.e(TAG, "[$reqId] Error waiting for engine init", e)
                currentEngine  // Fall back to current engine
            }
        }

        if (engine == null || !engine.isInitialized()) {
            Log.e(TAG, "[$reqId] Engine not initialized")
            SupportLogStore.log(this, TAG, "Synthesis failed: engine not initialized")
            callback.error()
            return
        }

        // 中文文本 + 不支持中文的引擎 = 汉字会被英文音素拼出毫无意义的音节。
        // 实测：模型初始化失败回退到 en_US-amy-low 之后，"你好，我是侍读郎。"
        // 被读成 "tʃˈaɪniːzlˌɛɾɚ tʃˈaɪniːzlˌɛɾɚ …"，也就是用户听到的 "chinese letter"。
        // 与其输出这种误导人的音频，不如明确报错（朗读软件会提示引擎出错）。
        val active = activeModelId
        if (HAN.containsMatchIn(text) &&
            !com.leedor.tts.engine.ModelLanguageSupport.modelCanSpeakChinese(active)
        ) {
            Log.e(
                TAG,
                "[$reqId] 文本含中文，但当前生效的模型是 '$active'（不支持中文），拒绝合成以免读出乱音"
            )
            SupportLogStore.log(
                this, TAG,
                "Rejected Chinese synthesis: active model '$active' has no Chinese G2P"
            )
            callback.error()
            return
        }
        
        // Get speech parameters
        val speechRate = request.speechRate / 100f
        
        // Determine voice with proper fallback chain
        // NOTE: For Pocket-TTS, celebrity/cloned voices may not be in availableVoices yet
        // because they are loaded on-demand. We pass the saved voice to the engine and
        // let it handle on-demand loading with its own fallback to alba.
        val requestedVoice = if (android.os.Build.VERSION.SDK_INT >= 21) request.voiceName else null
        val availableVoices = engine.getVoices()
        val prefs = com.leedor.tts.data.PrefsManager(this)
        val savedVoice = prefs.currentVoice

        // 本次请求的语言。中文请求绝不能静默地落到英文语音上。
        val requestedLocale = VoiceLocales.normalize(request.language, request.country)
        val voiceMatchingRequest = requestedLocale?.let { loc ->
            availableVoices.firstOrNull {
                VoiceLocales.localeFor(it, activeModelId ?: prefs.currentModel).language == loc.language
            }
        }

        val voiceToUse = when {
            // Use explicitly requested voice if available
            requestedVoice != null && availableVoices.contains(requestedVoice) -> requestedVoice
            // For Pocket-TTS celebrity/cloned voices: pass savedVoice directly
            // The engine will load on-demand or fall back to alba
            prefs.currentModel == "pocket_v1" -> savedVoice
            // 请求语言有对应语音时优先用它（例如系统请求中文、但当前保存的是英文语音）
            voiceMatchingRequest != null -> voiceMatchingRequest
            // For other models: check if saved voice is available
            availableVoices.contains(savedVoice) -> savedVoice
            // Default fallback
            availableVoices.isNotEmpty() -> availableVoices.first()
            else -> null
        }
        
        // Use engine's sample rate instead of hardcoded value
        val sampleRate = engine.getSampleRate()
        
        Log.i(TAG, "[$reqId] Starting synthesis. Voice: $voiceToUse, Rate: $speechRate, SampleRate: $sampleRate")
        
        // Start audio stream with engine's sample rate
        callback.start(
            sampleRate,
            AudioFormat.ENCODING_PCM_16BIT,
            1  // Mono
        )
        
        try {
            // Acquire mutex to prevent engine release during synthesis
            runBlocking {
                synthMutex.withLock {
                    // Re-check engine is still valid after acquiring lock
                    if (currentEngine != engine || !engine.isInitialized()) {
                        Log.w(TAG, "[$reqId] Engine changed during synthesis start")
                        return@withLock
                    }
                    
                    engine.generate(
                        text = text,
                        speed = speechRate.coerceIn(0.5f, 2.0f),
                        voice = voiceToUse
                    ) { samples ->
                        if (stopRequested) {
                            return@generate
                        }
                        
                        // Convert float samples to PCM 16-bit bytes (with rounding for quality)
                        val bytes = floatToPcm16(samples)
                        
                        // Stream audio in chunks
                        val maxBufferSize = callback.maxBufferSize
                        var offset = 0
                        while (offset < bytes.size) {
                            if (stopRequested) return@generate
                            val bytesToWrite = minOf(maxBufferSize, bytes.size - offset)
                            val ret = callback.audioAvailable(bytes, offset, bytesToWrite)
                            if (ret == TextToSpeech.ERROR) {
                                Log.w(TAG, "[$reqId] audioAvailable returned ERROR, stopping")
                                stopRequested = true
                                return@generate
                            }
                            offset += bytesToWrite
                        }
                    }
                }
            }
            
            // Always call done() to signal completion to the system
            callback.done()
            
            if (stopRequested) {
                Log.i(TAG, "[$reqId] Synthesis stopped by request")
            } else {
                Log.i(TAG, "[$reqId] Synthesis complete successfully")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "[$reqId] Synthesis error", e)
            callback.error()
        }
    }

    override fun onGetVoices(): List<Voice> {
        val engine = currentEngine ?: run {
            Log.w(TAG, "onGetVoices called but engine is null")
            return emptyList()
        }

        // 每个语音必须带上它真实的 Locale，否则系统无法把中文请求路由过来。
        // 映射规则见 VoiceLocales。
        val modelForLocales = activeModelId ?: com.leedor.tts.data.PrefsManager(this).currentModel
        return engine.getVoices().map { name ->
            Voice(
                name,
                VoiceLocales.localeFor(name, modelForLocales),
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                emptySet()  // 不用 features 表达性别
            )
        }
    }
    
    /**
     * Convert float audio samples [-1.0, 1.0] to PCM 16-bit bytes
     * Uses rounding instead of truncation for better audio quality
     */
    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            // Clamp and scale to 16-bit range with rounding
            val sample = (samples[i] * 32767f).roundToInt().coerceIn(-32768, 32767)
            // Little-endian byte order
            bytes[2 * i] = sample.toByte()
            bytes[2 * i + 1] = (sample shr 8).toByte()
        }
        return bytes
    }
}
