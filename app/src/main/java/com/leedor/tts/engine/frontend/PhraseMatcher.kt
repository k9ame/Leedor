package com.leedor.tts.engine.frontend

/**
 * 前向最长匹配分词器 —— 与 sherpa-onnx 的 `phrase-matcher.cc` 同构。
 *
 * 算法（逐字推进）：
 * 1. 从当前位置 `i` 开始，取最长候选（最多 [maxSearchLen] 个字符）
 * 2. 若候选末尾是字母/标点则缩短（避免跨中英边界）
 * 3. 命中词典就采纳，`i` 跳到匹配末尾之后
 * 4. 都不命中则退化为**单字**，`i` 前进 1
 *
 * 注意 `while (end > start)` 用的是 `>` 而不是 `>=`：
 * 单字匹配不在这里处理，而是走第 4 步的兜底分支 —— 这样能保证
 * 单字不会重复查表，行为与 sherpa 完全一致。
 *
 * 之所以自己实现而不复用 [com.leedor.tts.engine.piper.PiperEngine] 里的贪心匹配：
 * 那个是「音素字符串 → token」的匹配，这里是「汉字序列 → 词组」的匹配，
 * 匹配对象和词典都不同，只是算法形状相似。
 */
class PhraseMatcher(
    lexicon: Set<String>,
    private val words: List<String>,
    private val maxSearchLen: Int = DEFAULT_MAX_SEARCH_LEN
) : Iterable<String> {

    companion object {
        /** sherpa-onnx 的默认值。 */
        const val DEFAULT_MAX_SEARCH_LEN = 10
    }

    private val phrases: MutableList<String> = ArrayList(words.size)

    init {
        build(lexicon)
    }

    override fun iterator(): Iterator<String> = phrases.iterator()

    val size: Int get() = phrases.size

    private fun build(lexicon: Set<String>) {
        val n = words.size
        var i = 0
        while (i < n) {
            val start = i
            var matched: String? = null

            val first = words[i]
            if (first.isNotEmpty() && !isAlphaOrPunct(first[0])) {
                var end = minOf(i + maxSearchLen - 1, n - 1)
                while (end > start) {
                    val candidate = join(start, end)
                    if (candidate.isNotEmpty() && isAlphaOrPunct(candidate[candidate.length - 1])) {
                        end--
                        continue
                    }
                    if (lexicon.contains(candidate)) {
                        i = end + 1
                        matched = candidate
                        break
                    }
                    end--
                }
            }

            if (matched == null) {
                // 兜底：单字
                matched = words[i]
                i++
            }
            phrases.add(matched)
        }
    }

    private fun join(start: Int, end: Int): String {
        if (start < 0 || end >= words.size || start > end) return ""
        val sb = StringBuilder()
        for (k in start..end) sb.append(words[k])
        return sb.toString()
    }

    /**
     * 与 C 的 `std::isalpha(ch) || std::ispunct(ch)` 对齐。
     *
     * - 非 ASCII（汉字、注音等）→ false
     * - ASCII 字母 → true
     * - ASCII 数字 → false（C 里 ispunct 不含数字）
     * - ASCII 空白 → false
     * - 其余 ASCII 可打印字符（标点）→ true
     */
    private fun isAlphaOrPunct(ch: Char): Boolean {
        if (ch.code >= 128) return false
        if (ch.isLetterOrDigit()) return ch.isLetter()
        if (ch.isWhitespace()) return false
        return true
    }
}
