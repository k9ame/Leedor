package com.leedor.tts.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class ModelFile(
    /** 相对 filesDir 的路径。 */
    val fileName: String,
    /** 首选下载地址（= [urls] 的第一个，保留这个字段是为了兼容既有调用）。 */
    val downloadUrl: String,
    val description: String = "",
    /** 全部候选地址（多镜像）。下载时按顺序尝试。 */
    val urls: List<String> = emptyList(),
    val sizeBytes: Long = 0,
    /** 可选文件：缺失不算未安装。 */
    val optional: Boolean = false
) {
    /** 实际可用的候选地址列表（至少含 downloadUrl）。 */
    val candidates: List<String>
        get() = if (urls.isNotEmpty()) urls else listOfNotNull(downloadUrl.ifEmpty { null })
}

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val files: List<ModelFile>,
    val version: String = "1.0",
    /**
     * 是否从界面上隐藏。
     *
     * 侍读郎是中文朗读应用，Pocket-TTS 与 Kitten 都不支持中文，所以不对外展示。
     * **代码与模型定义完整保留** —— 只是不显示，需要时把这里改回 false 即可恢复。
     */
    val hidden: Boolean = false,
    /** 用哪个引擎加载：piper / kokoro / kokoro_multilang / zipvoice / pocket / kitten */
    val engine: String = "kokoro",
    /** ISO 639-1 语言代码。 */
    val languages: List<String> = emptyList(),
    /** 是否随 APK 打包（无需下载）。 */
    val bundled: Boolean = false,
    /**
     * 运行时模型 id 的前缀（Piper 用 `piper_`）。留空则精确匹配 [id]。
     * 见 [matches]。
     */
    val idPrefix: String = "",
    /** 该模型是否是多语音（语音在「语音库」里单独选）。 */
    val multiVoice: Boolean = false,
    /** 单个语音的下载地址模板，`{voice}` 会被替换。 */
    val voiceUrlTemplate: String = "",
    /** 该模型的语音名列表（来自索引）。 */
    val voices: List<String> = emptyList(),
    /** 语音名 → 参考文本（ZipVoice 这类零样本克隆模型用）。 */
    val voicePrompts: Map<String, String> = emptyMap()
) {
    /**
     * 给定的运行时模型 id 是否属于这个条目。
     *
     * Piper 的运行时 id 是 `piper_<语音名>`（会随用户换语音而变），
     * 所以必须支持前缀匹配；其它模型用精确匹配。
     */
    fun matches(runtimeModelId: String?): Boolean {
        if (runtimeModelId.isNullOrBlank()) return false
        return if (idPrefix.isNotEmpty()) runtimeModelId.startsWith(idPrefix)
        else runtimeModelId == id
    }

    /** 该模型是否支持中文（依据索引文件里声明的 languages）。 */
    val supportsChinese: Boolean get() = languages.contains("zh")

    /** 需要下载的总字节数（未知为 0 的文件不计入）。 */
    val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

object ModelRepository {
    private const val TAG = "ModelRepository"

    /** 模型定义来自 assets/models.json，不再硬编码在代码里。 */
    private val manifest: ModelManifest by lazy { ModelManifest.load(appContext) }

    private lateinit var appContext: Context

    /** 必须在 Application.onCreate（或任何使用前）调用一次。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ensureInit(context: Context) {
        if (!::appContext.isInitialized) init(context)
    }

    private fun toModelInfo(m: ManifestModel): ModelInfo = ModelInfo(
        id = m.id,
        name = m.id,              // 显示名由 ModelDisplay 按 id 映射到字符串资源
        description = "",
        files = m.files.map { f ->
            ModelFile(
                fileName = f.path,
                downloadUrl = f.urls.firstOrNull().orEmpty(),
                description = f.path.substringAfterLast('/'),
                urls = f.urls,
                sizeBytes = f.sizeBytes,
                optional = f.optional
            )
        },
        version = m.version,
        hidden = m.hidden,
        engine = m.engine,
        languages = m.languages,
        bundled = m.bundled,
        idPrefix = m.idPrefix,
        multiVoice = m.multiVoice,
        voiceUrlTemplate = m.voiceUrlTemplate,
        voices = m.voices,
        voicePrompts = m.voicePrompts
    )

    /** 全部模型（含隐藏的）。 */
    val models: List<ModelInfo>
        get() {
            val ctx = if (::appContext.isInitialized) appContext else null
                ?: return emptyList()
            return manifest.models.map { toModelInfo(it) }
        }

    /**
     * 界面上可选的模型（过滤掉 [ModelInfo.hidden]）。
     * 所有 UI 都应该用这个，而不是 [models]。
     */
    val visibleModels: List<ModelInfo> get() = models.filter { !it.hidden }

    /** 该模型是否被隐藏（用户不应再选到它）。 */
    fun isHidden(modelId: String?): Boolean =
        resolve(modelId)?.hidden ?: false

    /** 按 manifest 里的 id 精确取模型定义。 */
    fun find(modelId: String?): ModelInfo? = models.find { it.id == modelId }

    /**
     * 把**运行时**模型 id 解析成 manifest 条目。
     *
     * 与 [find] 的区别：这里支持前缀匹配，所以
     * `piper_zh_CN-huayan-x_low` 能正确解析到 Piper 那条。
     */
    fun resolve(runtimeModelId: String?): ModelInfo? =
        models.find { it.matches(runtimeModelId) }

    /**
     * 该模型用哪个引擎。
     *
     * 必须走 [resolve]（前缀匹配）而不是 [find]（精确匹配）：
     * Piper 的运行时 id 是动态的（`piper_zh_CN-huayan-x_low` 这种），
     * 用 find 会匹配不上，从而把 Piper 语音错当成 Kokoro 引擎，
     * 让引擎去加载 kokoro-v1.0 的模型文件然后失败。
     */
    fun engineOf(modelId: String?): String =
        resolve(modelId)?.engine ?: "kokoro"

// Simple in-memory download state tracker
    private val _downloadStates = mutableMapOf<String, MutableStateFlow<Float>>()
    
    fun getDownloadProgress(modelId: String): StateFlow<Float>? {
        return _downloadStates[modelId]?.asStateFlow()
    }

    fun isInstalled(context: Context, modelId: String): Boolean {
        ensureInit(context)
        // 用 resolve 而不是 find：Piper 的运行时 id 是动态的
        val model = resolve(modelId) ?: return false
        // 可选文件缺失不算未安装（例如有按需下载替代的整包语音）
        return model.files.filter { !it.optional }.all { fileDef ->
            isFileValid(context, fileDef)
        }
    }

    /**
     * 本地文件是否**就是这个版本**该有的文件。
     *
     * 只看「存在且大于 1KB」是不够的：同一个文件路径在不同版本里可能指向
     * 不同的模型（例如 `kokoro-v1.1-zh/model.onnx` 先放过 fp16、后来换成 fp32），
     * 那样老文件会被当成已安装，用户修不好也下不动。
     * 所以索引里声明了 sizeBytes 时，大小必须完全一致。
     */
    private fun isFileValid(context: Context, fileDef: ModelFile): Boolean {
        val file = File(context.filesDir, fileDef.fileName)
        if (!file.exists() || file.length() <= 1024) return false
        return fileDef.sizeBytes <= 0 || file.length() == fileDef.sizeBytes
    }
    
    suspend fun downloadModel(context: Context, modelId: String, onComplete: (Boolean) -> Unit) = withContext(Dispatchers.IO) {
        ensureInit(context)
        val model = models.find { it.id == modelId } ?: return@withContext
        
        if (_downloadStates.containsKey(modelId)) return@withContext // Already downloading
        
        val progressFlow = MutableStateFlow(0f)
        _downloadStates[modelId] = progressFlow
        
        try {
            val totalFiles = model.files.size
            var filesDownloaded = 0
            
            for (fileDef in model.files) {
                val targetFile = File(context.filesDir, fileDef.fileName)
                
                // Skip if already downloaded and valid（大小不符 = 不是这个版本的文件，重下）
                if (isFileValid(context, fileDef)) {
                    Log.d(TAG, "File already exists: ${fileDef.fileName}")
                    filesDownloaded++
                    progressFlow.value = filesDownloaded.toFloat() / totalFiles
                    continue
                }
                if (targetFile.exists()) {
                    Log.w(
                        TAG,
                        "已有文件大小不符（${targetFile.length()} != ${fileDef.sizeBytes}），重新下载: ${fileDef.fileName}"
                    )
                }
                
                // Create parent directories
                targetFile.parentFile?.mkdirs()
                
                Log.i(TAG, "Downloading: ${fileDef.downloadUrl}")
                
                val success = downloadFile(fileDef.candidates, targetFile) { fileProgress ->
                    val totalProgress = (filesDownloaded + fileProgress) / totalFiles
                    progressFlow.value = totalProgress
                }
                
                if (!success) {
                    Log.e(TAG, "Failed to download: ${fileDef.fileName}")
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@withContext
                }
                
                filesDownloaded++
                Log.i(TAG, "Downloaded: ${fileDef.fileName} (${targetFile.length() / 1024}KB)")
            }
            
            Log.i(TAG, "Model $modelId download complete")
            withContext(Dispatchers.Main) { onComplete(true) }
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $modelId", e)
            e.printStackTrace()
            withContext(Dispatchers.Main) { onComplete(false) }
        } finally {
            _downloadStates.remove(modelId)
        }
    }
    
    /**
     * 下载单个文件，**自动在多个镜像主机之间回退**。
     *
     * 应用里所有 HuggingFace URL 都写死在 `huggingface.co` 上，而该域名在部分
     * 网络下完全不可达。这里把它展开成候选列表（镜像优先），逐个尝试。
     * 见 [DownloadHosts]。
     */
    private fun downloadFile(rawUrls: List<String>, targetFile: File, onProgress: (Float) -> Unit): Boolean {
        // 每个地址再按镜像策略展开（huggingface.co → hf-mirror.com）
        val candidates = rawUrls.flatMap { DownloadHosts.expand(it) }.distinct()
        for ((index, candidate) in candidates.withIndex()) {
            Log.i(TAG, "下载尝试 ${index + 1}/${candidates.size}: $candidate")
            if (downloadFromUrl(candidate, targetFile, onProgress)) return true
            Log.w(TAG, "该主机失败，换下一个: $candidate")
            targetFile.delete()
        }
        Log.e(TAG, "所有候选主机均失败: $candidates")
        return false
    }

    private fun downloadFromUrl(urlString: String, targetFile: File, onProgress: (Float) -> Unit): Boolean {
        var connection: HttpURLConnection? = null
        try {
            var currentUrl = urlString
            var redirectCount = 0
            val maxRedirects = 5
            
            // Handle redirects manually (HuggingFace uses them for LFS files)
            while (redirectCount < maxRedirects) {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 120000  // 2 min timeout for large files
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Leedor-TTS/1.0")
                connection.instanceFollowRedirects = false  // Handle redirects manually
                
                val responseCode = connection.responseCode
                Log.d(TAG, "Response code for $currentUrl: $responseCode")
                
                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> break  // Success, proceed to download
                    HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_MOVED_PERM, 
                    HttpURLConnection.HTTP_SEE_OTHER, 307, 308 -> {
                        // Follow redirect
                        val newUrl = connection.getHeaderField("Location")
                        if (newUrl == null) {
                            Log.e(TAG, "Redirect without Location header")
                            return false
                        }
                        Log.d(TAG, "Redirecting to: $newUrl")
                        currentUrl = newUrl
                        connection.disconnect()
                        redirectCount++
                    }
                    else -> {
                        Log.e(TAG, "HTTP error: $responseCode for $urlString")
                        return false
                    }
                }
            }
            
            if (redirectCount >= maxRedirects) {
                Log.e(TAG, "Too many redirects for $urlString")
                return false
            }
            
            val contentLength = connection!!.contentLengthLong
            Log.d(TAG, "Content length: $contentLength bytes")
            
            var bytesDownloaded = 0L
            
            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        
                        if (contentLength > 0) {
                            onProgress(bytesDownloaded.toFloat() / contentLength)
                        }
                    }
                }
            }
            
            Log.i(TAG, "Downloaded ${bytesDownloaded / 1024}KB to ${targetFile.name}")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            targetFile.delete() // Clean up partial download
            return false
        } finally {
            connection?.disconnect()
        }
    }
    
    /**
     * 按需下载**单个**语音文件（Kokoro v1.1-zh 这类多语音模型）。
     *
     * ModelScope 上语音是 `voices/<名字>.bin` 独立文件，每个约 0.5 MB。
     * 只下用户选中的那一个，比下载 51 MB 整包省得多，也更快。
     *
     * 下载到 `<模型目录>/voices/<名字>.bin`，之后 [com.leedor.tts.engine.KokoroEngine]
     * 会优先读这个文件。
     *
     * @return 成功返回 true
     */
    /**
     * 某个语音文件的落盘位置。
     *
     * **下载端与引擎端必须都调用这个函数**，否则会各写各的目录：
     * 曾经这里用 `model.id`（`kokoro_v1.1-zh`，下划线）拼路径，而引擎按索引里
     * 声明的目录 `kokoro-v1.1-zh`（连字符）去找，结果语音下载成功却「找不到语音文件」。
     *
     * 目录以索引里第一个文件的父目录为准（`kokoro-v1.1-zh/model.onnx` → `kokoro-v1.1-zh`），
     * 这样索引改了目录，两端一起变。
     */
    /**
     * 某模型存放「单个语音」的目录。
     *
     * - **ZipVoice**：`<模型目录>/cloned` —— 它的语音是参考音频（wav），
     *   而且引擎对自带音色和用户克隆的音色一视同仁，都去这里找。
     * - **其它（Kokoro 等）**：`<模型目录>/voices` —— style 向量（bin）。
     */
    fun voiceDir(context: Context, modelId: String): File {
        ensureInit(context)
        val model = resolve(modelId)
        val dir = model?.files?.firstOrNull()?.fileName
            ?.substringBeforeLast('/', "")
            ?.takeIf { it.isNotEmpty() }
            ?: model?.id
            ?: modelId
        val sub = if (model?.engine == "zipvoice") "cloned" else "voices"
        return File(context.filesDir, "$dir/$sub")
    }

    /** 语音文件的扩展名（ZipVoice 是 wav，其它是 bin）。 */
    private fun voiceExtension(modelId: String): String =
        if (resolve(modelId)?.engine == "zipvoice") "wav" else "bin"

    fun voiceFile(context: Context, modelId: String, voiceName: String): File =
        File(voiceDir(context, modelId), "$voiceName.${voiceExtension(modelId)}")

    /** ZipVoice 这类模型：某个语音对应的参考文本（没有就返回 null）。 */
    fun voicePromptOf(modelId: String, voiceName: String): String? =
        resolve(modelId)?.voicePrompts?.get(voiceName)?.takeIf { it.isNotBlank() }

    /**
     * 删除一个按需下载/克隆的语音文件（含它的参考文本）。
     * 引擎端与下载端都走 [voiceFile]，这里也走同一个函数，避免删错目录。
     */
    fun deleteVoiceFile(context: Context, modelId: String, voiceName: String): Boolean {
        val audio = voiceFile(context, modelId, voiceName)
        val text = File(audio.parentFile, "${audio.nameWithoutExtension}.txt")
        var ok = false
        if (audio.exists()) ok = audio.delete()
        if (text.exists()) text.delete()
        return ok
    }

    suspend fun downloadVoiceFile(
        context: Context,
        modelId: String,
        voiceName: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        ensureInit(context)
        val model = resolve(modelId) ?: return@withContext false
        if (model.voiceUrlTemplate.isBlank()) {
            Log.w(TAG, "模型 ${model.id} 没有配置 voiceUrlTemplate")
            return@withContext false
        }

        val template = model.voiceUrlTemplate
        val url = template.replace("{voice}", voiceName)
        val target = voiceFile(context, model.id, voiceName)
        target.parentFile?.mkdirs()

        if (target.exists() && target.length() > 0) {
            Log.i(TAG, "语音 $voiceName 已存在，跳过下载")
            return@withContext true
        }

        Log.i(TAG, "按需下载语音: $voiceName")
        val ok = downloadFile(listOf(url), target, onProgress)
        if (!ok) {
            Log.e(TAG, "语音 $voiceName 下载失败")
            target.delete()
        }
        ok
    }

    /**
     * 读取 Kokoro v1.1-zh 的 103 个语音名。
     *
     * 取值顺序：
     *  1. **索引里登记的 `voices`**（首选，权威且不依赖导出方）
     *  2. ONNX 元数据的 `speaker_names`（只有 sherpa 的导出才有）
     *  3. 本地缓存 `<模型目录>/voices.txt`
     *
     * 顺序与 `voices/<名字>.bin` 一一对应。
     *
     * @return 语音名列表；模型未下载时返回空列表
     */
    suspend fun readKokoroZhVoiceNames(context: Context, modelId: String = "kokoro_v1.1-zh"): List<String> =
        withContext(Dispatchers.IO) {
            ensureInit(context)
            if (cachedVoiceNames != null) return@withContext cachedVoiceNames!!

            // ① 优先用索引里登记的列表。
            //    onnx-community 的 ONNX 导出**没有** speaker_names 元数据，
            //    只有 sherpa 的导出才有，所以不能只依赖元数据。
            val fromManifest = resolve(modelId)?.voices ?: emptyList()
            if (fromManifest.isNotEmpty()) {
                cachedVoiceNames = fromManifest
                Log.i(TAG, "从索引读到 ${fromManifest.size} 个语音名")
                return@withContext fromManifest
            }

            val cache = File(context.filesDir, "$modelId/voices.txt")
            if (cache.exists() && cache.length() > 0) {
                val names = cache.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
                if (names.isNotEmpty()) {
                    cachedVoiceNames = names
                    Log.i(TAG, "从缓存读到 ${names.size} 个语音名")
                    return@withContext names
                }
            }

            // 找模型文件
            val model = resolve(modelId)?.files?.firstOrNull { it.fileName.endsWith(".onnx") }
                ?: return@withContext emptyList()
            val modelFile = File(context.filesDir, model.fileName)
            if (!modelFile.exists()) {
                Log.i(TAG, "模型尚未下载，暂时没有语音列表")
                return@withContext emptyList()
            }

            val names = try {
                val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
                val opts = ai.onnxruntime.OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.NO_OPT)
                }
                env.createSession(modelFile.absolutePath, opts).use { session ->
                    session.metadata.customMetadata["speaker_names"]
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: emptyList()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "读取 ONNX 元数据里的 speaker_names 失败", t)
                emptyList()
            }

            if (names.isNotEmpty()) {
                cachedVoiceNames = names
                runCatching { cache.writeText(names.joinToString("\n"), Charsets.UTF_8) }
                Log.i(TAG, "从 ONNX 元数据读到 ${names.size} 个语音名（已缓存）")
            }
            names
        }

    /** 语音名缓存，避免反复建 ONNX 会话。 */
    @Volatile private var cachedVoiceNames: List<String>? = null

    /**
     * 单个语音文件是否已按需下载到本地。
     */
    fun isVoiceFileDownloaded(context: Context, modelId: String, voiceName: String): Boolean =
        voiceFile(context, modelId, voiceName).exists()

    fun deleteModel(context: Context, modelId: String) {
        ensureInit(context)
        val model = models.find { it.id == modelId } ?: return
        model.files.forEach { fileDef ->
             val file = File(context.filesDir, fileDef.fileName)
             if (file.exists()) file.delete()
        }
    }

    /**
     * 历史版本用过、现已废弃的大文件。
     *
     * `kokoro-v1.1-zh/model.int8.onnx` 是 127 MB 的旧模型，里面含 ConvInteger，
     * ONNX Runtime 建不出会话（中文因此完全读不出来）。换源之后它只是白占空间，
     * 所以升级时顺手删掉。
     */
    private val LEGACY_FILES = listOf("kokoro-v1.1-zh/model.int8.onnx")

    /**
     * 升级时收拾旧版本留下的东西：
     *
     * 1. 删掉带 ConvInteger 的旧 int8 模型（127 MB，ORT 根本建不出会话）；
     * 2. 删掉**大小与索引不符**的文件 —— 同一个路径换过模型内容时（例如
     *    `kokoro-v1.1-zh/model.onnx` 先放过 fp16 的 163 MB、现在要放 fp32 的 339 MB），
     *    老文件必须清掉，否则会被当成"已安装"，用户永远换不到新模型；
     * 3. 把语音文件从 `kokoro_v1.1-zh/voices/`（旧版用模型 id 拼路径，下划线）
     *    搬到索引声明的 `kokoro-v1.1-zh/voices/`（连字符）。
     *    不搬的话用户明明下载过语音，引擎却一个都找不到。
     */
    fun migrateLegacyStorage(context: Context) {
        ensureInit(context)

        // ① 废弃文件
        LEGACY_FILES.forEach { relative ->
            val file = File(context.filesDir, relative)
            // 只有在索引里已经不再引用这个名字时才删，避免误删正在用的文件
            val stillUsed = models.any { m -> m.files.any { it.fileName == relative } }
            if (!stillUsed && file.exists()) {
                val freed = file.length()
                if (file.delete()) {
                    Log.i(TAG, "已清理废弃文件 $relative（释放 ${freed / 1024 / 1024} MB）")
                }
            }
        }

        // ② 内容过期（大小不符）的文件
        models.forEach { model ->
            model.files.filter { it.sizeBytes > 0 }.forEach { fileDef ->
                val file = File(context.filesDir, fileDef.fileName)
                if (file.exists() && file.length() != fileDef.sizeBytes) {
                    Log.w(
                        TAG,
                        "删除过期文件 ${fileDef.fileName}：本地 ${file.length()} 字节，" +
                            "索引要求 ${fileDef.sizeBytes} 字节"
                    )
                    file.delete()
                }
            }
        }

        // ② 搬移错位的语音文件
        val legacyVoices = File(context.filesDir, "kokoro_v1.1-zh/voices")
        val bins = legacyVoices.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
        if (!bins.isNullOrEmpty()) {
            var moved = 0
            bins.forEach { bin ->
                val name = bin.name.removeSuffix(".bin")
                val canonical = voiceFile(context, "kokoro_v1.1-zh", name)
                if (canonical.exists()) {
                    bin.delete()
                } else {
                    canonical.parentFile?.mkdirs()
                    if (bin.renameTo(canonical) || runCatching { bin.copyTo(canonical, true); bin.delete() }
                            .getOrDefault(false)
                    ) {
                        moved++
                    }
                }
            }
            legacyVoices.delete()  // 只在空目录时才会成功
            Log.i(TAG, "已把 $moved 个语音文件搬到索引声明的目录")
        }
    }
}
