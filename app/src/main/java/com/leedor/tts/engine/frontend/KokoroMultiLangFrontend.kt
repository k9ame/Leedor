package com.leedor.tts.engine.frontend

import android.util.Log

/**
 * Kokoro 多语言文本前端 —— 对齐 sherpa-onnx 的
 * `kokoro-multi-lang-lexicon.cc` 的 `ConvertTextToTokenIds`。
 *
 * 处理流程：
 * 1. 标点归一化（[TextUtils.PunctuationStyle.KOKORO]）
 * 2. 按 `[\u4e00-\u9fff]` 切分中英片段
 * 3. **中文** → [PhraseMatcher] 最长匹配查 [Lexicon]
 * 4. **非中文** → 标点直接成 token；否则走 [oovPhonemizer]（eSpeak）
 * 5. 按 sherpa 的规则合并短句、按 [maxTokenLen] 断句
 *
 * 输出的每个 `IntArray` 都是一句，首尾各有一个 pad token。
 */
class KokoroMultiLangFrontend(
    private val tokens: TokenTable,
    private val lexicon: Lexicon,
    /** 来自模型元数据（Kokoro 是 512）。 */
    private val maxTokenLen: Int,
    /**
     * 非中文（含 OOV 中文词）的音素化回调，返回**单个音素 token**列表。
     * 通常接 eSpeak：`text -> EspeakWrapper.textToPhonemesSafe(text, "en-us")` 再按码点拆开。
     * 为 null 时非中文片段会被丢弃。
     */
    private val oovPhonemizer: ((String) -> List<String>)? = null,
    private val debug: Boolean = false
) {
    companion object {
        private const val TAG = "KokoroFrontend"

        /** sherpa 的 `ids.size() > 10 + 2` 阈值。 */
        private const val LONG_SEGMENT_TOKENS = 12

        /** sherpa 的 `ans.back().tokens.size() + ids.size() < 50` 阈值。 */
        private const val MERGE_SENTENCE_LIMIT = 50

        /** sherpa 的 `ids.size() < 5` 阈值。 */
        private const val SHORT_SEGMENT_TOKENS = 5
    }

    /**
     * 文本 → 每句一个 token id 数组。
     * 返回空列表表示无法合成（例如文本为空或全部是未知字符）。
     */
    fun textToTokenIds(text: String): List<IntArray> {
        if (text.isBlank()) return emptyList()

        val normalized = TextUtils.normalizePunctuation(text, TextUtils.PunctuationStyle.KOKORO)
        if (normalized.isEmpty()) return emptyList()

        val ans = ArrayList<IntArray>()
        for ((isChinese, segment) in TextUtils.splitByChinese(normalized)) {
            val idsVec = if (isChinese) {
                chineseToTokenIds(segment)
            } else {
                nonChineseToTokenIds(segment)
            }
            mergeInto(ans, idsVec)
        }

        if (debug) {
            ans.forEachIndexed { i, ids -> Log.d(TAG, "句 $i: ${ids.toList()}") }
        }
        return ans
    }

    // ------------------------------------------------------------------
    // 中文
    // ------------------------------------------------------------------

    private fun chineseToTokenIds(text: String): List<IntArray> {
        val words = TextUtils.splitUtf8(text)
        if (words.isEmpty()) return emptyList()

        val ans = ArrayList<IntArray>()
        var sentence = ArrayList<Int>()
        sentence.add(tokens.padId)

        for (phrase in PhraseMatcher(lexicon.words, words)) {
            val ids = lexicon.idsOf(phrase)
            if (ids == null || ids.isEmpty()) {
                if (debug) Log.d(TAG, "忽略词典外的词: '$phrase'")
                continue
            }

            if (sentence.size + ids.size > maxTokenLen - 2) {
                sentence.add(tokens.padId)
                ans.add(sentence.toIntArray())
                sentence = ArrayList()
                sentence.add(tokens.padId)
            }
            ids.forEach { sentence.add(it) }
        }

        if (sentence.size > 1) {
            sentence.add(tokens.padId)
            ans.add(sentence.toIntArray())
        }
        return ans
    }

    // ------------------------------------------------------------------
    // 非中文
    // ------------------------------------------------------------------

    private fun nonChineseToTokenIds(text: String): List<IntArray> {
        // 单个标点 → [pad, punctId, pad]
        val trimmed = text.trim()
        if (TextUtils.isPunctuation(trimmed)) {
            val id = tokens.idOf(trimmed)
            return if (id == null) emptyList()
            else listOf(intArrayOf(tokens.padId, id, tokens.padId))
        }

        val ans = ArrayList<IntArray>()
        var sentence = ArrayList<Int>()
        sentence.add(tokens.padId)

        val spaceId = tokens.idOf(" ")

        for (word in TextUtils.splitWords(text)) {
            val w = word.lowercase()
            if (w.isEmpty()) continue

            if (TextUtils.isPunctuation(w)) {
                val id = tokens.idOf(w) ?: continue
                sentence.add(id)

                if (sentence.size > maxTokenLen - 2) {
                    sentence.add(tokens.padId)
                    ans.add(sentence.toIntArray())
                    sentence = ArrayList()
                    sentence.add(tokens.padId)
                    continue
                }
                // 句末标点：断句
                if (w == "." || w == "!" || w == "?" || w == ";") {
                    sentence.add(tokens.padId)
                    ans.add(sentence.toIntArray())
                    sentence = ArrayList()
                    sentence.add(tokens.padId)
                }
                continue
            }

            val ids = lexicon.idsOf(w)
            if (ids != null && ids.isNotEmpty()) {
                if (sentence.size + ids.size + 3 > maxTokenLen - 2) {
                    sentence.add(tokens.padId)
                    ans.add(sentence.toIntArray())
                    sentence = ArrayList()
                    sentence.add(tokens.padId)
                }
                ids.forEach { sentence.add(it) }
                if (spaceId != null) sentence.add(spaceId)
                continue
            }

            // 词典里没有 → 交给 eSpeak
            val phonemes = oovPhonemizer?.invoke(w)
            if (phonemes.isNullOrEmpty()) {
                if (debug) Log.d(TAG, "无法处理 OOV: '$w'")
                continue
            }
            val phonemeIds = tokens.idsOf(phonemes)
            if (phonemeIds.isEmpty()) continue

            if (sentence.size + phonemeIds.size + 3 > maxTokenLen - 2) {
                sentence.add(tokens.padId)
                ans.add(sentence.toIntArray())
                sentence = ArrayList()
                sentence.add(tokens.padId)
            }
            phonemeIds.forEach { sentence.add(it) }
        }

        if (sentence.size > 1) {
            sentence.add(tokens.padId)
            ans.add(sentence.toIntArray())
        }
        return ans
    }

    // ------------------------------------------------------------------
    // 合并（对齐 sherpa 的合并规则）
    // ------------------------------------------------------------------

    private fun mergeInto(ans: MutableList<IntArray>, idsVec: List<IntArray>) {
        for (ids in idsVec) {
            if (ids.size > LONG_SEGMENT_TOKENS) {
                // 长片段自成一句
                ans.add(ids)
                continue
            }
            if (ans.isEmpty()) {
                ans.add(ids)
                continue
            }

            val last = ans.last()
            val canMerge = (last.size + ids.size < MERGE_SENTENCE_LIMIT) ||
                (ids.size < SHORT_SEGMENT_TOKENS)

            if (canMerge && ids.size >= 2 && last.isNotEmpty()) {
                // 把上一句结尾的 pad 换成新片段的第一个真实 token，
                // 再追加其余部分（跳过新片段的起始 pad）
                val merged = IntArray(last.size + ids.size - 2)
                System.arraycopy(last, 0, merged, 0, last.size)
                merged[last.size - 1] = ids[1]
                System.arraycopy(ids, 2, merged, last.size, ids.size - 2)
                ans[ans.size - 1] = merged
            } else {
                ans.add(ids)
            }
        }
    }
}
