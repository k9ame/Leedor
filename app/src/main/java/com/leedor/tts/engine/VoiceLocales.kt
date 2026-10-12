package com.leedor.tts.engine

import java.util.Locale

/**
 * 语音 ID → Locale 的映射。
 *
 * 各引擎的语音 ID 命名约定完全不同，Android TTS 框架又要求每个 [android.speech.tts.Voice]
 * 必须带一个准确的 Locale，否则系统无法把中文请求路由到本引擎。这里统一做归一化。
 *
 * 已知的命名约定：
 *  - **Piper**：`{lang}_{COUNTRY}-{name}-{quality}`
 *    例如 `en_US-amy-low`、`zh_CN-huayan-medium`、`ta_IN-Valluvar-medium`
 *  - **Kokoro**：`{a|b}{f|m}_{name}`，`a`=美式英语、`b`=英式英语
 *    例如 `af_heart`、`bm_george`
 *  - **Pocket-TTS / 名人语音 / Kitten / 克隆语音**：无语言信息，全部按英语处理
 *
 * 注意：克隆语音（用户自录音）的语言无法从 ID 推断，也归入 [FALLBACK]。
 * 这只会影响框架对"该语音属于哪种语言"的判断，不影响实际音频。
 */
object VoiceLocales {

    /** Piper：`zh_CN-huayan-medium` */
    private val PIPER_ID = Regex("([a-z]{2})_([A-Za-z]{2})-.+")

    /** Kokoro：`af_heart` / `bm_george`（英文） */
    private val KOKORO_ID = Regex("([ab])[fm]_.+")

    /**
     * Kokoro v1.1-zh 的中文语音：`zf_001` / `zm_010` 等。
     * `z` 代表中文（zh），`f`/`m` 是性别。
     */
    private val KOKORO_ZH_ID = Regex("z[fm]_.+")

    /**
     * 无法推断时的兜底。
     * 内置的非 Piper 语音（Pocket-TTS、名人、Kitten）都是英语，所以取 en-US。
     */
    val FALLBACK: Locale = Locale.US

    /** 本引擎对外声明支持的语言。 */
    val SUPPORTED: List<Locale> = listOf(
        Locale.SIMPLIFIED_CHINESE,
        Locale.US,
        Locale.UK,
        Locale("en", "AU"),
        Locale("en", "IN")
    )

    /** 根据语音 ID 推断它使用的 Locale。 */
    fun localeFor(voiceId: String?): Locale = localeFor(voiceId, null)

    /**
     * 根据语音 ID 推断它使用的 Locale，[modelId] 已知时优先按**模型**判断。
     *
     * 为什么需要 modelId：ZipVoice 的语音就是参考音频，名字来自用户
     * （`leijun-1`、`news-female`，或者随便录的一段），光看名字永远推不出语言。
     * 它本身是中英双语模型，所以统一按中文报给框架 —— 否则系统的中文请求
     * 会因为"这个语音是 en-US"而路由不到本引擎。
     */
    fun localeFor(voiceId: String?, modelId: String?): Locale {
        if (modelId == "zipvoice_zh_en") return Locale.SIMPLIFIED_CHINESE
        if (voiceId.isNullOrBlank()) return FALLBACK

        PIPER_ID.matchEntire(voiceId)?.let { m ->
            val lang = m.groupValues[1]
            val country = m.groupValues[2].uppercase(Locale.ROOT)
            if (lang.length == 2 && country.length == 2) {
                return Locale(lang, country)
            }
        }

        // Kokoro v1.1-zh 的中文语音
        if (KOKORO_ZH_ID.matchEntire(voiceId) != null) {
            return Locale.SIMPLIFIED_CHINESE
        }

        KOKORO_ID.matchEntire(voiceId)?.let { m ->
            return when (m.groupValues[1]) {
                "a" -> Locale.US
                "b" -> Locale.UK
                else -> FALLBACK
            }
        }

        return FALLBACK
    }

    /**
     * 把 Android TTS 框架传来的语言/国家代码归一化成 Locale。
     *
     * 框架传的通常是 ISO 639-2/3166-1 alpha-3（`eng`/`USA`、`zho`/`CHN`），
     * 但也可能是 alpha-2（`zh`/`CN`）；中文还可能是 ISO 639-3 的 `cmn`。
     * 返回 null 表示本引擎不支持该语言。
     */
    fun normalize(lang: String?, country: String?): Locale? {
        val l = lang?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (l.isEmpty()) return null
        val c = countryToIso2(country)

        return when (l) {
            // 中文：zho/chi 是 ISO 639-2，cmn 是 ISO 639-3（普通话），chs 是历史遗留
            "zh", "zho", "chi", "cmn", "chs" -> Locale.SIMPLIFIED_CHINESE
            "en", "eng" -> when (c) {
                "GB" -> Locale.UK
                "AU" -> Locale("en", "AU")
                "IN" -> Locale("en", "IN")
                else -> Locale.US
            }
            else -> null
        }
    }

    /** ISO3 国家代码（框架用）→ ISO2（Locale 用），便于比较。 */
    fun countryToIso2(country: String?): String {
        val c = country?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return when (c) {
            "USA" -> "US"
            "GBR" -> "GB"
            "CHN" -> "CN"
            "AUS" -> "AU"
            "IND" -> "IN"
            "SGP" -> "SG"
            "CAN" -> "CA"
            "NZL" -> "NZ"
            "ZAF" -> "ZA"
            "HKG" -> "HK"
            "TWN" -> "TW"
            else -> c
        }
    }

    /** 把 Locale 转成框架期望的 [ISO3语言, ISO3国家, variant] 三元组。 */
    fun toFrameworkTriple(locale: Locale): Array<String> {
        val lang = runCatching { locale.isO3Language }.getOrNull().orEmpty()
        val country = runCatching { locale.isO3Country }.getOrNull().orEmpty()
        return arrayOf(
            lang.ifEmpty { locale.language },
            country.ifEmpty { locale.country },
            ""
        )
    }
}
