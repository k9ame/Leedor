package com.leedor.tts.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leedor.tts.engine.KokoroEngine
import com.leedor.tts.R
import com.leedor.tts.data.PrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.leedor.tts.data.VoiceDefinitions
import com.leedor.tts.engine.pocket.PocketVoiceState
import com.leedor.tts.support.SupportLogStore
import java.io.File

data class Voice(
    val id: String,
    val name: String,
    val language: String,
    val gender: String, // "Male" or "Female"
    val region: String, // "US" or "UK"
    val downloadState: com.leedor.tts.data.DownloadState = com.leedor.tts.data.DownloadState.Downloaded,
    val downloadProgress: Float = 0f,
    val metadata: com.leedor.tts.data.VoiceInfo? = null,
    val isCloned: Boolean = false // True for user-cloned voices that can be deleted
)

enum class ViewMode {
    LIST, GRID
}

enum class VoiceSortOption(val displayName: String) {
    NAME("Name"),
    LANGUAGE("Language")
}

class VoicesViewModel(application: Application) : AndroidViewModel(application) {
    
    data class UiState(
        val voices: List<Voice> = emptyList(),
        val filteredVoices: List<Voice> = emptyList(),
        val isLoading: Boolean = true,
        val searchQuery: String = "",
        val showFilters: Boolean = false,
        val viewMode: ViewMode = ViewMode.LIST,
        val selectedVoiceId: String? = "af_heart", // Default
        
        // Filters
        val selectedLanguage: String? = null,
        val selectedGender: String? = null,
        val selectedRegion: String? = null,
        val selectedQuality: String? = null, // For Piper: x_low, low, medium, high
        val sortBy: VoiceSortOption = VoiceSortOption.NAME,
        
        val availableLanguages: List<String> = emptyList(),
        val availableRegions: List<String> = emptyList(),
        val availableQualities: List<String> = emptyList(),
        
        // Processing status for voice encoding feedback
        val processingStatus: String? = null, // e.g., "Encoding celebrity voice: Oprah Winfrey..."
        val cloneErrorMessage: String? = null
    )
    
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    private val context: Context get() = getApplication<Application>().applicationContext
    private val voiceRepo by lazy { com.leedor.tts.data.VoiceRepository(context) }
    private val voiceDownloader by lazy { com.leedor.tts.data.VoiceDownloader(context) }
    
    // Kokoro v1.0 的 28 个英文语音表已随 v1.0 一起下架：
    // 它曾是这个 ViewModel 的兜底语音表，结果成了「一进语音库模型就变回 v1.0」的帮凶。
    // 现在语音表完全由 loadVoices() 按当前模型决定，认不出来的模型就显示空列表。

    private val kittenVoices = listOf(
        Voice("expr-voice-2-f", "Kitten F2", "en-us", "Female", "US"),
        Voice("expr-voice-2-m", "Kitten M2", "en-us", "Male", "US"),
        Voice("expr-voice-3-f", "Kitten F3", "en-us", "Female", "US"),
        Voice("expr-voice-3-m", "Kitten M3", "en-us", "Male", "US"),
        Voice("expr-voice-4-f", "Kitten F4", "en-us", "Female", "US"),
        Voice("expr-voice-4-m", "Kitten M4", "en-us", "Male", "US"),
        Voice("expr-voice-5-f", "Kitten F5", "en-us", "Female", "US"),
        Voice("expr-voice-5-m", "Kitten M5", "en-us", "Male", "US"),
    )

    // Dynamic source.
    // 初始为空：语音表一律由 loadVoices() 按当前模型填。
    // 这里以前放着 Kokoro v1.0 的 28 个英文语音当默认值，
    // 一旦落到这个默认值上就会把模型带回 v1.0（见 loadVoices / selectVoice 的注释）。
    private var allVoices: List<Voice> = emptyList()
    private var lastLoadedModel: String? = null
    
    init {
        loadVoices()
        startPollLoop()
        observePrefsChanges()
        observeEncodingStatus()
    }
    
    private fun observePrefsChanges() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val prefs = PrefsManager(context)
                val currentModel = prefs.currentModel
                if (lastLoadedModel != null && lastLoadedModel != currentModel) {
                    // Model changed externally (e.g., from Settings), reload voices
                    loadVoices()
                }
            }
        }
    }
    
    // Collecting status from PocketVoiceRepository (singleton)
    private fun observeEncodingStatus() {
        viewModelScope.launch {
            com.leedor.tts.data.PocketVoiceRepository.encodingStatus.collect { status ->
                _uiState.update { it.copy(processingStatus = status) }
            }
        }
    }
    
    private fun startPollLoop() {
        viewModelScope.launch {
            while(true) {
                kotlinx.coroutines.delay(1000)
                // Refresh status only if Piper mode
                val prefs = PrefsManager(context)
                if (prefs.currentModel.startsWith("piper")) {
                    refreshPiperStatuses()
                }
            }
        }
    }
    
    private fun refreshPiperStatuses() {
        // Check only downloading or not-downloaded items? Or all?
        // Checking 500+ items is slow.
        // But the user wants to see "Download started", "Level", "Failed".
        // VoiceDownloader only tracks Active/Recent downloads via prefs.
        // Repo checks file existence.
        
        val updatedVoices = allVoices.map { voice ->
            if (voice.id.startsWith("piper") || voice.metadata != null) {
                // It's a piper voice
                val status = voiceDownloader.getDownloadStatus(voice.id)
                if (status.state != com.leedor.tts.data.DownloadState.NotDownloaded) {
                    // Active or stored download info
                    voice.copy(downloadState = status.state, downloadProgress = status.progress)
                } else {
                    // Fallback to Repo check (Disk existence)
                    val diskState = voiceRepo.getDownloadState(voice.id)
                    voice.copy(downloadState = diskState, downloadProgress = if (diskState == com.leedor.tts.data.DownloadState.Downloaded) 1f else 0f)
                }
            } else {
                voice
            }
        }
        
        if (updatedVoices != allVoices) {
            allVoices = updatedVoices
            applyFilters()
        }
    }
    
    fun downloadVoice(voice: Voice) {
        // Handle celebrity voices
        if (VoiceDefinitions.requiresDownload(voice.id)) {
            downloadCelebrityVoice(voice.id)
            return
        }
        
        // Kokoro v1.1-zh：按需下载单个语音文件（0.5 MB），
        // 而不是整包 51 MB。metadata 为空，所以必须在这之前处理。
        val model = com.leedor.tts.data.ModelRepository.resolve(PrefsManager(context).currentModel)
        if (model?.voiceUrlTemplate?.isNotEmpty() == true) {
            viewModelScope.launch {
                _uiState.update { st ->
                    st.copy(voices = st.voices.map {
                        if (it.id == voice.id) it.copy(downloadState = com.leedor.tts.data.DownloadState.Downloading) else it
                    })
                }
                val ok = com.leedor.tts.data.ModelRepository.downloadVoiceFile(
                    context, model.id, voice.id
                ) { p ->
                    _uiState.update { st ->
                        st.copy(voices = st.voices.map {
                            if (it.id == voice.id) it.copy(downloadProgress = p) else it
                        })
                    }
                }
                android.util.Log.i("VoicesViewModel", "按需语音 ${voice.id} 下载${if (ok) "成功" else "失败"}")
                loadVoices()
            }
            return
        }

        // Handle Piper voices
        val meta = voice.metadata ?: return
        viewModelScope.launch {
            voiceDownloader.downloadVoice(voice.id, meta.onnxUrl, meta.jsonUrl)
            refreshPiperStatuses() // Optimistic update
        }
    }
    
    fun loadVoices() {
        val prefs = PrefsManager(context)
        val currentModel = prefs.currentModel
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            allVoices = when {
                currentModel == "kitten_nano" -> kittenVoices
                currentModel.startsWith("piper") -> {
                    // Load ALL piper voices from repo
                    voiceRepo.availableVoices.map { info ->
                        // Parse language name to separate language from country
                        // Format: "English (United States)" -> language="English", region="United States"
                        val languageName = info.languageName
                        val pureLang = if (languageName.contains("(")) {
                            languageName.substringBefore("(").trim()
                        } else {
                            languageName
                        }
                        
                        val countryPart = if (languageName.contains("(")) {
                            languageName.substringAfter("(").substringBefore(")").trim()
                        } else {
                            info.region // Fallback to region code
                        }
                        
                        Voice(
                            id = info.id, // e.g. en_US-amy-low
                            name = "${info.name} (${info.quality})", // e.g. Amy (low)
                            language = pureLang, // e.g. "English"
                            gender = "Unknown", // Metadata doesn't strictly have gender.
                            region = countryPart, // e.g. "United States"
                            metadata = info,
                            downloadState = com.leedor.tts.data.DownloadState.NotDownloaded // Initial, will refresh
                        )
                    }
                }
                currentModel == "pocket_v1" -> {
                    val standardVoices = VoiceDefinitions.POCKET_VOICES.map { def ->
                        Voice(
                            id = def.id,
                            name = def.name,
                            language = if (def.region.contains("French")) "fr-fr" else "en-us", // Simple inference
                            gender = def.gender,
                            region = def.region,
                            downloadState = com.leedor.tts.data.DownloadState.Downloaded // Bundled usually
                        )
                    }
                    
                    // Celebrity voices from HuggingFace (download on-demand)
                    val celebrityVoices = VoiceDefinitions.CELEBRITY_VOICES.map { def ->
                        val isDownloaded = com.leedor.tts.data.PocketVoiceRepository.isDownloaded(context, def.id)
                        Voice(
                            id = def.id,
                            name = def.name,
                            language = "en-us",
                            gender = def.gender,
                            region = def.region,
                            downloadState = if (isDownloaded) 
                                com.leedor.tts.data.DownloadState.Downloaded 
                            else 
                                com.leedor.tts.data.DownloadState.NotDownloaded
                        )
                    }
                    
                    // Cloned voices from local storage
                    val clonedVoices = mutableListOf<Voice>()
                    val clonedDir = File(context.filesDir, "pocket/cloned_voices")
                    if (clonedDir.exists()) {
                        clonedDir.listFiles()?.filter { it.extension == "bin" }?.forEach { file ->
                            try {
                                val state = PocketVoiceState.fromBytes(file.readBytes())
                                clonedVoices.add(
                                    Voice(
                                        id = state.id,
                                        name = context.getString(R.string.vm_cloned_name, state.displayName),
                                        language = "en-us", // Default
                                        gender = "Cloned",
                                        region = "Custom",
                                        downloadState = com.leedor.tts.data.DownloadState.Downloaded,
                                        isCloned = true // Mark as deletable
                                    )
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    
                    standardVoices + celebrityVoices + clonedVoices
                }
                currentModel == "kokoro_v1.1-zh" -> {
                    // 103 个语音来自索引（assets/models.json 的 voices 字段），
                    // 不是 VoiceDefinitions 里那几个写死的英文语音。
                    // 之所以不用 ONNX 元数据：onnx-community 的导出没有 speaker_names。
                    val names = com.leedor.tts.data.ModelRepository
                        .readKokoroZhVoiceNames(context, currentModel)
                    names.map { name ->
                        Voice(
                            id = name,
                            name = name,
                            language = if (name.startsWith("z")) "Chinese" else "English",
                            gender = if (name.contains("f_")) "Female" else "Male",
                            region = if (name.startsWith("z")) "CN" else "US",
                            downloadState = if (com.leedor.tts.data.ModelRepository
                                    .isVoiceFileDownloaded(context, currentModel, name)
                            ) com.leedor.tts.data.DownloadState.Downloaded
                            else com.leedor.tts.data.DownloadState.NotDownloaded
                        )
                    }
                }
                currentModel == "zipvoice_zh_en" -> {
                    // ZipVoice 的「语音」是参考音频：索引里登记的 3 个自带音色
                    // （leijun-1 / news-female / news-female-2，来自上游 test_wavs/）
                    // ＋ 用户自己录/传的克隆音色。
                    val repo = com.leedor.tts.data.ModelRepository
                    val presets = repo.resolve(currentModel)?.voices ?: emptyList()
                    val presetVoices = presets.map { name ->
                        Voice(
                            id = name,
                            name = zipVoicePresetLabel(name, context),
                            language = "Chinese",
                            gender = "Unknown",
                            region = "CN",
                            downloadState = if (repo.isVoiceFileDownloaded(context, currentModel, name))
                                com.leedor.tts.data.DownloadState.Downloaded
                            else com.leedor.tts.data.DownloadState.NotDownloaded
                        )
                    }

                    // 用户克隆的音色：与自带音色放在同一个目录，同一种文件。
                    val cloned = mutableListOf<Voice>()
                    val voiceDir = repo.voiceDir(context, currentModel)
                    voiceDir.listFiles { f -> f.isFile && f.extension == "wav" }?.forEach { f ->
                        val id = f.nameWithoutExtension
                        if (id in presets) return@forEach
                        cloned.add(
                            Voice(
                                id = id,
                                name = context.getString(R.string.vm_cloned_name, id),
                                language = "Chinese",
                                gender = "Cloned",
                                region = "Custom",
                                downloadState = com.leedor.tts.data.DownloadState.Downloaded,
                                isCloned = true // 可删除
                            )
                        )
                    }
                    presetVoices + cloned.sortedBy { it.id }
                }
                // 认不出来的模型不再回退到 Kokoro v1.0 的英文语音表：
                // 显示一堆用不了的语音，比显示空列表更让人困惑。
                else -> emptyList()
            }
            
            // Initial status refresh
            if (currentModel.startsWith("piper")) {
                refreshPiperStatuses()
            }
            
            // Auto-fix filters if they are invalid for the current model
            val currentRegion = _uiState.value.selectedRegion
            if (currentRegion == "UK" && allVoices.none { it.region == "UK" }) {
                 _uiState.update { it.copy(selectedRegion = null) }
            }
            
            // Validate selected voice
            val currentVoiceId = prefs.currentVoice
            val isValid = allVoices.any { it.id == currentVoiceId }
            
            val voiceToSelect = if (isValid) {
                currentVoiceId
            } else {
                // Determine sensible default
                val defaultId = when {
                    currentModel == "kitten_nano" -> "expr-voice-2-f"
                    currentModel.startsWith("piper") -> {
                        // Default to amy-low if available, else first bundled
                        "en_US-amy-low"
                    }
                    // 其它模型取该模型**自己的**第一个语音。
                    // 中文模型优先取中文语音：v1.1-zh 的音色表里前三个是英文音色
                    // （af_maple / af_sol / bf_vale），直接取第一个会默认成英文声音，
                    // 而且这几个 af_/bf_ 名字以前还会把模型带偏回 v1.0。
                    allVoices.isNotEmpty() -> (
                        allVoices.firstOrNull {
                            com.leedor.tts.engine.ModelLanguageSupport.voiceIsChinese(it.id)
                        } ?: allVoices.first()
                        ).id
                    else -> com.leedor.tts.data.PrefsManager.DEFAULT_VOICE
                }
                // Update persistent storage
                prefs.currentVoice = defaultId
                defaultId
            }
            
            _uiState.update { 
                it.copy(
                    isLoading = false, 
                    voices = emptyList(), // Use filtered list primarily
                    // filteredVoices will be updated by applyFilters
                    availableLanguages = allVoices.map { v -> v.language }.distinct().sorted(),
                    availableRegions = allVoices.map { v -> v.region }.distinct().sorted(),
                    // Extract quality from Piper voice names (e.g., "Amy (low)" -> "low")
                    availableQualities = allVoices
                        .mapNotNull { v -> 
                            val match = Regex("\\(([^)]+)\\)").find(v.name)
                            match?.groupValues?.getOrNull(1)
                        }
                        .distinct()
                        .sortedBy { 
                            when(it) { "x_low" -> 0; "low" -> 1; "medium" -> 2; "high" -> 3; else -> 4 }
                        },
                    selectedVoiceId = voiceToSelect
                ) 
            }
            applyFilters()
            lastLoadedModel = currentModel
        }
    }
    
    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFilters()
    }
    
    fun toggleFilters() {
        _uiState.update { it.copy(showFilters = !it.showFilters) }
    }
    
    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }
    
    fun selectVoice(voiceId: String) {
        val voice = allVoices.find { it.id == voiceId }
        
        // Check if this is a celebrity voice that needs download
        if (VoiceDefinitions.requiresDownload(voiceId)) {
            if (!com.leedor.tts.data.PocketVoiceRepository.isDownloaded(context, voiceId)) {
                // Trigger download for celebrity voice
                downloadCelebrityVoice(voiceId)
                return
            }
        }
        
        // Only select if downloaded
        if (voice != null && voice.downloadState == com.leedor.tts.data.DownloadState.Downloaded) {
            _uiState.update { it.copy(selectedVoiceId = voiceId) }
            val prefs = PrefsManager(context)
            prefs.currentVoice = voiceId

            val newModel = modelForSelectedVoice(
                currentModel = prefs.currentModel,
                currentEngine = com.leedor.tts.data.ModelRepository.engineOf(prefs.currentModel),
                voiceId = voiceId
            )
            android.util.Log.i("VoicesViewModel", "selectVoice($voiceId) -> model=$newModel")
            prefs.currentModel = newModel
        }
    }
    
    /**
     * Download a celebrity voice from HuggingFace.
     */
    fun downloadCelebrityVoice(voiceId: String) {
        viewModelScope.launch {
            _uiState.update { 
                val updated = allVoices.map { v ->
                    if (v.id == voiceId) v.copy(downloadState = com.leedor.tts.data.DownloadState.Downloading)
                    else v
                }
                it.copy(voices = updated)
            }
            
            com.leedor.tts.data.PocketVoiceRepository.downloadVoice(context, voiceId) { success ->
                viewModelScope.launch {
                    if (success) {
                        // Reload voices to pick up the new download
                        loadVoices()
                    } else {
                        // Reset download state on failure
                        _uiState.update { 
                            val updated = allVoices.map { v ->
                                if (v.id == voiceId) v.copy(downloadState = com.leedor.tts.data.DownloadState.NotDownloaded)
                                else v
                            }
                            it.copy(voices = updated)
                        }
                    }
                }
            }
        }
    }
    
    fun selectLanguage(language: String?) {
        _uiState.update { it.copy(selectedLanguage = language) }
        applyFilters()
    }
    
    fun selectGender(gender: String?) {
        _uiState.update { it.copy(selectedGender = gender) }
        applyFilters()
    }
    
    fun selectRegion(region: String?) {
        _uiState.update { it.copy(selectedRegion = region) }
        applyFilters()
    }
    
    fun clearFilters() {
        _uiState.update { 
            it.copy(
                searchQuery = "",
                selectedLanguage = null,
                selectedGender = null,
                selectedRegion = null,
                selectedQuality = null
            )
        }
        applyFilters()
    }
    
    /**
     * Set processing status message to show in the UI.
     * Pass null to clear the status.
     */
    fun setProcessingStatus(status: String?) {
        _uiState.update { it.copy(processingStatus = status) }
    }
    
    fun selectQuality(quality: String?) {
        _uiState.update { it.copy(selectedQuality = quality) }
        applyFilters()
    }
    
    private fun applyFilters() {
        val currentState = _uiState.value
        val query = currentState.searchQuery.lowercase()
        
        val filtered = allVoices.filter { voice ->
            val matchesSearch = voice.name.lowercase().contains(query) || 
                              voice.language.contains(query)
            
            val matchesLang = currentState.selectedLanguage?.let { it == voice.language } ?: true
            val matchesGender = currentState.selectedGender?.let { it == voice.gender } ?: true
            val matchesRegion = currentState.selectedRegion?.let { it == voice.region } ?: true
            
            // Quality filter for Piper voices
            val matchesQuality = currentState.selectedQuality?.let { quality ->
                voice.name.contains("($quality)", ignoreCase = true)
            } ?: true
            
            matchesSearch && matchesLang && matchesGender && matchesRegion && matchesQuality
        }
        
        val sorted = when(currentState.sortBy) {
            VoiceSortOption.NAME -> filtered.sortedBy { it.name }
            VoiceSortOption.LANGUAGE -> filtered.sortedBy { it.language }
        }
        
        _uiState.update { it.copy(filteredVoices = sorted) }
    }
    /**
     * 试听用的示例句。
     *
     * 注意：这里**不能**按界面语言本地化。示例句要用**被选中语音自己的语言**，
     * 否则会让英文语音去念中文字符、或让西班牙语语音念中文，试听结果毫无意义。
     * 所以非中文语音统一使用拉丁字母的品牌名 "Leedor"。
     */
    fun getSampleTextForVoice(voiceId: String?): String {
        val voice = allVoices.find { it.id == voiceId } ?: return SAMPLE_DEFAULT
        val lang = voice.language.lowercase() // e.g. "English (United States)" or "en_US"

        return when {
            lang.contains("tamil") || lang.contains("ta_in") -> "வணக்கம், நான் Leedor."
            lang.contains("spanish") || lang.contains("es_") -> "Hola, soy Leedor."
            lang.contains("french") || lang.contains("fr_") -> "Bonjour, je suis Leedor."
            lang.contains("german") || lang.contains("de_") -> "Hallo, ich bin Leedor."
            lang.contains("japanese") || lang.contains("ja_") -> "こんにちは、Leedorです。"
            lang.contains("chinese") || lang.contains("zh_") -> "你好，我是侍读郎。"
            else -> SAMPLE_DEFAULT
        }
    }

    private companion object {
        /** 英文语音（以及无法判断语言的语音）的示例句，用拉丁字母品牌名。 */
        const val SAMPLE_DEFAULT = "Hello, I am Leedor."
    }

    fun cloneVoice(path: String, name: String, transcript: String = "") {
        android.util.Log.i("VoicesViewModel", "cloneVoice called: path=$path, name=$name")
        SupportLogStore.log(context, "VoicesViewModel", "Clone requested for voice='$name'")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    processingStatus = context.getString(R.string.vm_clone_processing, name),
                    cloneErrorMessage = null
                )
            }
            try {
                // Check if file exists
                val sourceFile = java.io.File(path)
                if (!sourceFile.exists()) {
                    android.util.Log.e("VoicesViewModel", "Audio file does not exist: $path")
                    SupportLogStore.log(context, "VoicesViewModel", "Clone failed: source audio file missing")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            processingStatus = null,
                            cloneErrorMessage = context.getString(R.string.vm_clone_err_audio_missing)
                        )
                    }
                    return@launch
                }
                android.util.Log.i("VoicesViewModel", "Audio file exists: ${sourceFile.length()} bytes")
                
                // CRITICAL: Copy file to permanent location BEFORE engine init
                // The engine init takes ~10s, during which cache files may be deleted
                val voicesDir = java.io.File(context.filesDir, "pocket/voice_clone_input")
                voicesDir.mkdirs()
                val permanentFile = java.io.File(voicesDir, "input_${System.currentTimeMillis()}.wav")
                sourceFile.copyTo(permanentFile, overwrite = true)
                android.util.Log.i("VoicesViewModel", "Copied to permanent location: ${permanentFile.absolutePath}")
                
                // Update status: Initializing engine
                _uiState.update { it.copy(processingStatus = context.getString(R.string.vm_clone_encoding, name)) }
                
                // Initialize a lightweight clone-only engine to avoid OOM/native crashes
                // 用当前模型对应的引擎来克隆，而不是硬编码 Pocket-TTS ——
                // 现在支持克隆的引擎可能是 ZipVoice（中英零样本克隆）。
                val engine = com.leedor.tts.engine.EngineFactory.createEngine(
                    context, com.leedor.tts.data.PrefsManager(context).currentModel
                )
                android.util.Log.i("VoicesViewModel", "Initializing ${engine::class.java.simpleName} for cloning...")
                try {
                    if (engine.initialize()) {
                        android.util.Log.i("VoicesViewModel", "Engine initialized, calling cloneVoice...")
                        SupportLogStore.log(context, "VoicesViewModel", "Clone engine initialized (clone-only)")
                        // ZipVoice 这类零样本克隆模型需要参考文本才能建立音色对应关系；
                        // Pocket-TTS 用不到，会忽略这个参数。
                        val voiceId = engine.cloneVoice(permanentFile.absolutePath, name, transcript)
                        if (voiceId != null) {
                            android.util.Log.i("VoicesViewModel", "Voice cloned successfully: $voiceId")
                            SupportLogStore.log(context, "VoicesViewModel", "Clone succeeded: voiceId='$voiceId'")
                            _uiState.update { it.copy(cloneErrorMessage = null) }
                        } else {
                            android.util.Log.e("VoicesViewModel", "cloneVoice returned null")
                            SupportLogStore.log(context, "VoicesViewModel", "Clone failed: cloneVoice returned null")
                            _uiState.update {
                                it.copy(cloneErrorMessage = context.getString(R.string.vm_clone_err_retry))
                            }
                        }

                        loadVoices() // Refresh list to show new voice
                    } else {
                        android.util.Log.e("VoicesViewModel", "Failed to initialize engine for cloning")
                        SupportLogStore.log(context, "VoicesViewModel", "Clone failed: engine initialization failed")
                        _uiState.update {
                            it.copy(cloneErrorMessage = context.getString(R.string.vm_clone_err_engine_init))
                        }
                    }
                } finally {
                    engine.release()
                    // Clean up temporary input file
                    permanentFile.delete()
                }
            } catch (e: Exception) {
                android.util.Log.e("VoicesViewModel", "Error in cloneVoice", e)
                e.printStackTrace()
                SupportLogStore.log(context, "VoicesViewModel", "Clone failed with exception", e)
                _uiState.update {
                    it.copy(cloneErrorMessage = context.getString(R.string.vm_clone_err_generic))
                }
            }
            _uiState.update { it.copy(isLoading = false, processingStatus = null) }
        }
    }

    fun clearCloneError() {
        _uiState.update { it.copy(cloneErrorMessage = null) }
    }
    
    /**
     * Delete a cloned voice.
     */
    fun deleteClonedVoice(voiceId: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val modelId = PrefsManager(context).currentModel
                // ZipVoice 的克隆音色是 zipvoice/cloned/<名字>.wav（+ 同名 .txt），
                // 与 Pocket 的 pocket/cloned_voices/<名字>.bin 不是一个地方，
                // 所以按当前模型分派 —— 以前一律按 Pocket 的路径删，等于静默失败。
                if (com.leedor.tts.data.ModelRepository.engineOf(modelId) == "zipvoice") {
                    com.leedor.tts.data.ModelRepository.deleteVoiceFile(context, modelId, voiceId)
                    android.util.Log.i("VoicesViewModel", "Deleted ZipVoice cloned voice: $voiceId")
                } else {
                    val voiceFile = File(context.filesDir, "pocket/cloned_voices/$voiceId.bin")
                    if (voiceFile.exists()) {
                        voiceFile.delete()
                        android.util.Log.i("VoicesViewModel", "Deleted cloned voice: $voiceId")
                    }
                    // Also delete cached embedding if exists
                    val cacheFile = File(context.cacheDir, "pocket/$voiceId.emb")
                    if (cacheFile.exists()) {
                        cacheFile.delete()
                    }
                }

                // Refresh the voice list
                loadVoices()
            } catch (e: Exception) {
                android.util.Log.e("VoicesViewModel", "Error deleting cloned voice", e)
            }
        }
    }
}

/**
 * ZipVoice 自带音色的字符串资源；返回 0 表示没有专门文案（退回显示 id）。
 * 单独抽出来是为了能写单元测试：**每加一个自带音色都必须配显示名**。
 */
@androidx.annotation.StringRes
internal fun zipVoicePresetNameRes(id: String): Int = when (id) {
    "leijun-1" -> R.string.voice_zipvoice_leijun
    "news-female" -> R.string.voice_zipvoice_news_female
    "news-female-2" -> R.string.voice_zipvoice_news_female_2
    else -> 0
}

/**
 * ZipVoice 自带音色的显示名。
 *
 * 索引里的 id 是上游的文件名（`leijun-1` / `news-female`…），直接显示很费解，
 * 所以映射到本地化文案；映射不到就退回 id（用户自己克隆的音色也走这条）。
 */
internal fun zipVoicePresetLabel(id: String, context: Context): String {
    val res = zipVoicePresetNameRes(id)
    return if (res != 0) context.getString(res) else id
}

/**
 * 「在语音库里点了某个语音之后，当前模型应该是什么」。
 *
 * 判断依据是**当前引擎**，不是语音名的前缀。
 *
 * 这条规则是被一个真 bug 逼出来的：语音库的语音表永远属于**当前模型**，
 * 所以点语音基本不该换模型。唯一例外是 Piper —— 它的模型 id 里就带着语音名
 * （`piper_<语音名>`），换语音必须换 id。
 *
 * 以前这里按语音名前缀猜模型，于是 Kokoro v1.1-zh 自带的三个英文音色
 * （`af_maple` / `af_sol` / `bf_vale`）被 `af_`/`bf_` 前缀规则判成了
 * `kokoro_v1.0`；而进语音库时默认语音又是取列表第一个（正好是 af_maple），
 * 结果就是「选了 Kokoro 1.1，一进语音库又变回 1.0」。
 *
 * 抽成纯函数是为了能写单元测试 —— 这类静默改模型的 bug 很难靠肉眼发现。
 */
internal fun modelForSelectedVoice(
    currentModel: String,
    currentEngine: String,
    voiceId: String
): String = when (currentEngine) {
    // Piper：模型 id 就是 piper_<语音名>
    "piper" -> if (voiceId.startsWith("piper_")) voiceId else "piper_$voiceId"
    // 多语音模型：语音是模型内部的音色，点语音不该动模型
    "kitten" -> "kitten_nano"
    "pocket" -> "pocket_v1"
    // kokoro_multilang / kokoro / zipvoice ←→ 语音与模型解耦，保持当前模型
    else -> currentModel
}
