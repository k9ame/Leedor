package com.leedor.tts.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PrefsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        /** Shared with NekoTtsService — keep as the single source of truth. */
        const val PREFS_NAME = "leedor_prefs"
        private const val KEY_VOICE = "current_voice"
        private const val KEY_MODEL = "current_model"
        private const val KEY_THREADS = "cpu_threads"
        private const val KEY_SPEED = "speech_speed"
        private const val KEY_TOKEN_SIZE = "stream_token_size"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_THEME_COLOR = "app_theme_v2"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_POCKET_TEMP = "pocket_temperature"
        private const val KEY_POCKET_LSD = "pocket_lsd_steps"
        private const val KEY_POCKET_EOS = "pocket_frames_after_eos"
        private const val KEY_POCKET_DECODE = "pocket_decoding_mode"
        private const val KEY_POCKET_CHUNK = "pocket_decode_chunk_size"
        private const val KEY_DOWNLOAD_HOST = "download_host_mode"

        /** 默认中文语音（x_low：体积小、CPU 推理快，适合实时朗读）。 */
        const val DEFAULT_VOICE = "zh_CN-huayan-x_low"
        const val DEFAULT_MODEL = "piper_zh_CN-huayan-x_low"

        /**
         * 初始化失败时的回退顺序。
         *
         * 其中 `piper_en_US-amy-low` 是随 APK 打包的语音，必然会初始化成功，
         * 所以引擎总能至少有一个可用语音，不会出现"装好了却一句话读不出来"。
         */
        val FALLBACK_MODELS = listOf(
            DEFAULT_MODEL,
            "piper_zh_CN-huayan-medium",
            "piper_en_US-amy-low"
        )

        /**
         * 已下架模型的 id 映射到还在的模型。
         *
         * `kokoro_v1.0` 是纯英文的旧模型，已从 `assets/models.json` 删除
         * （它的语音表还曾让「选 Kokoro 1.1 → 进语音库 → 变回 1.0」）。
         */
        private val legacyModelAliases = mapOf(
            "kokoro_v1.0" to "kokoro_v1.1-zh"
        )
    }

    // Reactive counter for theme updates
    private val _themeCounter = MutableStateFlow(0)
    val themeCounter = _themeCounter.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_THEME_COLOR || key == KEY_DARK_MODE) {
            _themeCounter.value += 1
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 默认语音。侍读郎是中文朗读应用，所以默认指向中文 Piper 语音。
     * 模型文件需要放在 piper_downloads/ 下，见 docs/zh-models.md。
     * 若模型不存在，NekoTtsService 会按 [FALLBACK_MODELS] 顺序回退。
     */
    var currentVoice: String
        get() = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var currentModel: String
        get() {
            val stored = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
            // 已下架的模型：老用户 prefs 里可能还存着 kokoro_v1.0（英文，已从索引删除），
            // 引擎再拿到这个 id 只会去找已经不存在的文件。直接纠正成中文模型。
            legacyModelAliases[stored]?.let { return it }
            // 已隐藏的模型（Pocket-TTS / Kitten，都不支持中文）不再可用。
            // 老用户 prefs 里可能还存着它们，这里直接回退到默认中文模型，
            // 免得每次启动都要走一遍注定失败的回退链。
            return if (ModelRepository.isHidden(stored)) DEFAULT_MODEL else stored
        }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var cpuThreads: Int
        get() = prefs.getInt(KEY_THREADS, 6)
        set(value) = prefs.edit().putInt(KEY_THREADS, value).apply()

    var speechSpeed: Float
        get() = prefs.getFloat(KEY_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value).apply()

    var streamTokenSize: Int
        get() = prefs.getInt(KEY_TOKEN_SIZE, 0)
        set(value) = prefs.edit().putInt(KEY_TOKEN_SIZE, value).apply()

    var isOnboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value).apply()
        
    var appTheme: String
        get() = prefs.getString(KEY_THEME_COLOR, "BLUE") ?: "BLUE"
        set(value) = prefs.edit().putString(KEY_THEME_COLOR, value).apply()
    
    var darkMode: String
        get() = prefs.getString(KEY_DARK_MODE, "FOLLOW_SYSTEM") ?: "FOLLOW_SYSTEM"
        set(value) = prefs.edit().putString(KEY_DARK_MODE, value).apply()

    // Restored Pocket-TTS settings
    var pocketTemperature: Float
        get() = prefs.getFloat(KEY_POCKET_TEMP, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_POCKET_TEMP, value).apply()
    
    var pocketLsdSteps: Int
        get() = prefs.getInt(KEY_POCKET_LSD, 10)
        set(value) = prefs.edit().putInt(KEY_POCKET_LSD, value).apply()
    
    var pocketFramesAfterEos: Int
        get() = prefs.getInt(KEY_POCKET_EOS, 3)
        set(value) = prefs.edit().putInt(KEY_POCKET_EOS, value).apply()
    
    var pocketDecodingMode: String
        get() = prefs.getString(KEY_POCKET_DECODE, "batch") ?: "batch"
        set(value) = prefs.edit().putString(KEY_POCKET_DECODE, value).apply()
    
    var pocketDecodeChunkSize: Int
        get() = prefs.getInt(KEY_POCKET_CHUNK, 15)
        set(value) = prefs.edit().putInt(KEY_POCKET_CHUNK, value).apply()

    /**
     * 下载主机模式：auto / official / mirror。
     * 默认 auto —— 镜像优先，官方源兜底。见 [DownloadHosts]。
     */
    var downloadHostMode: String
        get() = prefs.getString(KEY_DOWNLOAD_HOST, DownloadHosts.MODE_AUTO) ?: DownloadHosts.MODE_AUTO
        set(value) {
            prefs.edit().putString(KEY_DOWNLOAD_HOST, value).apply()
            // 模式变了，之前探测出来的主机作废
            DownloadHosts.invalidate()
        }
}
