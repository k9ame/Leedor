package com.leedor.tts.engine.frontend

import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * 发音词典：`<词> <音素token> <音素token> ...` → token id 列表。
 *
 * 这是 sherpa-onnx 方案的核心 —— **中文 G2P 全部离线预编译进词典**，
 * 运行时只做查表，不需要 jieba / pypinyin。
 *
 * 实测文件格式（ZipVoice 的 `lexicon.txt`）：
 * ```
 * 一 y0 i1
 * 丁 d0 ing1
 * 不 b0 u4
 * ```
 * Kokoro v1.1-zh 的 `lexicon-zh.txt` 格式相同，只是音素换成注音符号：
 * ```
 * 中 ㄓ ㄨ ㄥ 1
 * ```
 *
 * 因此**同一个解析器两个模型都能用**。
 */
class Lexicon private constructor(
    private val word2ids: Map<String, IntArray>
) {
    companion object {
        private const val TAG = "Lexicon"

        fun fromFile(file: File, tokens: TokenTable): Lexicon =
            file.inputStream().use { fromStream(it, tokens) }

        fun fromStream(input: InputStream, tokens: TokenTable): Lexicon =
            fromLines(input.bufferedReader(Charsets.UTF_8).readLines().asSequence(), tokens)

        fun fromLines(lines: Sequence<String>, tokens: TokenTable): Lexicon {
            val map = HashMap<String, IntArray>(1 shl 16)
            var dup = 0
            var bad = 0

            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                // 词与音素之间可能是空格，也可能是 TAB（不同导出方的写法不一样），
                // 所以按「第一个空白字符」切分。
                val sp = line.indexOfFirst { it == ' ' || it == '\t' }
                if (sp <= 0) { bad++; continue }

                // 词统一小写，与 sherpa 的 ToLowerCase 一致
                val word = line.substring(0, sp).lowercase()
                // 音素之间同样按任意空白切分（不能只按空格切）
                val tokenList = line.substring(sp + 1).trim().split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                if (tokenList.isEmpty()) { bad++; continue }

                if (map.containsKey(word)) { dup++; continue }

                val ids = tokens.idsOf(tokenList)
                if (ids.isEmpty()) { bad++; continue }

                map[word] = ids
            }

            Log.i(TAG, "已加载 ${map.size} 个词条（重复 $dup，无效 $bad）")
            return Lexicon(map)
        }
    }

    /** 所有词，供 [PhraseMatcher] 做最长匹配。 */
    val words: Set<String> get() = word2ids.keys

    val size: Int get() = word2ids.size

    /** 查词的 token ids；不存在返回 null。 */
    fun idsOf(word: String): IntArray? = word2ids[word.lowercase()]

    fun contains(word: String): Boolean = word2ids.containsKey(word.lowercase())

    /**
     * 合并另一个词典。**已有的词不会被覆盖**（与 sherpa 的多词典加载行为一致：
     * 先加载的词典优先，后面的重复词会被忽略）。
     *
     * 用于 Kokoro v1.1-zh 把 `lexicon-zh.txt` 与 `lexicon-us-en.txt` 合成一份查表结构。
     */
    fun mergedWith(other: Lexicon): Lexicon {
        if (other.size == 0) return this
        if (this.size == 0) return other
        val merged = HashMap<String, IntArray>(this.size + other.size)
        merged.putAll(this.word2ids)
        for ((k, v) in other.word2ids) {
            if (!merged.containsKey(k)) merged[k] = v
        }
        Log.i(TAG, "合并词典: ${this.size} + ${other.size} -> ${merged.size}")
        return Lexicon(merged)
    }
}
