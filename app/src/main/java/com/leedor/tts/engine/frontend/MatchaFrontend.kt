package com.leedor.tts.engine.frontend

import android.util.Log

/**
 * ZipVoice 的文本前端 —— 对齐 sherpa-onnx 的 `MatchaTtsLexicon::ConvertTextToTokenIds`。
 *
 * 与 [KokoroMultiLangFrontend] 的区别：
 *  - **不做中英分段**，整段文本一起做 [PhraseMatcher] 最长匹配
 *  - 标点归一化表不同（`：` 直接映射到 `,`，见 [TextUtils.PunctuationStyle.MATCHA]）
 *  - 输出**不带** BOS/EOS 填充，而是遇到标点就断句；英文单词之间插一个 blank(空格) token
 *
 * 处理流程：
 * 1. 标点归一化
 * 2. 按词切分（中文单字 / 英文成词）
 * 3. 去掉标点后的空格、合并连续标点
 * 4. [PhraseMatcher] 最长匹配
 * 5. 每个词查词典；CJK 词逐字回退；其余走 eSpeak
 */
class MatchaFrontend(
    private val tokens: TokenTable,
    private val lexicon: Lexicon,
    /** 非中文且词典未命中时的音素化回调（eSpeak）。返回单个音素 token。 */
    private val oovPhonemizer: ((String) -> List<String>)? = null,
    private val debug: Boolean = false
) {
    companion object {
        private const val TAG = "MatchaFrontend"

        /** 一句话最多允许多少个 token，超过就强制断句，防止 ONNX 输入过长。 */
        private const val MAX_TOKENS_PER_SENTENCE = 1000
    }

    /** 文本 → 每句一个 token id 数组（**不含** BOS/EOS 填充）。 */
    fun textToTokenIds(text: String): List<IntArray> {
        if (text.isBlank()) return emptyList()

        val normalized = TextUtils.normalizePunctuation(text, TextUtils.PunctuationStyle.MATCHA)
        if (normalized.isEmpty()) return emptyList()

        // 1. 切词
        val rawWords = TextUtils.splitWords(normalized)

        // 2. 去掉标点后的空格、合并连续标点
        val words = ArrayList<String>(rawWords.size)
        for ((i, w) in rawWords.withIndex()) {
            if (i == 0) {
                words.add(w)
                continue
            }
            val prev = words.last()
            if (w == " " || isPunct(w)) {
                // 前一个已经是空格或标点 → 丢弃当前这个
                if (prev == " " || isPunct(prev)) continue
            }
            words.add(w)
        }
        if (words.isEmpty()) return emptyList()

        // 3. 最长匹配 + 逐词转换
        val blankId = tokens.idOf(" ")
        val ans = ArrayList<IntArray>()
        var sentence = ArrayList<Int>()

        var lastWord = ""
        for (w in PhraseMatcher(lexicon.words, words)) {
            val ids = wordToIds(w)
            if (ids.isEmpty()) {
                if (debug) Log.d(TAG, "忽略词典外的词: '$w'")
                lastWord = w
                continue
            }

            // 上一个词以**ASCII 字母**开头 → 补一个空格，避免英文单词粘连。
            //
            // ⚠️ 这里必须限定 ASCII：sherpa 用的是 C 的 `isalpha(last_word[0])`，
            // 作用在 UTF-8 的**首字节**上，汉字首字节 >= 0x80 会返回 0。
            // 如果直接用 Kotlin 的 `Char.isLetter()`，汉字也会返回 true，
            // 结果是在每个中文词之间都插入一个空格，严重劣化合成质量。
            if (lastWord.isNotEmpty() && isAsciiAlpha(lastWord[0]) && blankId != null) {
                sentence.add(blankId)
            }

            ids.forEach { sentence.add(it) }

            // 标点 → 断句
            if (isPunct(w) && sentence.isNotEmpty()) {
                ans.add(sentence.toIntArray())
                sentence = ArrayList()
            } else if (sentence.size >= MAX_TOKENS_PER_SENTENCE) {
                ans.add(sentence.toIntArray())
                sentence = ArrayList()
            }

            lastWord = w
        }

        if (sentence.isNotEmpty()) ans.add(sentence.toIntArray())

        if (debug) {
            ans.forEachIndexed { i, ids -> Log.d(TAG, "句 $i: ${ids.toList()}") }
        }
        return ans
    }

    /**
     * 单个词 → token ids。
     *
     * 查找顺序（与 sherpa 一致）：
     * 1. 词典
     * 2. token 表本身（标点、空格这类单 token）
     * 3. 含 CJK → 拆成单字递归查词典
     * 4. 其余 → eSpeak
     */
    private fun wordToIds(w: String): IntArray {
        lexicon.idsOf(w)?.let { return it }
        tokens.idOf(w)?.let { return intArrayOf(it) }

        if (containsCjk(w)) {
            val out = ArrayList<Int>()
            for (ch in TextUtils.splitUtf8(w)) {
                lexicon.idsOf(ch)?.let { ids -> ids.forEach { out.add(it) } }
            }
            return out.toIntArray()
        }

        val phonemes = oovPhonemizer?.invoke(w) ?: return IntArray(0)
        return tokens.idsOf(phonemes)
    }

    private fun containsCjk(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            // CJK 统一表意文字 + 扩展 A
            if (cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun isPunct(s: String): Boolean = TextUtils.isPunctuation(s)

    /** 对齐 C 的 `isalpha`：只认 ASCII 字母。 */
    private fun isAsciiAlpha(c: Char): Boolean = c.code < 128 && c.isLetter()
}
