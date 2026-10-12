package com.leedor.tts.engine.zipvoice

/**
 * 16-bit PCM WAV 解码结果。
 *
 * @param samples 采样值，范围 [-1, 1)，多声道只取第一声道
 * @param sampleRate 采样率
 * @param channels 原始声道数
 */
internal data class DecodedWav(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int
)

/**
 * 解码 16-bit PCM WAV（对齐 ZipVoice 的参考音频需求）。
 *
 * ⚠️ **PCM 采样是有符号 16 位整数**，这是这里踩过的坑：
 * 早期实现按无符号拼字节（`(hi shl 8) or lo`），于是每一个负样本都被读成
 * 65535 附近的正数（`65535 / 32768f = 2.0f`），参考音频被"整流"成
 * 0..2 的畸变波形。后果是：参考梅尔完全失真 → 克隆条件崩掉 →
 * **合成出来全是杂音**，而且比正常响得多（波形均值被抬高）。
 * 帧数还是对的（时长不受影响），所以只看着主机日志很难发现。
 *
 * 对照：`PocketTtsEngine` 的同类读取用了 `.toShort()` 做符号扩展，是正确的。
 *
 * @return 解析失败返回 null
 */
internal fun decodeWav(bytes: ByteArray): DecodedWav? {
    if (bytes.size < 44) return null
    if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null

    var offset = 12
    var sampleRate = 24_000
    var channels = 1
    var bits = 16
    var dataOffset = -1
    var dataSize = 0

    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4)
        val size = readInt32LE(bytes, offset + 4)
        val body = offset + 8

        when (id) {
            "fmt " -> {
                channels = readInt16LE(bytes, body + 2)
                sampleRate = readInt32LE(bytes, body + 4)
                bits = readInt16LE(bytes, body + 14)
            }
            "data" -> {
                dataOffset = body
                dataSize = minOf(size, bytes.size - body)
            }
        }
        // chunk 按偶数对齐
        offset = body + size + (size and 1)
    }

    if (dataOffset < 0 || dataSize <= 0) return null
    if (bits != 16) return null
    if (channels <= 0) return null

    val totalSamples = dataSize / 2
    val perChannel = totalSamples / channels
    if (perChannel <= 0) return null

    val out = FloatArray(perChannel)
    for (i in 0 until perChannel) {
        // 多声道取第一声道
        val idx = dataOffset + i * channels * 2
        if (idx + 1 >= bytes.size) break
        out[i] = readInt16LESigned(bytes, idx) / 32768f
    }
    return DecodedWav(out, sampleRate, channels)
}

/** 有符号 16 位小端读取（PCM 采样必须用它）。 */
internal fun readInt16LESigned(b: ByteArray, i: Int): Int {
    val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    return if (v >= 0x8000) v - 0x10000 else v
}

/** 头部字段用的小端读取（这些字段都是正数，无符号即可）。 */
internal fun readInt16LE(b: ByteArray, i: Int): Int =
    (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

internal fun readInt32LE(b: ByteArray, i: Int): Int =
    (b[i].toInt() and 0xFF) or
        ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or
        ((b[i + 3].toInt() and 0xFF) shl 24)
