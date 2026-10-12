package com.leedor.tts.ui

import androidx.annotation.StringRes
import com.leedor.tts.R

/**
 * 模型 id → 本地化显示文案。
 *
 * 之所以不把文案放进 `assets/models.json`：那份索引是**数据**（id、引擎、文件列表），
 * 一旦写入英文/中文文案，双语就会被写死。这里按 id 映射到字符串资源，
 * 索引文件保持语言中立。
 *
 * 新增模型时：在 models.json 里加一条，然后在这里加一组 name/desc 资源即可。
 */
object ModelDisplay {

    @StringRes
    fun nameRes(modelId: String?): Int = when (modelId) {
        "piper_en_US-amy-low" -> R.string.model_piper_name
        "kokoro_v1.1-zh" -> R.string.model_kokoro_v11zh_name
        "zipvoice_zh_en" -> R.string.model_zipvoice_name
        "pocket_v1" -> R.string.model_pocket_name
        "kitten_nano" -> R.string.model_kitten_name
        else -> R.string.model_unknown_name
    }

    @StringRes
    fun descriptionRes(modelId: String?): Int = when (modelId) {
        "piper_en_US-amy-low" -> R.string.model_piper_desc
        "kokoro_v1.1-zh" -> R.string.model_kokoro_v11zh_desc
        "zipvoice_zh_en" -> R.string.model_zipvoice_desc
        "pocket_v1" -> R.string.model_pocket_desc
        "kitten_nano" -> R.string.model_kitten_desc
        else -> R.string.model_unknown_desc
    }

    /** 该模型是否支持中文（索引文件里声明了 `zh`）。 */
    fun supportsChinese(modelId: String?): Boolean =
        com.leedor.tts.data.ModelRepository.find(modelId)?.supportsChinese ?: false
}
