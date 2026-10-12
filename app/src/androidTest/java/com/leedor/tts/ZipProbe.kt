package com.leedor.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leedor.tts.data.ModelRepository
import com.leedor.tts.data.PrefsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 临时真机探针：下载 ZipVoice → 走系统 TTS 服务合成 → 落成 wav 供本地分析。用完即删。 */
@RunWith(AndroidJUnit4::class)
class ZipProbe {

    @Test
    fun downloadThenSynthesize() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. 模型（含 3 个自带参考音色）没下过就先下
        assertTrue("模型下载失败", ensureModel(ctx))
        val prompts = File(ctx.filesDir, "zipvoice/cloned")
        println("PROBE cloned=" + prompts.list()?.joinToString())

        // 2. 选好模型与音色
        val prefs = PrefsManager(ctx)
        prefs.currentModel = "zipvoice_zh_en"
        prefs.currentVoice = "leijun-1"

        // 3. 走系统 TTS 服务合成
        val latch = CountDownLatch(1)
        val tts = android.speech.tts.TextToSpeech(
            ctx,
            { if (it == android.speech.tts.TextToSpeech.SUCCESS) latch.countDown() },
            "com.leedor.tts"
        )
        assertTrue("绑定超时", latch.await(30, TimeUnit.SECONDS))
        println("PROBE voices=" + tts.voices?.map { "${it.name}@${it.locale}" })

        val out = File(ctx.cacheDir, "probe_zip.wav")
        if (out.exists()) out.delete()
        val code = tts.synthesizeToFile("你好，我是侍读郎。", null, out, "zip")
        println("PROBE code=$code")
        assertEquals(android.speech.tts.TextToSpeech.SUCCESS, code)
        var waited = 0L
        while (waited < 180_000 && out.length() <= 48_000) { Thread.sleep(500); waited += 500 }
        Thread.sleep(800)
        println("PROBE wav=${out.length()} bytes waited=${waited}ms")
        assertTrue("没生成音频", out.length() > 48_000)
        tts.shutdown()
    }

    private fun ensureModel(ctx: android.content.Context): Boolean {
        val need = listOf(
            "zipvoice/encoder.int8.onnx", "zipvoice/decoder.int8.onnx",
            "zipvoice/vocos_24khz.onnx", "zipvoice/cloned/leijun-1.wav"
        )
        if (need.all { File(ctx.filesDir, it).length() > 1024 }) {
            println("PROBE 模型已在本地")
            return true
        }
        val done = CountDownLatch(1)
        var ok = false
        kotlinx.coroutines.runBlocking {
            ModelRepository.downloadModel(ctx, "zipvoice_zh_en") { success ->
                ok = success; done.countDown()
            }
        }
        done.await(10, TimeUnit.MINUTES)
        println("PROBE download ok=$ok")
        need.forEach { println("PROBE   $it = " + File(ctx.filesDir, it).length()) }
        return ok
    }
}
