package com.leedor.tts.engine

import android.content.Context
import com.leedor.tts.data.ModelRepository
import com.leedor.tts.data.PrefsManager
import com.leedor.tts.engine.piper.PiperEngine
import com.leedor.tts.engine.zipvoice.ZipVoiceEngine
import com.leedor.tts.engine.pocket.PocketTtsEngine

/**
 * 按模型索引（`assets/models.json`）里声明的 `engine` 字段创建对应引擎。
 *
 * 之所以改成查索引而不是硬编码 id 的 if/else：
 * 新增模型时只需要在 JSON 里写 `"engine": "xxx"`，不用再改这里。
 */
object EngineFactory {

    /**
     * @param modelId 显式指定模型；为 null 时用用户偏好里的当前模型。
     */
    fun createEngine(context: Context, modelId: String? = null): TtsEngine {
        val prefs = PrefsManager(context)
        val selectedModel = modelId ?: prefs.currentModel
        val engine = ModelRepository.engineOf(selectedModel)

        android.util.Log.i(
            "EngineFactory",
            "createEngine: modelId=$modelId, current=${prefs.currentModel}, selected=$selectedModel, engine=$engine"
        )

        return when (engine) {
            "pocket" -> PocketTtsEngine(context)

            // Kokoro v1.0 与 v1.1-zh 共用同一个引擎类，
            // 内部按 modelId 决定走英文音素器还是多语言词典前端。
            "kokoro", "kitten", "kokoro_multilang" -> KokoroEngine(context, selectedModel)

            "zipvoice" -> ZipVoiceEngine(context, selectedModel)

            "piper" -> PiperEngine(context, selectedModel.removePrefix("piper_"))

            else -> {
                android.util.Log.w("EngineFactory", "未知 engine='$engine'，回退到 Kokoro")
                KokoroEngine(context, selectedModel)
            }
        }
    }
}
