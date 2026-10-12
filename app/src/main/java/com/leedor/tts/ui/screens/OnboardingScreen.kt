package com.leedor.tts.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.leedor.tts.R
import com.leedor.tts.data.PrefsManager
import com.leedor.tts.ui.navigation.Screen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager(context) }
    
    // 默认选中索引里第一个（可见的）模型，不再写死 Kokoro v1.0 —— 它已下架。
    var selectedModel by remember {
        mutableStateOf(
            com.leedor.tts.data.ModelRepository.visibleModels.firstOrNull()?.id ?: "kokoro_v1.1-zh"
        )
    }
    var selectedVoice by remember { mutableStateOf("af_heart") }
    
    // Model Installation State
    var isModelInstalled by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    
    // Check installation status when model changes
    LaunchedEffect(selectedModel) {
        // Piper is bundled/managed differently usually, but we check repository if defined
        if (selectedModel.startsWith("piper")) {
            isModelInstalled = true // Assuming bundled or managed by PiperEngine for starters
        } else {
            isModelInstalled = com.leedor.tts.data.ModelRepository.isInstalled(context, selectedModel)
        }
        
        // Reset voices logic
        val voices = onboardingPresetVoices(selectedModel)

        val validIds = voices.map { it.id }
        if (selectedVoice !in validIds) {
            selectedVoice = validIds.firstOrNull() ?: ""
        }
    }
    
    // Observe download progress
    DisposableEffect(selectedModel) {
        val flow = com.leedor.tts.data.ModelRepository.getDownloadProgress(selectedModel)
        if (flow != null) {
            isDownloading = true
            // In a real app we would collect the flow here to update progress
        }
        onDispose { }
    }

    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    
    // Monitor download progress flow if active
    LaunchedEffect(selectedModel, isDownloading) {
        if (isDownloading) {
             val flow = com.leedor.tts.data.ModelRepository.getDownloadProgress(selectedModel)
             flow?.collect { 
                 downloadProgress = it
                 if (it >= 1.0f) {
                     isDownloading = false
                     isModelInstalled = true
                 }
             }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF1A1B2E), Color(0xFF10111C))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            
            // Header (Static)
            Text(
                text = stringResource(R.string.onboarding_header_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Pager Content
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                userScrollEnabled = false // Force navigation via buttons for flow control
            ) { page ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    when (page) {
                        0 -> {
                            // Step 1: Model Selection
                            Text(
                                text = stringResource(R.string.onboarding_step_model),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            )
                            
                            // 模型列表来自 assets/models.json（唯一来源），
                            // 不再逐个硬编码 —— 否则新增模型（Kokoro v1.1-zh / ZipVoice）
                            // 不会出现在引导页里。
                            com.leedor.tts.data.ModelRepository.visibleModels.forEachIndexed { index, model ->
                                if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                                val installed = com.leedor.tts.data.ModelRepository.isInstalled(context, model.id)
                                ModelSelectionCard(
                                    modelId = model.id,
                                    title = stringResource(com.leedor.tts.ui.ModelDisplay.nameRes(model.id)),
                                    description = stringResource(com.leedor.tts.ui.ModelDisplay.descriptionRes(model.id)),
                                    warning = if (!installed) {
                                        stringResource(R.string.onboarding_warning_download_required)
                                    } else {
                                        null
                                    },
                                    isSelected = model.matches(selectedModel),
                                    onClick = { selectedModel = model.id }
                                )
                            }
                        }
                        1 -> {
                            // Step 2: Voice Selection
                            Text(
                                text = stringResource(R.string.onboarding_step_voice),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            )
                            
                            val voices = onboardingPresetVoices(selectedModel)

                            if (voices.isEmpty()) {
                                // Kokoro v1.1-zh 的语音要等模型下载后从 ONNX 元数据读；
                                // ZipVoice 是克隆模型，本来就没有预设语音。
                                val model = com.leedor.tts.data.ModelRepository.resolve(selectedModel)
                                val hintRes = if (model?.engine == "zipvoice") {
                                    R.string.onboarding_voice_clone_required
                                } else {
                                    R.string.onboarding_voice_after_download
                                }
                                Text(
                                    stringResource(hintRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.75f)
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    voices.forEach { voice ->
                                        VoiceChip(
                                            name = "${voice.name} (${voice.gender})",
                                            isSelected = selectedVoice == voice.id,
                                            onClick = { selectedVoice = voice.id },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                            
                            if (selectedModel == "pocket_v1") {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.onboarding_note_pocket_voices),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        2 -> {
                            // Step 3: System Setup + Theme
                            Text(
                                text = stringResource(R.string.onboarding_step_customize),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            )
                            
                            // Theme Selection - Book-Story inspired mini preview cards
                            var appTheme by remember { mutableStateOf(prefs.appTheme) }
                            var darkMode by remember { mutableStateOf(prefs.darkMode) }
                            
                            // Determine if dark mode preview should be shown
                            val isDarkPreview = when (darkMode) {
                                "FOLLOW_SYSTEM" -> androidx.compose.foundation.isSystemInDarkTheme()
                                "LIGHT" -> false
                                else -> true
                            }
                            val isPureDark = darkMode == "PURE_DARK"
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White.copy(alpha = 0.05f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 12.dp)
                                ) {
                                    // Theme color picker with mini preview cards
                                    com.leedor.tts.ui.theme.ThemePicker(
                                        selectedTheme = appTheme,
                                        isDarkMode = isDarkPreview,
                                        isPureDark = isPureDark,
                                        onThemeSelected = { theme ->
                                            appTheme = theme
                                            prefs.appTheme = theme
                                        }
                                    )
                                    
                                    Spacer(modifier = Modifier.height(16.dp))
                                    
                                    // Dark mode picker
                                    com.leedor.tts.ui.theme.DarkModePicker(
                                        selectedMode = darkMode,
                                        onModeSelected = { mode ->
                                            darkMode = mode
                                            prefs.darkMode = mode
                                        }
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // TTS Setup
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White.copy(alpha = 0.05f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.onboarding_tts_section_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.onboarding_tts_section_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                val intent = android.content.Intent("com.android.settings.TTS_SETTINGS")
                                                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                // Fallback
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(40.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(stringResource(R.string.onboarding_open_tts_settings), color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Pager Indicators
            Row(
                Modifier
                    .wrapContentHeight()
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(pagerState.pageCount) { iteration ->
                    val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.3f)
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(color)
                            .size(8.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // Navigation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pagerState.currentPage > 0) {
                     TextButton(
                        onClick = { 
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        },
                        enabled = !isDownloading
                    ) {
                        Text(stringResource(R.string.onboarding_button_back), color = Color.White.copy(alpha = 0.7f))
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }

                if (pagerState.currentPage < 2) {
                    // Logic for Next/Download
                    if (!isModelInstalled && pagerState.currentPage == 0) {
                        if (isDownloading) {
                             Row(verticalAlignment = Alignment.CenterVertically) {
                                 CircularProgressIndicator(
                                     progress = { downloadProgress },
                                     modifier = Modifier.size(24.dp),
                                     color = MaterialTheme.colorScheme.primary,
                                 )
                                 Spacer(Modifier.width(8.dp))
                                 Text("${(downloadProgress * 100).toInt()}%", color = Color.White)
                             }
                        } else {
                            Button(
                                onClick = { 
                                    isDownloading = true
                                    scope.launch {
                                        com.leedor.tts.data.ModelRepository.downloadModel(context, selectedModel) { success ->
                                            if (success) {
                                                isModelInstalled = true
                                                isDownloading = false
                                                scope.launch { pagerState.animateScrollToPage(1) }
                                            } else {
                                                isDownloading = false
                                                // Handle error (show toast/snackbar)
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.onboarding_button_download_continue))
                            }
                        }
                    } else {
                        Button(
                            onClick = { 
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            }
                        ) {
                            Text(stringResource(R.string.onboarding_button_next))
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            prefs.currentModel = selectedModel
                            prefs.currentVoice = selectedVoice
                            prefs.isOnboardingComplete = true
                            
                            navController.navigate(Screen.Voices.route) {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.onboarding_button_get_started))
                    }
                }
            }
        }
    }
}

@Composable
fun ModelSelectionCard(
    modelId: String,
    title: String,
    description: String,
    warning: String?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = title, fontWeight = FontWeight.Bold, color = Color.White)
                // 中文能力标注（深色背景用高对比配色）
                com.leedor.tts.ui.components.ChineseCapabilityLabel(modelId, onDark = true)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
                if (warning != null) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF8A65), // Orange-ish
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun VoiceChip(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
        modifier = modifier.height(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

/**
 * 引导页里可选的预设语音。
 *
 * 只有「固定音色」模型才有预设语音：
 *  - Piper：内置 Amy（其余语音在语音库里下载）
 *  - Kitten：登记在 VoiceDefinitions 里的少数几个
 *  - Pocket-TTS：内置若干（不含名人语音）
 *
 * Kokoro v1.1-zh 的 103 个语音要等模型下载后从索引读取；
 * ZipVoice 是零样本克隆模型，没有预设语音 —— 两者都返回空列表，
 * 由调用处给出相应提示。
 */
private fun onboardingPresetVoices(modelId: String): List<com.leedor.tts.data.VoiceDefinition> = when {
    modelId.startsWith("piper") ->
        listOf(com.leedor.tts.data.VoiceDefinition("en_US-amy-low", "Amy (Low)", "Female", "US", null, null, "piper"))
    modelId == "pocket_v1" ->
        com.leedor.tts.data.VoiceDefinitions.POCKET_VOICES
    modelId == "kitten_nano" ->
        com.leedor.tts.data.VoiceDefinitions.getVoicesForModel(modelId)
    else -> emptyList()
}
