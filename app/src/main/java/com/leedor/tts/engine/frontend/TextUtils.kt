package com.leedor.tts.engine.frontend

/**
 * 文本预处理的公共工具 —— 供 Kokoro 与 ZipVoice 两条前端共用。
 *
 * 全部行为对齐 sherpa-onnx：
 *  - `kokoro-multi-lang-lexicon.cc` 的 `replace_str_pairs`
 *  - `matcha-tts-lexicon.cc` 的 `replace_str_pairs`
 *  - `text-utils.cc` 的 `SplitUtf8`
 */
object TextUtils {

    /**
     * 标点归一化表。
     *
     * ⚠️ **两套表不一样，而且顺序敏感**，这是刻意保留 sherpa 的原始行为：
     *
     * - Kokoro：`：` 出现在 `:` 之后，所以 `：` 最终留在 `:`（因为 `:`→`,` 已经跑过了）
     * - ZipVoice(Matcha)：`：` 直接映射到 `,`，且在 `:` 之前
     *
     * 顺序不能随意调整，否则输出会与 sherpa 的测试结果不一致。
     */
    private val KOKORO_PUNCT = listOf(
        "，" to ",", ":" to ",", "、" to ",", "；" to ";", "：" to ":",
        "。" to ".", "？" to "?", "！" to "!", "\\s+" to " "
    )

    private val MATCHA_PUNCT = listOf(
        "，" to ",", "、" to ",", "；" to ";", "：" to ",", ":" to ",",
        "。" to ".", "？" to "?", "！" to "!", "\\s+" to " "
    )

    enum class PunctuationStyle { KOKORO, MATCHA }

    /** 按指定风格归一化标点，并把连续空白压成一个空格。 */
    fun normalizePunctuation(text: String, style: PunctuationStyle): String {
        val table = when (style) {
            PunctuationStyle.KOKORO -> KOKORO_PUNCT
            PunctuationStyle.MATCHA -> MATCHA_PUNCT
        }
        var out = text
        for ((pattern, replacement) in table) {
            out = Regex(pattern).replace(out, replacement)
        }
        return out.trim()
    }

    /**
     * 拆成单个 Unicode 码点（对齐 sherpa 的 `SplitUtf8`）。
     *
     * 用码点而不是 `Char`，是为了正确处理 BMP 之外的字符（emoji、部分生僻字），
     * 避免把一个字符拆成两个代理对。
     */
    fun splitUtf8(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val count = Character.charCount(cp)
            out.add(text.substring(i, i + count))
            i += count
        }
        return out
    }

    /** 该字符（码点）是否为中日韩统一表意文字 —— 对应 sherpa 的 `[\u4e00-\u9fff]`。 */
    fun isChineseCodePoint(cp: Int): Boolean = cp in 0x4E00..0x9FFF

    fun isChinese(text: String): Boolean =
        text.isNotEmpty() && isChineseCodePoint(text.codePointAt(0))

    /**
     * 按「词」切分 —— 对齐 sherpa 的 `SplitUtf8`（它内部会调 `MergeCharactersIntoWords`）。
     *
     * 行为：
     *  - **ASCII 字母/数字**会被合并回单词：`"hello"` → `["hello"]`
     *  - **中文**保持单字：`"你好"` → `["你", "好"]`
     *  - **空格**被丢弃
     *  - **ASCII 标点**独立成项
     *  - `"你好world"` → `["你", "好", "world"]`
     *
     * 之所以不能简单地按空格切：中文没有空格，必须逐字处理，
     * 而英文又必须成词（否则每个字母都会去查词典）。
     */
    fun splitWords(text: String): List<String> {
        val chars = splitUtf8(text)
        val out = ArrayList<String>(chars.size)
        var prev = -1
        var i = 0
        val n = chars.size

        while (i < n) {
            val w = chars[i]
            val byteLen = utf8ByteLength(w)
            val standalone = byteLen >= 3 ||
                (byteLen == 2 && !isSpecial(w)) ||
                (byteLen == 1 && (isAsciiPunct(w) || w[0].isWhitespace()))

            if (standalone) {
                if (prev != -1) {
                    val sb = StringBuilder()
                    while (prev < i) { sb.append(chars[prev]); prev++ }
                    prev = -1
                    out.add(sb.toString())
                }
                if (!w[0].isWhitespace()) out.add(w)
                i++
                continue
            }

            // 1 字节的字母/数字，或 2 字节的欧洲变音字母 → 先攒着，稍后拼成词
            if (byteLen == 1 || (byteLen == 2 && isSpecial(w))) {
                if (prev == -1) prev = i
                i++
                continue
            }

            i++
        }

        if (prev != -1) {
            val sb = StringBuilder()
            while (prev < i) { sb.append(chars[prev]); prev++ }
            out.add(sb.toString())
        }
        return out
    }

    /** UTF-8 编码字节数（sherpa 靠它区分 ASCII / 变音字母 / CJK）。 */
    fun utf8ByteLength(s: String): Int {
        if (s.isEmpty()) return 0
        val cp = s.codePointAt(0)
        return when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
    }

    /** 对应 sherpa 的 `IsPunct`：`c != '\'' && ispunct(c)`，只针对 ASCII。 */
    private fun isAsciiPunct(s: String): Boolean {
        if (s.length != 1) return false
        val c = s[0]
        if (c.code >= 128) return false
        if (c == '\'') return false
        return !c.isLetterOrDigit() && !c.isWhitespace()
    }

    /**
     * 对应 sherpa 的 `IsSpecial` —— 2 字节的欧洲变音字母。
     *
     * sherpa 只精确列举了德语变音、西/法语的几个字符；这里放宽到
     * 整个 Latin-1 补充区的字母（`0xC3 0x80..0xBF`），
     * 效果是这些字符会与相邻字母拼成一个词，而不是被当成独立字符。
     * 对中英混排场景这是更稳妥的行为。
     */
    private fun isSpecial(s: String): Boolean {
        if (s.length != 1) return false
        val cp = s.codePointAt(0)
        // Latin-1 Supplement 字母区（À-ÿ），排除 ×(0xD7) 和 ÷(0xF7)
        return cp in 0xC0..0xFF && cp != 0xD7 && cp != 0xF7
    }

    /**
     * 把文本按「中文连续段 / 非中文连续段」切开，顺序保留。
     *
     * 对齐 sherpa 的 `([\u4e00-\u9fff]+)|([^\u4e00-\u9fff]+)`。
     * 返回 `Pair<是否中文, 片段>`。
     */
    fun splitByChinese(text: String): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        if (text.isEmpty()) return out

        val sb = StringBuilder()
        var current: Boolean? = null

        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val count = Character.charCount(cp)
            val isZh = isChineseCodePoint(cp)

            if (current == null) {
                current = isZh
            } else if (current != isZh) {
                out.add(current to sb.toString())
                sb.setLength(0)
                current = isZh
            }
            sb.appendRange(text, i, i + count)
            i += count
        }
        if (sb.isNotEmpty() && current != null) {
            out.add(current to sb.toString())
        }
        return out
    }

    /**
     * sherpa 认定的标点集合（`IsPunctuation`）。
     * 这些字符会作为**单个 token** 直接输出，不参与音素化。
     */
    private val PUNCTUATIONS = setOf(
        ";", ":", ",", ".", "!", "?", "—", "…", "\"", "(", ")", "“", "”"
    )

    fun isPunctuation(text: String): Boolean = text in PUNCTUATIONS
}
