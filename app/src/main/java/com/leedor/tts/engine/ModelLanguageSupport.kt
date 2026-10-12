package com.leedor.tts.engine

/**
 * 各引擎 / 语音的**中文能力标注**（唯一事实来源）。
 *
 * 这里刻意区分两个概念，混淆它们会得出错误结论：
 *
 * 1. [modelSupportsChinese] —— **模型本身**是否被训练过中文。
 * 2. [modelCanSpeakChinese] —— **本应用当前的链路**能否真的把中文读出来。
 *    模型支持只是必要条件；还需要「字 → 音」（G2P）那一环也支持。
 *
 * 两者都成立，中文朗读才真的可用。
 *
 * 实测依据：
 * | 引擎 | 模型支持中文 | 本应用可用 | 说明 |
 * |---|---|---|---|
 * | Piper | ✅ 2 个语音 | ✅ | 从模型自带 `.onnx.json` 读 `espeak.voice="cmn"`，非英语走 eSpeak |
 * | Kokoro v1.1-zh | ✅ 103 个语音 | ✅ | 预编译词典 + 最长匹配 |
 * | ZipVoice | ✅ | ✅ | 词典前端，且支持零样本音色克隆 |
 * | Pocket-TTS | ❌ | ❌ | 只训练了英语（已从界面隐藏） |
 * | Kitten Nano | ❌ | ❌ | 只训练了英语（已从界面隐藏） |
 *
 * Kokoro v1.0 是纯英文旧模型，已从 `assets/models.json` 下架，这里不再维护它的分支。
 */
object ModelLanguageSupport {

    /** Piper 中文语音 ID：`zh_CN-huayan-medium` / `zh_CN-huayan-x_low` */
    private val PIPER_CHINESE = Regex("zh_[A-Z]{2}-.+")

    /**
     * Kokoro 中文语音 ID（上游 v1.0 共 8 个）：
     * 女声 `zf_xiaobei` / `zf_xiaoni` / `zf_xiaoxiao` / `zf_xiaoyi`
     * 男声 `zm_yunjian` / `zm_yunxi` / `zm_yunxia` / `zm_yunyang`
     */
    private val KOKORO_CHINESE = Regex("z[fm]_.+")

    /** 随 APK 打包的语音（离线可用）。 */
    const val BUNDLED_VOICE = "en_US-amy-low"

    /** 推荐的中文语音。 */
    const val RECOMMENDED_CHINESE_VOICE = "zh_CN-huayan-x_low"

    // ------------------------------------------------------------------
    // 引擎（模型）级
    // ------------------------------------------------------------------

    /** 模型本身是否原生支持中文。 */
    fun modelSupportsChinese(modelId: String?): Boolean = when {
        modelId == null -> false
        modelId.startsWith("piper") -> true    // zh_CN-huayan-*
        modelId == "kokoro_v1.1-zh" -> true    // zf_*/zm_* 共 103 个（55 女 + 45 男 + 3 英）
        modelId == "zipvoice_zh_en" -> true    // 零样本克隆，中英
        else -> false                          // pocket_v1 / kitten_nano
    }

    /**
     * 本应用当前能否真的用中文驱动该引擎。
     *
     * 判断依据是「字→音（G2P）链路是否打通」，而不是模型有没有中文语音：
     *
     * | 引擎 | 中文 G2P | 可用 |
     * |---|---|---|
     * | Piper | 模型自带 `espeak.voice="cmn"`，非英语直接走 eSpeak | ✅ |
     * | **Kokoro v1.1-zh** | **预编译词典 `lexicon-zh.txt` + 最长匹配**（见 [com.leedor.tts.engine.frontend]） | ✅ |
     * | **ZipVoice** | 同上的词典前端（`MatchaFrontend`），且支持零样本音色克隆 | ✅ |
     * | Kokoro v1.0 | 上游有 8 个中文语音，但本应用只移植了英文 Misaki，且 `KokoroEngine` 走的是 v1.0 词表 | ❌ |
     * | Pocket / Kitten | 只训练了英语 | ❌ |
     */
    fun modelCanSpeakChinese(modelId: String?): Boolean = when {
        modelId == null -> false
        modelId.startsWith("piper") -> true
        modelId == "kokoro_v1.1-zh" -> true
        // ZipVoice 走 MatchaTtsLexicon 风格的词典前端，中英都支持
        modelId == "zipvoice_zh_en" -> true
        else -> false
    }

    /**
     * 该模型是否支持**零样本语音克隆**（录一段参考音频就能用那个音色朗读）。
     *
     * - ZipVoice：支持，中英都能克隆
     * - Pocket-TTS：支持，但只训练了英语，且已从界面隐藏
     * - Piper / Kokoro：不支持（它们是固定音色）
     */
    fun supportsCloning(modelId: String?): Boolean = when (modelId) {
        "zipvoice_zh_en", "pocket_v1" -> true
        else -> false
    }

    // ------------------------------------------------------------------
    // 语音级
    // ------------------------------------------------------------------

    /** 该语音是否是中文语音（只看模型能力，不看链路）。 */
    fun voiceIsChinese(voiceId: String?): Boolean {
        if (voiceId.isNullOrBlank()) return false
        return PIPER_CHINESE.matches(voiceId) || KOKORO_CHINESE.matches(voiceId)
    }

    /** 该语音的中文朗读当前是否真的可用。 */
    fun voiceChineseUsable(voiceId: String?): Boolean {
        if (voiceId.isNullOrBlank()) return false
        // Piper 的 zh_CN-huayan-*，以及 Kokoro v1.1-zh 的 zf_*/zm_*
        return PIPER_CHINESE.matches(voiceId) || KOKORO_CHINESE.matches(voiceId)
    }

    /**
     * 该语音中文是否「模型支持但当前不可用」。
     * 用于在界面上给出准确提示，而不是假装能用。
     */
    fun voiceChineseModelOnly(voiceId: String?): Boolean =
        voiceIsChinese(voiceId) && !voiceChineseUsable(voiceId)
}
