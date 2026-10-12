package com.leedor.tts.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.leedor.tts.R
import com.leedor.tts.engine.ModelLanguageSupport

/**
 * 模型的中文能力标注。
 *
 * 三种状态分开显示，不含糊：
 *  - ✅ 支持中文朗读        —— 模型支持且本应用链路可用
 *  - ⚠️ 模型有中文但不可用   —— 模型训练过中文，但缺中文音素（G2P）模块
 *  - ❌ 不支持中文          —— 模型本身只训练了英语
 *
 * @param onDark 深色背景（如初始引导页）上用高对比度配色，
 *               否则主题色在该背景上对比度不足。
 */
@Composable
fun ChineseCapabilityLabel(modelId: String, onDark: Boolean = false) {
    val (textRes, color) = when {
        ModelLanguageSupport.modelCanSpeakChinese(modelId) ->
            R.string.lang_model_supported to
                if (onDark) Color(0xFF81C784) else MaterialTheme.colorScheme.primary

        ModelLanguageSupport.modelSupportsChinese(modelId) ->
            R.string.lang_model_model_only to
                if (onDark) Color(0xFFFFB74D) else MaterialTheme.colorScheme.tertiary

        else ->
            R.string.lang_model_unsupported to
                if (onDark) Color.White.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}
