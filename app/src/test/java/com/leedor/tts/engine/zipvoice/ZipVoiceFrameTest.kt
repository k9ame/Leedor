package com.leedor.tts.engine.zipvoice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归：解码器输出必须**丢掉参考音频那段**再送声码器。
 *
 * 真实 bug：模型把参考音频也重建在序列开头（实测 568 帧参考 + 212 帧合成 = 780 帧），
 * 我们当时把 780 帧整段喂给声码器 → 先输出 6 秒参考音频的重建结果（4 步 flow + int8，
 * 那一段是乱的）→ 用户听到"全是乱音"。
 */
class ZipVoiceFrameTest {

    /** 造一个 (T, C) 的行优先数组，值 = 帧号*100 + 通道号，便于断言。 */
    private fun makeX(frames: Int, dim: Int) = FloatArray(frames * dim) { i ->
        val t = i / dim
        val c = i % dim
        (t * 100 + c).toFloat()
    }

    @Test
    fun `丢掉参考帧并转置为 通道×帧`() {
        // 10 帧、2 通道；前 4 帧是参考音频 → 只保留 6..9 帧（第 4..9 帧）
        val x = makeX(10, 2)
        val out = selectGeneratedFrames(x, numFrames = 10, featDim = 2, promptFrames = 4, invFeatScale = 1f)

        assertEquals("保留 6 帧 × 2 通道", 12, out.size)
        // (C, T) 布局：out[c*kept + t]
        // 通道 0：帧 4,5,6,7,8,9
        assertArrayEquals(
            floatArrayOf(400f, 500f, 600f, 700f, 800f, 900f),
            out.copyOfRange(0, 6), 0f
        )
        // 通道 1：同帧，通道号 +1
        assertArrayEquals(
            floatArrayOf(401f, 501f, 601f, 701f, 801f, 901f),
            out.copyOfRange(6, 12), 0f
        )
    }

    @Test
    fun `除以 feat_scale 还原量纲`() {
        val x = makeX(3, 1)                       // 0, 100, 200
        val out = selectGeneratedFrames(x, 3, 1, promptFrames = 1, invFeatScale = 10f)
        assertArrayEquals("第 1、2 帧 ×10", floatArrayOf(1000f, 2000f), out, 0f)
    }

    @Test
    fun `没有可合成内容时返回空数组`() {
        val x = makeX(5, 2)
        assertEquals(0, selectGeneratedFrames(x, 5, 2, promptFrames = 5, invFeatScale = 10f).size)
        assertEquals(0, selectGeneratedFrames(x, 5, 2, promptFrames = 9, invFeatScale = 10f).size)
    }
}
