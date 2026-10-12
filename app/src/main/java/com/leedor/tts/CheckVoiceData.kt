package com.leedor.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.leedor.tts.engine.VoiceLocales

/**
 * 响应系统的 `CHECK_TTS_DATA` 查询，回报本引擎可用的语言。
 *
 * 返回的字符串格式是 `{ISO3语言}-{ISO3国家}`，例如 `zho-CHN`、`eng-USA`。
 * 语言清单与 [VoiceLocales.SUPPORTED] 保持一致，避免与 onIsLanguageAvailable 打架。
 */
class CheckVoiceData : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val result = Intent()

        val availableVoices = ArrayList<String>()
        for (locale in VoiceLocales.SUPPORTED) {
            val lang = runCatching { locale.isO3Language }.getOrNull().orEmpty()
            val country = runCatching { locale.isO3Country }.getOrNull().orEmpty()
            if (lang.isNotEmpty() && country.isNotEmpty()) {
                availableVoices.add("$lang-$country")
            }
        }

        result.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
            availableVoices
        )

        // 中文语音需要用户自行放置模型文件（见 docs/zh-models.md）。
        // 这属于"语音数据未下载"，而不是"语言不支持"，
        // 所以不能上报到 unavailable，否则系统会直接把整个引擎隐藏掉。
        result.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
            arrayListOf()
        )

        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}
