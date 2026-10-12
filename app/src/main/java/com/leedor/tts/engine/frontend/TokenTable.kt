package com.leedor.tts.engine.frontend

import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * `tokens.txt` —— 音素 token → id 的映射表。
 *
 * 文件格式（每行一条）：
 * ```
 * $ 0
 * ; 1
 * ㄅ 23
 * tʃ 45
 * ```
 *
 * 注：
 *  - token 可能是**多字符**（例如 `tʃ`、`dʒ`），所以必须按「第一个空格」切分，
 *    不能用 `split(" ")` 后取最后一段。
 *  - 行尾可能有 `\r`（Windows 换行），必须去掉。
 *
 * 背景：应用原来的 [com.leedor.tts.engine.Phonemizer] 把 178 个 token 硬编码在代码里，
 * 那是 Kokoro v1.0 的词表。Kokoro v1.1-zh 用 172 个 token（含 38 个注音符号），
 * ZipVoice 又是另一套，所以必须改成从文件读。
 */
class TokenTable private constructor(
    private val token2id: Map<String, Int>,
    val padId: Int
) {
    companion object {
        private const val TAG = "TokenTable"

        /** Kokoro / ZipVoice 都用 id 0 作为句首句尾的填充符。 */
        private const val DEFAULT_PAD_ID = 0

        fun fromFile(file: File): TokenTable = file.inputStream().use { openInputStream(it) }

        /**
         * 自动识别两种词表格式：
         *  - **纯文本** `tokens.txt`（sherpa 风格）：每行 `<token> <id>`
         *  - **JSON** `tokenizer.json`（onnx-community / transformers.js 风格）：
         *    `{"model": {"vocab": {"<token>": id, ...}}}`
         *
         * 两个来源的 token→id 映射实测**完全一致**（171 个共有 token 的 id 全部相同，
         * JSON 版多一个 `$` 填充符），所以可以互换。
         * 这样索引里就能同时挂 ModelScope 的 tokenizer.json 和 HF 的 tokens.txt 做多源回退。
         */
        fun openInputStream(input: InputStream): TokenTable {
            val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val trimmed = text.trimStart()
            return if (trimmed.startsWith("{")) fromTokenizerJson(trimmed)
            else fromLines(text.lineSequence())
        }

        /** 解析 transformers.js 风格的 `tokenizer.json`。 */
        fun fromTokenizerJson(json: String): TokenTable {
            val map = HashMap<String, Int>(512)
            try {
                val root = com.google.gson.Gson().fromJson(json, Map::class.java)
                @Suppress("UNCHECKED_CAST")
                val model = root?.get("model") as? Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val vocab = model?.get("vocab") as? Map<String, Any?>
                vocab?.forEach { (token, idAny) ->
                    val id = (idAny as? Number)?.toInt() ?: return@forEach
                    map[token] = id
                }
            } catch (t: Throwable) {
                Log.e(TAG, "解析 tokenizer.json 失败", t)
            }
            val pad = map["$"] ?: DEFAULT_PAD_ID
            Log.i(TAG, "已从 tokenizer.json 加载 ${map.size} 个 token，padId=$pad")
            return TokenTable(map, pad)
        }

        fun fromLines(lines: Sequence<String>): TokenTable {
            val map = HashMap<String, Int>(512)
            for (raw in lines) {
                // ⚠️ 只能用 trimEnd()，不能 trim()！
                // tokens.txt 里「空格」这个 token 本身就是一个空格字符，
                // 它的行长这样：`"  16"`（空格 + 空格 + id）。
                // 用 trim() 会把前导空格吃掉，导致空格 token 丢失，
                // 英文单词之间就不会有间隔了。
                val line = raw.trimEnd()
                if (line.isEmpty() || line.startsWith("#")) continue

                // 取「最后一个空白字符」当分隔符。
                //
                // token 本身可能含空白以外的任意字符（`tʃ`、`n0`、`ang4`），
                // 但绝不会含空白；id 一定在最后。所以按最后一个空白切分。
                //
                // 为什么不能只找空格：不同模型的 tokens.txt 分隔符不一样 ——
                //   * Kokoro（sherpa 版）：`token id`，空格分隔
                //   * **ZipVoice：`token\tid`，TAB 分隔**（实测）
                // 只找空格的话 ZipVoice 整张表都会解析失败（每一行都"格式异常"），
                // 结果是词典查不出音素、前端一个 token 都产不出来、完全没声音。
                val sp = line.indexOfLast { it == ' ' || it == '\t' }
                if (sp < 0) {
                    Log.w(TAG, "跳过格式异常的行: '$line'")
                    continue
                }
                val token = line.substring(0, sp)
                val id = line.substring(sp + 1).trim().toIntOrNull()
                if (id == null) {
                    Log.w(TAG, "跳过 id 无法解析的行: '$line'")
                    continue
                }
                map[token] = id
            }

            val pad = map["$"] ?: DEFAULT_PAD_ID
            Log.i(TAG, "已加载 ${map.size} 个 token，padId=$pad")
            return TokenTable(map, pad)
        }
    }

    val size: Int get() = token2id.size

    /** 查 token 的 id；不存在返回 null。 */
    fun idOf(token: String): Int? = token2id[token]

    fun contains(token: String): Boolean = token2id.containsKey(token)

    /** 批量转换，跳过未知 token（与 sherpa 的行为一致）。 */
    fun idsOf(tokens: List<String>): IntArray {
        val out = IntArray(tokens.size)
        var n = 0
        for (t in tokens) {
            token2id[t]?.let { out[n++] = it }
        }
        return if (n == out.size) out else out.copyOf(n)
    }
}
