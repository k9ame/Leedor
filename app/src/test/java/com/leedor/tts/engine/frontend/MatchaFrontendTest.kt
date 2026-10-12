package com.leedor.tts.engine.frontend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZipVoice 文本前端（[MatchaFrontend]）的单元测试。
 *
 * 与 Kokoro 前端的关键差异都体现在这里：
 *  - 不做中英分段
 *  - 输出**不带** BOS/EOS 填充
 *  - 遇到标点就断句
 *  - 英文单词之间插 blank(空格) token
 */
class MatchaFrontendTest {

    /** 合成词表：pad 不存在（ZipVoice 无 pad），空格=1，标点=2/3，中文音素 10.. */
    private fun tokens() = TokenTable.fromLines(
        sequenceOf(
            "  1",
            ", 2",
            ". 3",
            "n 10", "i 11", "h 12", "ao 13", "zh 14", "ong 15", "g 16", "uo 17",
            "a 20", "b 21",
        )
    )

    private fun frontend(): MatchaFrontend {
        val t = tokens()
        val lex = Lexicon.fromLines(
            sequenceOf(
                "你 n i",
                "好 h ao",
                "中 zh ong",
                "国 g uo",
            ),
            t
        )
        return MatchaFrontend(t, lex)
    }

    // ------------------------------------------------------------------
    // 真机文件格式回归（ZipVoice 的 tokens.txt / lexicon.txt）
    // ------------------------------------------------------------------

    /**
     * ZipVoice 的 `tokens.txt` 是 **TAB 分隔** 的，而且音素 token 是多字符（`n0`/`sh0`/`ang4`）。
     *
     * 之前的解析只认空格分隔，遇到 TAB 行直接判定"格式异常"跳过 →
     * 整张表解析成空 → 词典全部无效 → 前端一个 token 都产不出来 →
     * 合成时只留下一句 `前端没有产出 token`，**完全没有声音**。
     *
     * 这里用从真实文件抄下来的前几行做回归（含 TAB 前那个空格 token）。
     */
    private fun zipVoiceStyleTokens() = TokenTable.fromLines(
        sequenceOf(
            "_\t0", "^\t1", "$\t2", " \t3", "!\t4", ",\t8", ".\t10",
            "n\t56", "i\t51", "h\t50", "ao\t108", "sh\t130", "d\t46", "u\t63",
            "l\t54", "ang\t122", "w\t65", "o\t57",
            // 多字符音素 token
            "n0\t200", "i2\t201", "h0\t202", "ao3\t203",
            "sh0\t204", "i4\t205", "d0\t206", "u2\t207", "l0\t208", "ang2\t209",
            "w0\t210", "o3\t211",
        )
    )

    @Test
    fun `TAB 分隔的 tokens 文本能正确解析`() {
        val t = zipVoiceStyleTokens()
        assertEquals("整张表都必须解析成功", 30, t.size)
        assertEquals("空格 token", 3, t.idOf(" "))
        assertEquals("下划线 token", 0, t.idOf("_"))
        assertEquals("多字符音素 token", 200, t.idOf("n0"))
        assertEquals("多字符音素 token", 209, t.idOf("ang2"))
    }

    @Test
    fun `空格分隔的 tokens 文本仍然能正确解析`() {
        // Kokoro（sherpa 版）是空格分隔，且空格 token 那行长这样：`"  16"`
        val t = TokenTable.fromLines(sequenceOf("$ 0", "  16", "a 14", "n0 200"))
        assertEquals(0, t.idOf("$"))
        assertEquals("空格 token 不能被吃掉", 16, t.idOf(" "))
        assertEquals(200, t.idOf("n0"))
    }

    @Test
    fun `ZipVoice 风格的中文文本能产出 token（不再空手而归）`() {
        // 回归：真实链路里 `你好，我是侍读郎。` 曾经产不出任何 token。
        val t = zipVoiceStyleTokens()
        val lex = Lexicon.fromLines(
            sequenceOf(
                "你好 n0 i2 h0 ao3",
                "我 w0 o3",
                "是 sh0 i4",
                "侍 sh0 i4",
                "读 d0 u2",
                "郎 l0 ang2",
            ),
            t
        )
        assertEquals("词典应加载成功", 6, lex.size)

        val out = MatchaFrontend(t, lex).textToTokenIds("你好，我是侍读郎。")
        assertTrue("必须产出句子", out.isNotEmpty())
        val flat = out.flatMap { it.toList() }
        assertTrue("必须产出 token", flat.isNotEmpty())
        // 第一句：你好 + 逗号
        assertArrayEquals(
            intArrayOf(200, 201, 202, 203, 8),
            out[0]
        )
    }

    @Test
    fun `中文词按词典展开为音素`() {
        val out = frontend().textToTokenIds("你好")
        assertEquals(1, out.size)
        assertArrayEquals(intArrayOf(10, 11, 12, 13), out[0])
    }

    @Test
    fun `输出不带 BOS EOS 填充`() {
        // 这是与 Kokoro 前端最大的区别：Kokoro 首尾各有一个 0，
        // ZipVoice 没有。
        val out = frontend().textToTokenIds("你好")
        val ids = out[0]
        assertFalse("首部不应有 pad", ids.first() == 0)
        assertFalse("尾部不应有 pad", ids.last() == 0)
    }

    @Test
    fun `标点会断句且标点本身保留在句尾`() {
        val out = frontend().textToTokenIds("你好，你好。")
        assertEquals("应按标点切成 2 句", 2, out.size)
        // 第一句 = 你好 + 逗号
        assertArrayEquals(intArrayOf(10, 11, 12, 13, 2), out[0])
        // 第二句 = 你好 + 句号
        assertArrayEquals(intArrayOf(10, 11, 12, 13, 3), out[1])
    }

    @Test
    fun `英文单词之间插入 blank token`() {
        // 规则：**前一个词**以 ASCII 字母开头时，才在它和下一个词之间插空格。
        // "a b" → 词序列 [a, b]，处理 b 时前一个词是 a（ASCII 字母）→ 插 blank
        val fe = frontend()
        val out = fe.textToTokenIds("a b")
        assertEquals(1, out.size)
        assertArrayEquals(intArrayOf(20, 1, 21), out[0])
    }

    @Test
    fun `中文词之间不插 blank token`() {
        // ⚠️ 回归测试：Kotlin 的 Char.isLetter() 对汉字返回 true，
        // 但 sherpa 用的 C isalpha 作用在 UTF-8 首字节上、汉字返回 0。
        // 如果误用 isLetter()，每个中文词之间都会被塞一个空格，严重劣化合成。
        val fe = frontend()
        val out = fe.textToTokenIds("你好")
        assertFalse("中文词之间不应有 blank", out[0].contains(1))
    }

    @Test
    fun `连续标点与标点后的空格会被合并`() {
        // "你好，，你好" → 归一化后是 "你好,,你好"
        // 连续的标点只保留一个
        val out = frontend().textToTokenIds("你好，，你好")
        for (ids in out) {
            val commas = ids.count { it == 2 }
            assertTrue("同一句里不应出现连续重复标点", commas <= 1)
        }
    }

    @Test
    fun `全角标点被归一化`() {
        val out = frontend().textToTokenIds("你好。")
        assertEquals(1, out.size)
        // 。→ . (id 3)
        assertEquals("句尾应是句号", 3, out[0].last())
    }

    @Test
    fun `词典外的中文被忽略而不是崩溃`() {
        val out = frontend().textToTokenIds("龙")
        assertTrue("全部 OOV 时不应产出句子", out.isEmpty())
    }

    @Test
    fun `空文本返回空列表`() {
        val fe = frontend()
        assertTrue(fe.textToTokenIds("").isEmpty())
        assertTrue(fe.textToTokenIds("   ").isEmpty())
    }

    @Test
    fun `CJK 词未命中词典时逐字回退`() {
        // 词典里有 你、好，但没有 "你好吗"
        // PhraseMatcher 会切出 你/好/吗，其中 吗 不在词典 → 忽略
        val out = frontend().textToTokenIds("你好吗")
        assertEquals(1, out.size)
        assertArrayEquals(intArrayOf(10, 11, 12, 13), out[0])
    }

    @Test
    fun `produces only finite token ids`() {
        val out = frontend().textToTokenIds("你好，中国。")
        for (ids in out) {
            assertTrue("token id 不应为负", ids.all { it >= 0 })
        }
    }
}
