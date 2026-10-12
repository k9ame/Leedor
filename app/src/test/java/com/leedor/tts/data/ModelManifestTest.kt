package com.leedor.tts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型索引（`assets/models.json`）的单元测试。
 *
 * 这里直接读**真实的** assets/models.json（单元测试的工作目录是模块根目录），
 * 所以能保证索引文件本身始终可解析、字段没写错。
 */
class ModelManifestTest {

    private fun loadReal(): ModelManifest {
        val json = java.io.File("src/main/assets/models.json").readText(Charsets.UTF_8)
        return ModelManifest.fromJson(json)
    }

    @Test
    fun `真实索引文件可以解析`() {
        val m = loadReal()
        assertEquals(1, m.schemaVersion)
        assertTrue("模型数量应大于 0", m.models.size > 0)
    }

    @Test
    fun `每个模型都必须有 id 和 engine`() {
        for (model in loadReal().models) {
            assertTrue("模型缺少 id", model.id.isNotBlank())
            assertTrue("模型 ${model.id} 缺少 engine", model.engine.isNotBlank())
        }
    }

    @Test
    fun `每个文件都必须有 path 和至少一个 url`() {
        for (model in loadReal().models) {
            for (f in model.files) {
                assertTrue("${model.id} 里有文件缺少 path", f.path.isNotBlank())
                assertTrue(
                    "${model.id}/${f.path} 没有配 url",
                    f.urls.isNotEmpty() && f.urls.all { it.startsWith("http") }
                )
            }
        }
    }

    @Test
    fun `内置模型不应该有需要下载的文件`() {
        val bundled = loadReal().models.filter { it.bundled }
        assertTrue("应至少有一个内置模型", bundled.isNotEmpty())
        for (m in bundled) {
            assertTrue("内置模型 ${m.id} 不应有下载文件", m.files.isEmpty())
        }
    }

    @Test
    fun `不支持中文的模型必须被隐藏`() {
        val m = loadReal()
        for (id in listOf("pocket_v1", "kitten_nano")) {
            val model = m.models.find { it.id == id }
            assertNotNull("索引里应保留 $id 的定义（只是隐藏）", model)
            assertTrue("$id 不支持中文，必须 hidden=true", model!!.hidden)
            assertFalse("$id 不应声明支持中文", model.languages.contains("zh"))
        }
    }

    @Test
    fun `Kokoro v1_1-zh 已登记且声明支持中文`() {
        val model = loadReal().models.find { it.id == "kokoro_v1.1-zh" }
        assertNotNull("必须登记 kokoro_v1.1-zh", model)
        assertEquals("kokoro_multilang", model!!.engine)
        assertTrue("应声明中文", model.languages.contains("zh"))
        assertFalse("应该对用户可见", model.hidden)

        // 必备文件：模型 / 词表 / 中文词典
        // （语音已改为按需下载，不再需要整包 voices.bin，见下一个测试）
        val paths = model.files.map { it.path.substringAfterLast('/') }
        for (required in listOf("model.onnx", "tokens.txt", "lexicon-zh.txt")) {
            assertTrue("缺少必备文件 $required", paths.contains(required))
        }
    }

    /**
     * 回归：Kokoro v1.1-zh 的模型文件必须是 **fp32** 的 `model.onnx`。
     *
     * 这个仓库里另外几个导出都踩过坑，换文件前务必看清：
     *
     * - `model_int8 / model_uint8 / model_quantized`：卷积被量化成 ConvInteger，
     *   ONNX Runtime 1.22 没有这个算子，建会话就抛：
     *     Could not find an implementation for ConvInteger(10) node with name '/F0.0/conv1/Conv_quant'
     *   引擎初始化直接失败 → 回退英文语音 → 中文被读成 "chinese letter"。
     *
     * - `model_fp16`：能建会话、能出声，但**听起来是"电音"**。
     *   F0（音高）预测器里的 LSTM 与 70 个 InstanceNormalization 在 fp16 下精度不足，
     *   实测音高在八度之间乱跳（帧间跳变 >50% 占 13.5%，音高标准差/中位 0.320），
     *   同一段话用 fp32 只有 0.0% / 0.134。
     *
     * 所以只剩 fp32 可用。好在它的图接口仍是 float32
     * （input_ids:int64 / style:float32 / speed:float32 / waveform:float32），
     * 引擎侧不需要为此改代码。
     */
    @Test
    fun `Kokoro v1_1-zh 的模型文件必须是能跑的 fp32 导出`() {
        val model = loadReal().models.find { it.id == "kokoro_v1.1-zh" }!!
        val onnx = model.files.single { it.path.endsWith(".onnx") }
        val url = onnx.urls.first()

        assertTrue("必须是 fp32 的 model.onnx，实际是 $url", url.endsWith("/onnx/model.onnx"))
        // ConvInteger：ORT 1.22 建不了会话
        for (bad in listOf("model_int8.onnx", "model_uint8.onnx", "model_quantized.onnx")) {
            assertFalse("$bad 含 ConvInteger，ORT 1.22 建不了会话", url.endsWith(bad))
        }
        // fp16：能跑但音高会乱跳（电音）
        assertFalse("model_fp16.onnx 会让音高在八度间乱跳（电音）", url.endsWith("model_fp16.onnx"))
        assertEquals(
            "文件大小应与 ModelScope 上的 model.onnx 一致",
            339_369_442L, onnx.sizeBytes
        )
    }

    /**
     * 声明了 sizeBytes 的文件，大小必须 > 1024。
     *
     * 下载端用「存在 && >1KB && 与声明大小完全一致」判断文件是否有效，
     * 声明成几百字节的话这个文件永远过不了校验，模型会一直重下。
     */
    @Test
    fun `声明了大小的文件必须大于 1KB`() {
        for (model in loadReal().models) {
            for (f in model.files) {
                if (f.sizeBytes > 0) {
                    assertTrue(
                        "${model.id} 的 ${f.path} 声明了 ${f.sizeBytes} 字节，永远过不了 >1KB 的校验",
                        f.sizeBytes > 1024
                    )
                }
            }
        }
    }

    /**
     * 回归：模型目录必须唯一，且语音目录约定为 `<模型目录>/voices/`。
     *
     * 这里踩过坑：下载端曾经用模型 id 拼路径（`kokoro_v1.1-zh`，下划线），
     * 引擎却按索引里声明的目录找（`kokoro-v1.1-zh`，连字符），
     * 于是语音明明下载成功、合成时却说「找不到语音文件」。
     * 现在两端都走 `ModelRepository.voiceFile()`，目录以索引里第一个文件的父目录为准。
     */
    @Test
    fun `Kokoro v1_1-zh 的模型文件与语音目录必须同层`() {
        val model = loadReal().models.find { it.id == "kokoro_v1.1-zh" }!!
        val dirs = model.files.map { it.path.substringBeforeLast('/', "") }.distinct()
        assertEquals("所有文件应放在同一个目录里", 1, dirs.size)
        assertEquals("语音目录 = 模型目录/voices", "kokoro-v1.1-zh", dirs.single())
        assertTrue(
            "语音模板的最后一段应与本地 <模型目录>/voices/{voice}.bin 对应",
            model.voiceUrlTemplate.endsWith("/voices/{voice}.bin")
        )
    }

    /**
     * Kokoro v1.0 必须彻底下架。
     *
     * 它是纯英文旧模型，而且它的语音表（af_heart…bm_lewis）曾让
     * 「选 Kokoro 1.1 → 进语音库 → 模型变回 1.0」这个 bug 得以发生。
     */
    @Test
    fun `Kokoro v1_0 已从索引下架`() {
        val m = loadReal()
        assertTrue(
            "索引里不应再有 kokoro_v1.0",
            m.models.none { it.id == "kokoro_v1.0" }
        )
        // 下架之后不该有别的条目把它的文件继续列进来
        assertTrue(
            "不应再有模型引用 kokoro-v1.0 的文件",
            m.models.none { model -> model.files.any { it.path.contains("kokoro-v1.0") } }
        )
    }

    @Test
    fun `Piper 用 idPrefix 匹配动态的运行时 id`() {
        // Piper 的运行时模型 id 是 `piper_<语音名>`，会随用户在语音库换语音而变。
        // 设置页必须能认出「当前选的是 Piper」，否则会出现
        // 「没有任何条目被选中」或者「Piper 出现两次」这类问题。
        val piper = loadReal().models.find { it.id == "piper_en_US-amy-low" }
        assertNotNull("索引里应有 Piper 条目", piper)
        assertEquals("Piper 必须声明 idPrefix", "piper_", piper!!.idPrefix)
        assertTrue("Piper 应标记为多语音", piper.multiVoice)

        // 各种 Piper 运行时 id 都应命中
        for (runtimeId in listOf(
            "piper_en_US-amy-low",
            "piper_zh_CN-huayan-x_low",
            "piper_zh_CN-huayan-medium",
            "piper_ta_IN-Valluvar-medium",
        )) {
            assertTrue("$runtimeId 应匹配 Piper 条目", piper.matches(runtimeId))
        }

        // 非 Piper 的不应误命中
        for (other in listOf("kokoro_v1.0", "kokoro_v1.1-zh", "zipvoice_zh_en", null)) {
            assertFalse("$other 不应匹配 Piper", piper.matches(other))
        }
    }

    @Test
    fun `非 Piper 模型用精确 id 匹配`() {
        val zh = loadReal().models.find { it.id == "kokoro_v1.1-zh" }!!
        assertTrue(zh.matches("kokoro_v1.1-zh"))
        assertFalse("不应前缀误匹配", zh.matches("kokoro_v1.1-zh-extra"))
        assertFalse(zh.matches("kokoro_v1.0"))
    }

    @Test
    fun `每个模型的 id 都不重复且不会被别的模型误匹配`() {
        val models = loadReal().models
        for (m in models) {
            val hits = models.filter { it.matches(m.id) }
            assertEquals("id=${m.id} 被 ${hits.map { it.id }} 同时匹配", 1, hits.size)
        }
    }

    @Test
    fun `Kokoro v1_1-zh 的语音走按需下载模板`() {
        // 103 个语音如果打成整包要 51 MB；按需只下选中的那一个（0.5 MB）。
        // 语音名来自 ONNX 元数据的 speaker_names，不在这里登记。
        val m = loadReal().models.find { it.id == "kokoro_v1.1-zh" }!!
        assertTrue("必须标记 multiVoice", m.multiVoice)
        assertTrue(
            "必须配 voiceUrlTemplate，否则语音无法按需下载",
            m.voiceUrlTemplate.contains("{voice}")
        )
        assertFalse(
            "模板里不应残留占位符以外的大括号",
            m.voiceUrlTemplate.replace("{voice}", "").contains("{")
        )
        // 不应再依赖整包 voices.bin
        val paths = m.files.map { it.path.substringAfterLast('/') }
        assertFalse("已改为按需，不应再要求 voices.bin", paths.contains("voices.bin"))
    }

    @Test
    fun `Kokoro v1_1-zh 全部文件集中在一个源上`() {
        // 之前模型+词表在 ModelScope、词典在 HF，两边都要能通才能用；
        // 现在应该集中在同一个 host，降低失败面。
        val m = loadReal().models.find { it.id == "kokoro_v1.1-zh" }!!
        val hosts = (m.files.flatMap { it.urls } + m.voiceUrlTemplate)
            .filter { it.startsWith("http") }
            .map { it.substringAfter("://").substringBefore("/") }
            .toSet()
        assertEquals("所有文件应来自同一个 host，实际: $hosts", 1, hosts.size)
    }

    @Test
    fun `多语音模型必须有可用的语音来源`() {
        // 要么有 voiceUrlTemplate（按需下载），要么有 voices.bin（整包），
        // 否则语音库会是空的。
        for (m in loadReal().models) {
            if (!m.multiVoice || m.bundled) continue
            val hasTemplate = m.voiceUrlTemplate.isNotBlank()
            val hasPack = m.files.any { it.path.endsWith("voices.bin") }
            assertTrue("${m.id} 既没有 voiceUrlTemplate 也没有 voices.bin", hasTemplate || hasPack)
        }
    }

    @Test
    fun `Kokoro v1_1-zh 的语音列表已登记在索引里`() {
        // 回归测试：曾经依赖 ONNX 元数据的 speaker_names 来拿语音名，
        // 但 ModelScope 上那个仓库用的是 onnx-community 的导出，
        // **没有这个元数据字段**，导致语音库一片空白。
        // 现在权威列表登记在索引里，不再依赖导出方的元数据。
        val m = loadReal().models.find { it.id == "kokoro_v1.1-zh" }!!
        assertEquals("应该有 103 个语音", 103, m.voices.size)

        val zf = m.voices.filter { it.startsWith("zf_") }
        val zm = m.voices.filter { it.startsWith("zm_") }
        val en = m.voices.filter { !it.startsWith("zf_") && !it.startsWith("zm_") }
        assertEquals("女声 55 个", 55, zf.size)
        assertEquals("男声 45 个", 45, zm.size)
        assertEquals("英文 3 个", 3, en.size)

        // 不能有重复
        assertEquals("语音名不应重复", m.voices.size, m.voices.toSet().size)

        // 每个名字都要能套进下载模板
        for (v in m.voices) {
            assertTrue("语音名 $v 不符合命名约定", Regex("^[a-z]{2}_[a-z0-9_]+$").matches(v))
        }
    }

    @Test
    fun `多语音模型的 voices 与 voiceUrlTemplate 必须配套`() {
        for (m in loadReal().models) {
            if (m.voices.isEmpty()) continue
            assertTrue(
                "${m.id} 登记了 voices 但没配 voiceUrlTemplate，语音无法下载",
                m.voiceUrlTemplate.contains("{voice}")
            )
        }
    }

    /**
     * ZipVoice 自带音色（上游 test_wavs/ 的三个）必须完整接进索引。
     *
     * 之前只登记了 5 个模型文件，语音库是空的 —— 用户会以为这个模型没有音色。
     * 三个音色要满足：
     *  1. 出现在 voices 里（语音库靠它列出）；
     *  2. 登记成 `zipvoice/cloned/<名字>.wav` 的可选文件（引擎就是去这里找参考音频；
     *     标 optional 是为了不让老用户变成「未安装」）；
     *  3. 有参考文本（voicePrompts）—— 零样本克隆缺了它音色会跑偏。
     */
    @Test
    fun `ZipVoice 自带音色已完整登记`() {
        val m = loadReal().models.find { it.id == "zipvoice_zh_en" }!!
        assertEquals("应有 3 个自带音色", 3, m.voices.size)
        assertTrue("应声明为多语音模型", m.multiVoice)
        assertTrue("没有 voiceUrlTemplate 就没法按需补下", m.voiceUrlTemplate.contains("{voice}"))

        for (v in m.voices) {
            val file = m.files.find { it.path == "zipvoice/cloned/$v.wav" }
            assertNotNull("自带音色 $v 没有登记音频文件", file)
            assertTrue("自带音色 $v 的音频应为可选文件（否则老用户会变成未安装）", file!!.optional)
            assertTrue("自带音色 $v 的文件大小必须是已知的（0 就失去校验意义）", file.sizeBytes > 1024)
            assertTrue(
                "自带音色 $v 缺少参考文本 voicePrompts",
                !m.voicePrompts[v].isNullOrBlank()
            )
        }
    }

    @Test
    fun `空的或损坏的 JSON 不会崩溃`() {
        assertEquals(0, ModelManifest.fromJson("{}").models.size)
        assertEquals(0, ModelManifest.fromJson("not json at all").models.size)
        assertEquals(0, ModelManifest.fromJson("").models.size)
    }
}
