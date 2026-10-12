package com.leedor.tts.ui.screens

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.leedor.tts.R
import com.leedor.tts.data.PrefsManager
import com.leedor.tts.ui.components.VoiceCard
import com.leedor.tts.ui.viewmodel.VoicesViewModel
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(
    navController: NavController,
    viewModel: VoicesViewModel = viewModel(),
    pendingVoiceCloneData: Triple<String, String, String>? = null, // (path, name, transcript)
    onVoiceCloneHandled: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PrefsManager(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Test Speech State
    var testText by remember { mutableStateOf("你好，我是侍读郎。") }
    var speechRate by remember { mutableFloatStateOf(1.0f) }
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    var showLanguageModal by remember { mutableStateOf(false) }
    var showRegionModal by remember { mutableStateOf(false) }
    var showGenderModal by remember { mutableStateOf(false) }
    var showQualityModal by remember { mutableStateOf(false) }
    
    // Voice cloning state
    var showCloneOptionsSheet by remember { mutableStateOf(false) }
    var showVoiceNameDialog by remember { mutableStateOf(false) }
    var voiceClonePath by remember { mutableStateOf<String?>(null) }
    var voiceCloneName by remember { mutableStateOf("") }
    var voiceCloneTranscript by remember { mutableStateOf("") } // Kept for API compat but not used
    var isCloning by remember { mutableStateOf(false) }
    
    DisposableEffect(Unit) {
        // Explicitly use our own engine package to ensure we test 侍读郎
        // regardless of the system-wide default setting.
        tts = TextToSpeech(context, { status ->
             if (status == TextToSpeech.SUCCESS) {
                 // Init
             }
        }, "com.leedor.tts")
        onDispose {
            tts?.shutdown()
        }
    }
    
    // Sync ViewModel selection with Prefs
    LaunchedEffect(uiState.selectedVoiceId) {
        uiState.selectedVoiceId?.let { 
             prefs.currentVoice = it
             // Auto-update test text based on language
             testText = viewModel.getSampleTextForVoice(it)
        }
    }
    
    // Load initial selection and voice list from prefs
    LaunchedEffect(Unit) {
        viewModel.loadVoices() // Refresh list (in case model changed)
        viewModel.selectVoice(prefs.currentVoice)
    }
    
    // Handle pending voice clone from VoiceRecorderScreen
    LaunchedEffect(pendingVoiceCloneData) {
        pendingVoiceCloneData?.let { (path, name, transcript) ->
            if (name.isNotEmpty()) {
                // Clone directly with recorded audio and transcript
                viewModel.cloneVoice(path, name, transcript)
            } else {
                voiceClonePath = path
                voiceCloneTranscript = transcript
                showVoiceNameDialog = true
            }
            onVoiceCloneHandled()
        }
    }

    LaunchedEffect(uiState.cloneErrorMessage) {
        val message = uiState.cloneErrorMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearCloneError()
        }
    }
    
    // File picker for audio upload
    val audioPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            // Copy to cache and get path
            val inputStream = context.contentResolver.openInputStream(uri)
            val cacheFile = java.io.File(context.cacheDir, "voice_upload_${System.currentTimeMillis()}.wav")
            inputStream?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            voiceClonePath = cacheFile.absolutePath
            voiceCloneTranscript = "" // Transcript not used - cloning is audio-only
            showVoiceNameDialog = true // Go directly to name dialog (skip transcript)
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(stringResource(R.string.voices_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = stringResource(R.string.voices_available_count, uiState.filteredVoices.size),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            )
        },

        floatingActionButton = {
            // 只要当前引擎支持克隆就显示（ZipVoice / Pocket-TTS）。
            // 判断放在 ModelLanguageSupport 里，避免这里再硬编码模型 id。
            if (com.leedor.tts.engine.ModelLanguageSupport.supportsCloning(prefs.currentModel)) {
                ExtendedFloatingActionButton(
                    onClick = { showCloneOptionsSheet = true },
                    icon = { Icon(Icons.Default.Add, stringResource(R.string.voices_clone_voice)) },
                    text = { Text(stringResource(R.string.voices_clone_voice)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
        },
        bottomBar = {
            // Test Speech Bar
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = testText,
                        onValueChange = { testText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.voices_type_to_speak)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FloatingActionButton(
                        onClick = {
                             val voiceId = uiState.selectedVoiceId ?: prefs.currentVoice
                             val params = android.os.Bundle()
                             params.putString("voiceName", voiceId)
                             
                             // Graceful recovery: stop, and if isSpeaking was true after stop, recreate TTS
                             val wasSpeaking = tts?.isSpeaking == true
                             tts?.stop()
                             
                             // If TTS was stuck or in an error state, recreate it
                             if (wasSpeaking) {
                                 // Give a brief moment for stop to take effect
                                 tts?.shutdown()
                                 tts = TextToSpeech(context, { _ -> }, "com.leedor.tts")
                             }
                             
                             // Set the speech rate from preferences
                             tts?.setSpeechRate(prefs.speechSpeed)
                             
                             tts?.speak(testText, TextToSpeech.QUEUE_FLUSH, params, "test_id")
                        },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(Icons.Default.PlayArrow, stringResource(R.string.voices_speak))
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.voices_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            
            // Filters Row
            Row(
                modifier = Modifier
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Region Filter
                FilterChip(
                    selected = uiState.selectedRegion != null,
                    onClick = { showRegionModal = true },
                    label = { Text(uiState.selectedRegion ?: stringResource(R.string.voices_filter_region)) },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp)) }
                )

                // Language Filter
                FilterChip(
                    selected = uiState.selectedLanguage != null,
                    onClick = { showLanguageModal = true },
                    label = { Text(uiState.selectedLanguage ?: stringResource(R.string.voices_filter_language)) },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp)) }
                )
                
                // Gender Filter
                FilterChip(
                    selected = uiState.selectedGender != null,
                    onClick = { showGenderModal = true },
                    label = { Text(uiState.selectedGender?.let { genderLabel(it) } ?: stringResource(R.string.voices_filter_gender)) },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp)) }
                )
                
                // Quality Filter (Piper only)
                if (uiState.availableQualities.isNotEmpty()) {
                    FilterChip(
                        selected = uiState.selectedQuality != null,
                        onClick = { showQualityModal = true },
                        label = { Text(uiState.selectedQuality?.let { qualityLabel(it) } ?: stringResource(R.string.voices_filter_quality)) },
                        trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp)) }
                    )
                }
                
                // Clear
                if (uiState.selectedRegion != null || uiState.selectedLanguage != null || uiState.selectedGender != null || uiState.selectedQuality != null) {
                    IconButton(onClick = { viewModel.clearFilters() }) {
                        Icon(Icons.Default.Clear, stringResource(R.string.voices_clear_filters))
                    }
                }
            }
            
            // Processing Status Banner - shows when encoding voices
            uiState.processingStatus?.let { status ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            if (uiState.filteredVoices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.voices_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.voices_empty_hint), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { viewModel.clearFilters() },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text(stringResource(R.string.voices_clear_filters))
                        }
                    }
                }
            } else {
                // Voice List
                LazyColumn(
                    contentPadding = PaddingValues(start=16.dp, end=16.dp, bottom=100.dp), // Extra bottom padding for TestBar
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.filteredVoices) { voice ->
                        com.leedor.tts.ui.components.VoiceCard(
                            voice = voice,
                            isSelected = voice.id == uiState.selectedVoiceId,
                            onVoiceSelected = { viewModel.selectVoice(voice.id) },
                            onDownload = { viewModel.downloadVoice(voice) },
                            onDelete = if (voice.isCloned) {{ viewModel.deleteClonedVoice(voice.id) }} else null
                        )
                    }
                }
            }
        }
        
        if (showLanguageModal) {
            ModalBottomSheet(
                onDismissRequest = { showLanguageModal = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        stringResource(R.string.voices_select_language),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        viewModel.selectLanguage(null)
                                        showLanguageModal = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = uiState.selectedLanguage == null,
                                    onClick = { 
                                        viewModel.selectLanguage(null)
                                        showLanguageModal = false
                                    }
                                )
                                Text(stringResource(R.string.voices_all_languages), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        
                        items(uiState.availableLanguages) { lang ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        viewModel.selectLanguage(lang)
                                        showLanguageModal = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = uiState.selectedLanguage == lang,
                                    onClick = { 
                                        viewModel.selectLanguage(lang)
                                        showLanguageModal = false
                                    }
                                )
                                Text(lang, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
        
        if (showRegionModal) {
            ModalBottomSheet(onDismissRequest = { showRegionModal = false }) {
                Column(modifier = Modifier.padding(bottom=32.dp)) {
                    Text(stringResource(R.string.voices_select_region), style=MaterialTheme.typography.titleLarge, modifier=Modifier.padding(16.dp))
                    HorizontalDivider()
                    uiState.availableRegions.forEach { region ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.selectRegion(region); showRegionModal = false }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(region)
                        }
                    }
                    Button(onClick={viewModel.selectRegion(null); showRegionModal=false}, modifier=Modifier.padding(16.dp).fillMaxWidth()) { Text(stringResource(R.string.voices_clear_region)) }
                }
            }
        }
        
        if (showGenderModal) {
             ModalBottomSheet(onDismissRequest = { showGenderModal = false }) {
                Column(modifier = Modifier.padding(bottom=32.dp)) {
                    Text(stringResource(R.string.voices_select_gender), style=MaterialTheme.typography.titleLarge, modifier=Modifier.padding(16.dp))
                    HorizontalDivider()
                    listOf(
                        "Male" to R.string.voices_gender_male,
                        "Female" to R.string.voices_gender_female
                    ).forEach { (gender, genderLabelRes) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.selectGender(gender); showGenderModal = false }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(genderLabelRes))
                        }
                    }
                    Button(onClick={viewModel.selectGender(null); showGenderModal=false}, modifier=Modifier.padding(16.dp).fillMaxWidth()) { Text(stringResource(R.string.voices_clear_gender)) }
                }
            }
        }
        
        if (showQualityModal) {
             ModalBottomSheet(onDismissRequest = { showQualityModal = false }) {
                Column(modifier = Modifier.padding(bottom=32.dp)) {
                    Text(stringResource(R.string.voices_select_quality), style=MaterialTheme.typography.titleLarge, modifier=Modifier.padding(16.dp))
                    HorizontalDivider()
                    uiState.availableQualities.forEach { quality ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.selectQuality(quality); showQualityModal = false }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(qualityLabel(quality))
                        }
                    }
                    Button(onClick={viewModel.selectQuality(null); showQualityModal=false}, modifier=Modifier.padding(16.dp).fillMaxWidth()) { Text(stringResource(R.string.voices_clear_quality)) }
                }
            }
        }
        
        // Voice Cloning Options Sheet
        if (showCloneOptionsSheet) {
            ModalBottomSheet(onDismissRequest = { showCloneOptionsSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        stringResource(R.string.voices_clone_a_voice),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()
                    
                    // Record option
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.voices_record_voice)) },
                        supportingContent = { Text(stringResource(R.string.voices_record_hint)) },
                        leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                        modifier = Modifier.clickable {
                            showCloneOptionsSheet = false
                            navController.navigate(com.leedor.tts.ui.navigation.Screen.VoiceRecorder.route)
                        }
                    )
                    
                    // Upload option
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.voices_upload_audio)) },
                        supportingContent = { Text(stringResource(R.string.voices_upload_hint)) },
                        leadingContent = { Icon(Icons.Default.Create, null) },
                        modifier = Modifier.clickable {
                            showCloneOptionsSheet = false
                            audioPickerLauncher.launch("audio/*")
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
        
        // NOTE: Transcript dialog removed - transcripts don't improve voice cloning
        // Voice embeddings come purely from mimi_encoder(audio), no text involved
        
        // Voice Name Dialog (step 2 for file upload, or direct from recording)
        if (showVoiceNameDialog && voiceClonePath != null) {
            AlertDialog(
                onDismissRequest = { 
                    showVoiceNameDialog = false
                    voiceClonePath = null
                    voiceCloneName = ""
                    voiceCloneTranscript = ""
                },
                title = { Text(stringResource(R.string.voices_name_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.voices_name_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = voiceCloneName,
                            onValueChange = { voiceCloneName = it },
                            label = { Text(stringResource(R.string.voices_name_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.cloneVoice(voiceClonePath!!, voiceCloneName, voiceCloneTranscript)
                            showVoiceNameDialog = false
                            voiceClonePath = null
                            voiceCloneName = ""
                            voiceCloneTranscript = ""
                        },
                        enabled = voiceCloneName.isNotBlank()
                    ) {
                        Text(stringResource(R.string.voices_clone_voice))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { 
                            showVoiceNameDialog = false 
                            voiceClonePath = null
                            voiceCloneName = ""
                            voiceCloneTranscript = ""
                        }
                    ) {
                        Text(stringResource(R.string.voices_cancel))
                    }
                }
            )
        }
    }
}

/**
 * Localized display label for a voice gender filter value.
 * The raw value ("Male" / "Female") is kept for filtering; only the label is translated.
 */
@Composable
private fun genderLabel(value: String): String = when (value.lowercase(Locale.US)) {
    "male" -> stringResource(R.string.voices_gender_male)
    "female" -> stringResource(R.string.voices_gender_female)
    else -> value
}

/**
 * Localized display label for a Piper quality value ("x_low" / "low" / "medium" / "high").
 * The raw value is kept for filtering; only the label is translated.
 */
@Composable
private fun qualityLabel(value: String): String = when (value.lowercase(Locale.US)) {
    "x_low" -> stringResource(R.string.voices_quality_extra_low)
    "low" -> stringResource(R.string.voices_quality_low)
    "medium" -> stringResource(R.string.voices_quality_medium)
    "high" -> stringResource(R.string.voices_quality_high)
    else -> value.replaceFirstChar { it.uppercaseChar() }
}
