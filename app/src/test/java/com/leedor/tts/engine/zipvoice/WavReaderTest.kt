package com.leedor.tts.engine.zipvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * WAV 解码的回归测试。
 *
 * 真实 bug：ZipVoice 拿参考音频时把 16-bit PCM 当**无符号**读，
 * 每个负样本都变成 65535 附近的正数（`65535/32768f = 2.0f`）。
 * 参考音频被整流成 0..2 的畸变波形 → 参考梅尔完全失真 →
 * 克隆出来的声音**全是杂音**，而且比正常响很多。
 * 因为帧数仍然正确，只看时长/帧数日志根本发现不了。
 */
class WavReaderTest {

    /** 造一个 16-bit 单声道 WAV，采样值取自 [samples]（范围 -1..1）。 */
    private fun makeWav(samples: IntArray, sampleRate: Int = 24_000, channels: Int = 1): ByteArray {
        val data = ByteArrayOutputStream()
        for (s in samples) {
            data.write(s and 0xFF)
            data.write((s shr 8) and 0xFF)
        }
        val dataBytes = data.toByteArray()
        val out = ByteArrayOutputStream()
        fun str(v: String) = out.write(v.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

        str("RIFF"); i32(36 + dataBytes.size); str("WAVE")
        str("fmt "); i32(16); i16(1); i16(channels); i32(sampleRate)
        i32(sampleRate * channels * 2); i16(channels * 2); i16(16)
        str("data"); i32(dataBytes.size); out.write(dataBytes)
        return out.toByteArray()
    }

    @Test
    fun `负样本必须解码成负数`() {
        // +0.5, -0.5, +0.25, -0.25, 满量程正负
        val wav = makeWav(intArrayOf(16384, -16384, 8192, -8192, 32767, -32768))
        val decoded = decodeWav(wav)
        assertNotNull("应能解析", decoded)
        val s = decoded!!.samples
        assertEquals(24_000, decoded.sampleRate)
        assertEquals(1, decoded.channels)
        assertEquals(6, s.size)
        assertEquals("正样本", 0.5f, s[0], 1e-4f)
        assertEquals("负样本必须是负数（曾经读成 2.0）", -0.5f, s[1], 1e-4f)
        assertEquals(0.25f, s[2], 1e-4f)
        assertEquals(-0.25f, s[3], 1e-4f)
        assertEquals(32767 / 32768f, s[4], 1e-4f)
        assertEquals(-1.0f, s[5], 1e-4f)
    }

    @Test
    fun `有符号读取小端 16 位`() {
        val b = byteArrayOf(0x00, 0x80.toByte())      // 0x8000 -> -32768
        assertEquals(-32768, readInt16LESigned(b, 0))
        val c = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) // 0xFFFF -> -1
        assertEquals(-1, readInt16LESigned(c, 0))
        val d = byteArrayOf(0x00, 0x40)               // 0x4000 -> 16384
        assertEquals(16384, readInt16LESigned(d, 0))
    }

    @Test
    fun `立体声只取第一声道`() {
        // 两个声道交错：L=+0.5, R=-0.5, L=+0.25, R=-0.25
        val wav = makeWav(intArrayOf(16384, -16384, 8192, -8192), channels = 2)
        val decoded = decodeWav(wav)!!
        assertEquals(2, decoded.samples.size)
        assertEquals(0.5f, decoded.samples[0], 1e-4f)
        assertEquals(0.25f, decoded.samples[1], 1e-4f)
    }

    @Test
    fun `非 WAV 数据返回 null`() {
        assertNull(decodeWav(ByteArray(64)))
        assertNull(decodeWav("not a wav at all".toByteArray()))
    }
}
