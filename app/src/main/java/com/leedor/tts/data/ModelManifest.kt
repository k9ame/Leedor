package com.leedor.tts.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * `assets/models.json` 的数据结构。
 *
 * 把「有哪些模型、每个模型要下哪些文件」从 Kotlin 代码里挪到声明式索引文件，
 * 好处是：
 *  - 新增模型只改 JSON，不动代码
 *  - 每个文件可以配多个镜像 URL
 *  - 中英双语的显示文案不写死在这里（由 [ModelDisplay] 映射到字符串资源）
 */
data class ManifestFile(
    /** 相对 `filesDir` 的路径，例如 `kokoro-v1.1-zh/model.int8.onnx`。 */
    val path: String = "",
    /** 期望字节数，仅用于界面显示；为 0 表示未知。 */
    val sizeBytes: Long = 0,
    /** 候选下载地址，按顺序尝试。 */
    val urls: List<String> = emptyList(),
    /**
     * 可选文件：缺失**不算未安装**。
     * 用于「整包语音」这类有按需替代方案的大文件。
     */
    val optional: Boolean = false
)

data class ManifestModel(
    val id: String = "",
    /**
     * 运行时模型 id 的**前缀**。
     *
     * Piper 是个「语音家族」：运行时 id 形如 `piper_<语音名>`，
     * 用户在语音库里换语音后 id 会变。用前缀匹配才能认出「当前选的是 Piper」。
     * 留空表示用 [id] 精确匹配。
     */
    val idPrefix: String = "",
    /** 用哪个引擎加载：piper / kokoro / kokoro_multilang / zipvoice / pocket / kitten */
    val engine: String = "",
    /** 该模型是否是多语音（语音在「语音库」里单独选）。 */
    val multiVoice: Boolean = false,
    /**
     * 单个语音的下载地址模板，`{voice}` 会被替换成语音名。
     * 用于「只下用户选中的那一个语音」，避免下载整个语音包。
     */
    val voiceUrlTemplate: String = "",
    /**
     * 多语音模型的语音名列表。
     *
     * 之所以要写在这里：sherpa 的导出会把 `speaker_names` 写进 ONNX 元数据，
     * 但 **onnx-community 的导出没有这个字段**（实测确认）。
     * 为了不依赖某个导出方的元数据，统一在索引里登记权威列表。
     */
    val voices: List<String> = emptyList(),
    /**
     * 语音名 → **参考文本**。
     *
     * 只有零样本克隆类模型（ZipVoice）用得到：它的「音色」就是一段参考音频，
     * 合成时必须同时告诉模型这段音频说了什么，否则音色会跑偏。
     * 上游把这个清单放在 `test_wavs/prompt.txt`；这里登记成结构化字段，
     * 免得为了三行文本再下一个文件，还要处理"老用户没有这个文件"的情况。
     */
    val voicePrompts: Map<String, String> = emptyMap(),
    val version: String = "1.0",
    /** ISO 639-1 语言代码，用于界面上的中文能力标注。 */
    val languages: List<String> = emptyList(),
    /** 是否从界面隐藏（不支持中文的模型）。 */
    val hidden: Boolean = false,
    /** 是否随 APK 打包（无需下载）。 */
    val bundled: Boolean = false,
    val files: List<ManifestFile> = emptyList()
) {
    /**
     * 给定的**运行时**模型 id 是否属于这个条目。
     *
     * Piper 的运行时 id 是 `piper_<语音名>`（会随用户换语音而变），
     * 所以用 [idPrefix] 前缀匹配；其它模型用 [id] 精确匹配。
     *
     * [com.leedor.tts.data.ModelInfo.matches] 是这里的运行时版本，两者语义必须一致。
     */
    fun matches(runtimeModelId: String?): Boolean {
        if (runtimeModelId.isNullOrBlank()) return false
        return if (idPrefix.isNotEmpty()) runtimeModelId.startsWith(idPrefix)
        else runtimeModelId == id
    }
}

data class ModelManifest(
    @SerializedName("schemaVersion") val schemaVersion: Int = 0,
    val models: List<ManifestModel> = emptyList()
) {
    companion object {
        private const val TAG = "ModelManifest"
        const val ASSET_NAME = "models.json"

        /** 从 assets 读取。失败时返回空清单（而不是崩溃），让上层能给出明确提示。 */
        fun load(context: Context): ModelManifest = try {
            val json = context.assets.open(ASSET_NAME)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            fromJson(json)
        } catch (t: Throwable) {
            Log.e(TAG, "读取 $ASSET_NAME 失败", t)
            ModelManifest()
        }

        /**
         * 直接解析 JSON 字符串（便于单元测试，不需要 Context）。
         *
         * 语法错误 / 字段类型不对都不抛异常，而是返回空清单 —— 索引文件坏掉时
         * 应该表现为「界面上没有可选模型」，而不是整个应用崩掉。
         */
        fun fromJson(json: String): ModelManifest {
            val manifest = try {
                Gson().fromJson(json, ModelManifest::class.java)
            } catch (t: Throwable) {
                Log.e(TAG, "模型索引 JSON 解析失败", t)
                null
            }
            if (manifest == null || manifest.models.isEmpty()) {
                Log.e(TAG, "模型索引解析为空")
                return ModelManifest()
            }
            Log.i(TAG, "已加载 ${manifest.models.size} 个模型定义")
            return manifest
        }
    }
}
