package com.leedor.tts.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「点语音之后当前模型该是什么」的回归测试。
 *
 * 真实 bug：Kokoro v1.1-zh 的音色表里前三个是英文音色（af_maple / af_sol / bf_vale），
 * 而进语音库时默认语音取列表第一个；旧的 selectVoice 又按语音名前缀把 `af_`/`bf_`
 * 判成 `kokoro_v1.0`。于是「在设置里选了 Kokoro 1.1，一进语音库就被改回 1.0」。
 *
 * 规则：语音表永远属于当前模型，点语音基本不换模型 —— 只有 Piper 例外，
 * 它的模型 id 里带着语音名。
 */
class VoiceModelSelectionTest {

    @Test
    fun `Kokoro v1_1-zh 的英文音色不会把模型带回 v1_0`() {
        // 这三个是 v1.1-zh 自带的英文音色，名字和 v1.0 的 af_/bf_ 撞前缀
        for (voice in listOf("af_maple", "af_sol", "bf_vale")) {
            assertEquals(
                "点 $voice 不应改变模型",
                "kokoro_v1.1-zh",
                modelForSelectedVoice("kokoro_v1.1-zh", "kokoro_multilang", voice)
            )
        }
    }

    @Test
    fun `Kokoro v1_1-zh 的中文音色同样不改变模型`() {
        for (voice in listOf("zf_001", "zm_014")) {
            assertEquals(
                "kokoro_v1.1-zh",
                modelForSelectedVoice("kokoro_v1.1-zh", "kokoro_multilang", voice)
            )
        }
    }

    @Test
    fun `Piper 的模型 id 就是语音名，换语音必须跟着换`() {
        assertEquals(
            "piper_zh_CN-huayan-x_low",
            modelForSelectedVoice("piper_en_US-amy-low", "piper", "zh_CN-huayan-x_low")
        )
    }

    @Test
    fun `Piper 语音名已带前缀时不会叠加前缀`() {
        assertEquals(
            "piper_en_US-amy-low",
            modelForSelectedVoice("piper_en_US-amy-low", "piper", "piper_en_US-amy-low")
        )
    }

    @Test
    fun `Pocket 与 Kitten 的语音不会把模型带到别的模型上`() {
        assertEquals(
            "pocket_v1",
            modelForSelectedVoice("pocket_v1", "pocket", "alba")
        )
        assertEquals(
            "kitten_nano",
            modelForSelectedVoice("kitten_nano", "kitten", "expr-voice-2-f")
        )
    }

    @Test
    fun `ZipVoice 的克隆音色不改变模型`() {
        assertEquals(
            "zipvoice_zh_en",
            modelForSelectedVoice("zipvoice_zh_en", "zipvoice", "我的声音")
        )
    }

    @Test
    fun `ZipVoice 自带的每个音色都有本地化显示名`() {
        val json = java.io.File("src/main/assets/models.json").readText()
        val zip = com.leedor.tts.data.ModelManifest.fromJson(json)
            .models.find { it.id == "zipvoice_zh_en" }!!
        for (v in zip.voices) {
            assertTrue(
                "自带音色 $v 没有显示名文案（界面会直接显示文件名）",
                zipVoicePresetNameRes(v) != 0
            )
        }
    }

    @Test
    fun `认不出的引擎保持当前模型不变`() {
        assertEquals(
            "some_future_model",
            modelForSelectedVoice("some_future_model", "unknown_engine", "whatever")
        )
    }
}
