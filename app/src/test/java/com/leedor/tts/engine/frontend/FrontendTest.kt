package com.leedor.tts.engine.frontend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享文本前端的单元测试。
 *
 * 这些测试用**合成数据**（格式与真实文件一致），因此不需要下载
 * 140MB 的模型包就能验证移植正确性。
 *
 * 跑：`./gradlew testDebugUnitTest`
 */
class FrontendTest {

    // ------------------------------------------------------------------
    // TokenTable
    // ------------------------------------------------------------------

    @Test
    fun `TokenTable 解析基本行与多字符 token`() {
        val t = TokenTable.fromLines(
            sequenceOf(
                "\$ 0",
                "; 1",
                "ㄅ 23",
                "tʃ 45",          // 多字符 token
                "dʒ 46",
                "",               // 空行
                "# 注释行",
            )
        )
        assertEquals(0, t.idOf("\$"))
        assertEquals(1, t.idOf(";"))
        assertEquals(23, t.idOf("ㄅ"))
        assertEquals(45, t.idOf("tʃ"))     // 必须能正确处理多字符 token
        assertEquals(46, t.idOf("dʒ"))
        assertEquals(5, t.size)
        assertNull(t.idOf("不存在"))
    }

    @Test
    fun `TokenTable 处理 Windows 换行与 CR`() {
        val t = TokenTable.fromLines(sequenceOf("\$ 0\r", "a 7\r"))
        assertEquals(0, t.idOf("\$"))
        assertEquals(7, t.idOf("a"))
    }

    @Test
    fun `TokenTable padId 取自 $ 符号`() {
        val t = TokenTable.fromLines(sequenceOf("\$ 3", "a 1"))
        assertEquals(3, t.padId)
    }

    @Test
    fun `TokenTable 必须保留空格 token`() {
        // 真实 tokens.txt 第 15 行长这样：`"  16"`（空格 + 空格 + id）
        // 这是回归测试：早期实现用 trim() 会把前导空格吃掉，导致空格 token 丢失，
        // 英文单词之间就没有间隔了。
        val t = TokenTable.fromLines(sequenceOf("; 1", "  16", "a 43"))
        assertEquals(1, t.idOf(";"))
        assertEquals("空格 token 不能被丢弃", 16, t.idOf(" "))
        assertEquals(43, t.idOf("a"))
    }

    @Test
    fun `TokenTable 能从 tokenizer_json 解析词表`() {
        // onnx-community / transformers.js 风格。ModelScope 上只有这个格式，
        // 而它和 sherpa 的 tokens.txt 内容等价（实测 id 逐条相同），
        // 所以索引里两者可以互为镜像，由 TokenTable 自动识别。
        val json = """
            {"model":{"vocab":{"$":0,";":1,"\u3105":23,"t\u0283":45,"a":43}}}
        """.trimIndent()
        val t = TokenTable.openInputStream(json.byteInputStream())
        assertEquals(5, t.size)
        assertEquals(0, t.padId)
        assertEquals(0, t.idOf("$"))
        assertEquals(1, t.idOf(";"))
        assertEquals(23, t.idOf("\u3105"))     // ㄅ
        assertEquals(45, t.idOf("t\u0283"))    // tʃ 多字符 token
        assertEquals(43, t.idOf("a"))
    }

    @Test
    fun `TokenTable 自动区分 txt 与 json 两种输入`() {
        val txt = TokenTable.openInputStream("a 1\nb 2".byteInputStream())
        assertEquals(2, txt.size)
        assertEquals(1, txt.idOf("a"))

        val js = TokenTable.openInputStream("""{"model":{"vocab":{"a":1,"b":2}}}""".byteInputStream())
        assertEquals(2, js.size)
        assertEquals(1, js.idOf("a"))
    }

    @Test
    fun `TokenTable 对损坏的 tokenizer_json 不崩溃`() {
        val t = TokenTable.openInputStream("{ this is not valid json".byteInputStream())
        assertEquals(0, t.size)
        assertEquals(0, t.padId)
    }

    // ------------------------------------------------------------------
    // Lexicon
    // ------------------------------------------------------------------

    @Test
    fun `Lexicon 解析汉字与音素`() {
        val tokens = TokenTable.fromLines(sequenceOf("\$ 0", "y0 10", "i1 11", "b0 12", "u4 13"))
        val lex = Lexicon.fromLines(
            sequenceOf(
                "一 y0 i1",
                "不 b0 u4",
                "坏行 不存在的token",   // 全部未知 → 应被跳过
            ),
            tokens
        )
        assertEquals(2, lex.size)
        assertArrayEquals(intArrayOf(10, 11), lex.idsOf("一"))
        assertArrayEquals(intArrayOf(12, 13), lex.idsOf("不"))
        assertNull(lex.idsOf("坏行"))
    }

    @Test
    fun `Lexicon 词条统一小写`() {
        val tokens = TokenTable.fromLines(sequenceOf("\$ 0", "h 1", "i 2"))
        val lex = Lexicon.fromLines(sequenceOf("Hello h i"), tokens)
        assertArrayEquals(intArrayOf(1, 2), lex.idsOf("hello"))
        assertArrayEquals(intArrayOf(1, 2), lex.idsOf("HELLO"))
    }

    // ------------------------------------------------------------------
    // PhraseMatcher —— 前向最长匹配
    // ------------------------------------------------------------------

    @Test
    fun `PhraseMatcher 优先匹配最长词组`() {
        val lex = setOf("中国", "中", "国", "人")
        val words = listOf("中", "国", "人")
        assertEquals(listOf("中国", "人"), PhraseMatcher(lex, words).toList())
    }

    @Test
    fun `PhraseMatcher 无匹配时退化为单字`() {
        val lex = setOf("中国")
        val words = listOf("你", "好", "中", "国")
        assertEquals(listOf("你", "好", "中国"), PhraseMatcher(lex, words).toList())
    }

    @Test
    fun `PhraseMatcher 不跨过英文边界`() {
        // "中国" 在词典里，但 words 是 中/国/hello 时仍应正常匹配
        val lex = setOf("中国")
        val words = listOf("中", "国", "hello")
        assertEquals(listOf("中国", "hello"), PhraseMatcher(lex, words).toList())
    }

    @Test
    fun `PhraseMatcher 尊重 maxSearchLen`() {
        // 用中文测：sherpa 只在「非 ASCII 字母/标点开头」的位置做词组匹配，
        // 所以这里不能用英文单词做用例（见下一个测试）。
        val lex = setOf("中国你好")
        val words = listOf("中", "国", "你", "好")
        // maxSearchLen=3 时最长只能看 3 个字，匹配不到 4 字的 "中国你好"
        assertEquals(
            listOf("中", "国", "你", "好"),
            PhraseMatcher(lex, words, maxSearchLen = 3).toList()
        )
        // 默认 10 时可以匹配到
        assertEquals(listOf("中国你好"), PhraseMatcher(lex, words).toList())
    }

    @Test
    fun `PhraseMatcher 不对 ASCII 字母开头的位置做词组匹配`() {
        // 这是 sherpa 的刻意设计：英文单词由词典/eSpeak 分支处理，
        // 不走 PhraseMatcher，否则每个字母都会去查词组词典。
        val lex = setOf("abcd")
        val words = listOf("a", "b", "c", "d")
        assertEquals(
            listOf("a", "b", "c", "d"),
            PhraseMatcher(lex, words).toList()
        )
    }

    // ------------------------------------------------------------------
    // TextUtils
    // ------------------------------------------------------------------

    @Test
    fun `标点归一化 Kokoro 风格`() {
        val out = TextUtils.normalizePunctuation(
            "你好，世界。你好！", TextUtils.PunctuationStyle.KOKORO
        )
        assertEquals("你好,世界.你好!", out)
    }

    @Test
    fun `标点归一化 ZipVoice 风格把全角冒号变成逗号`() {
        val out = TextUtils.normalizePunctuation(
            "注意：这是重点", TextUtils.PunctuationStyle.MATCHA
        )
        assertEquals("注意,这是重点", out)
    }

    @Test
    fun `splitWords 把英文合并成词而中文保持单字`() {
        assertEquals(listOf("hello"), TextUtils.splitWords("hello"))
        assertEquals(listOf("你", "好"), TextUtils.splitWords("你好"))
        assertEquals(listOf("你", "好", "world"), TextUtils.splitWords("你好world"))
        assertEquals(listOf("你", "好", "world"), TextUtils.splitWords("你好 world"))
    }

    @Test
    fun `splitWords 保留 ASCII 标点且丢弃空格`() {
        assertEquals(listOf("hi", ",", "there"), TextUtils.splitWords("hi, there"))
    }

    @Test
    fun `splitByChinese 正确切分中英片段`() {
        val parts = TextUtils.splitByChinese("你好world再见")
        assertEquals(3, parts.size)
        assertEquals(true to "你好", parts[0])
        assertEquals(false to "world", parts[1])
        assertEquals(true to "再见", parts[2])
    }

    // ------------------------------------------------------------------
    // KokoroMultiLangFrontend —— 端到端
    // ------------------------------------------------------------------

    private fun buildFrontend(): KokoroMultiLangFrontend {
        // 合成词表：pad=0，中文音素 10..13，英文用 a/b，标点 , 和 .
        val tokens = TokenTable.fromLines(
            sequenceOf(
                "\$ 0",
                ", 1",
                ". 2",
                "  3",
                "zh0 10",
                "ong1 11",
                "g0 12",
                "uo2 13",
                "a 20",
                "b 21",
            )
        )
        val lex = Lexicon.fromLines(
            sequenceOf(
                "中 zh0 ong1",
                "国 g0 uo2",
            ),
            tokens
        )
        return KokoroMultiLangFrontend(tokens, lex, maxTokenLen = 512)
    }

    @Test
    fun `前端 中文句子产出正确的 token 序列`() {
        val fe = buildFrontend()
        val out = fe.textToTokenIds("中国")
        assertEquals(1, out.size)
        // 期望：[pad] + 中(10,11) + 国(12,13) + [pad]
        assertArrayEquals(intArrayOf(0, 10, 11, 12, 13, 0), out[0])
    }

    @Test
    fun `前端 中英混排按片段分别处理`() {
        val fe = buildFrontend()
        val out = fe.textToTokenIds("中国ab")
        assertTrue("应至少产出一句", out.isNotEmpty())
        // 中文部分必须出现
        val flat = out.flatMap { it.toList() }
        assertTrue("应包含中文音素", flat.containsAll(listOf(10, 11, 12, 13)))
    }

    @Test
    fun `前端 空文本返回空列表`() {
        val fe = buildFrontend()
        assertTrue(fe.textToTokenIds("").isEmpty())
        assertTrue(fe.textToTokenIds("   ").isEmpty())
    }

    @Test
    fun `前端 词典外的中文被忽略而不是崩溃`() {
        val fe = buildFrontend()
        // "龙" 不在词典里
        val out = fe.textToTokenIds("龙")
        assertTrue("全部 OOV 时不应产出句子", out.isEmpty())
    }

    @Test
    fun `前端 每句首尾都是 pad`() {
        val fe = buildFrontend()
        val out = fe.textToTokenIds("中国中国中国")
        for (ids in out) {
            assertEquals("句首应为 pad", 0, ids.first())
            assertEquals("句尾应为 pad", 0, ids.last())
        }
    }
}
