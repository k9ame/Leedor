package com.leedor.tts.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.leedor.tts.R
import com.leedor.tts.support.SupportReportManager
import com.leedor.tts.support.SupportShareHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var lastReport by remember { mutableStateOf<File?>(SupportReportManager.latestReport(context)) }
    var generatingReport by remember { mutableStateOf(false) }
    
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            val prefs = remember { com.leedor.tts.data.PrefsManager(context) }
            // Use .value for manual control to avoid 'by' delegation complexity with mismatched imports if simpler
            var currentModel by remember { mutableStateOf(prefs.currentModel) }
            var threads by remember { mutableFloatStateOf(prefs.cpuThreads.toFloat()) }
            
            SettingsSection(title = stringResource(R.string.settings_section_ai_model)) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    val models = com.leedor.tts.data.ModelRepository.visibleModels

                    // 模型列表**只有一个来源**：assets/models.json（经 ModelRepository）。
                    // 这里原来还有一行硬编码的「Piper (ONNX)」，和索引里的 Piper 条目
                    // 指向同一个 id，导致 Piper 出现两次、且标签有误导性
                    // （Piper 本来就全是 ONNX）。现已删除。
                    //
                    // 选中判断用 model.matches(currentModel)：Piper 的运行时 id 是
                    // `piper_<语音名>`（会随用户在语音库换语音而变），必须前缀匹配。
                    models.forEach { model ->
                        val isInstalled = com.leedor.tts.data.ModelRepository.isInstalled(context, model.id)
                        val isSelected = model.matches(currentModel)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isInstalled) {
                                        // 多语音模型：已经是当前模型时不要重置语音，
                                        // 否则用户在语音库选的中文语音会被覆盖回默认值。
                                        if (!isSelected) {
                                            currentModel = model.id
                                            prefs.currentModel = model.id
                                            if (!model.multiVoice) {
                                                // 单语音模型，语音跟着模型走
                                                prefs.currentVoice = model.id.removePrefix("piper_")
                                            }
                                        }
                                    } else {
                                        navController.navigate(com.leedor.tts.ui.navigation.Screen.ModelManager.route)
                                    }
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    if (isInstalled) {
                                        if (!isSelected) {
                                            currentModel = model.id
                                            prefs.currentModel = model.id
                                            if (!model.multiVoice) {
                                                prefs.currentVoice = model.id.removePrefix("piper_")
                                            }
                                        }
                                    } else {
                                        navController.navigate(com.leedor.tts.ui.navigation.Screen.ModelManager.route)
                                    }
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(com.leedor.tts.ui.ModelDisplay.nameRes(model.id)),
                                    fontWeight = FontWeight.Bold
                                )
                                com.leedor.tts.ui.components.ChineseCapabilityLabel(model.id)

                                when {
                                    isSelected && !isInstalled ->
                                        Text(
                                            stringResource(R.string.settings_selection_pending_download),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )

                                    !isInstalled ->
                                        Text(
                                            stringResource(R.string.settings_download_required),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )

                                    // 多语音模型选中时显示当前语音，而不是笼统的描述
                                    isSelected && model.multiVoice -> {
                                        val voice = prefs.currentVoice
                                        Text(
                                            if (voice.isBlank()) stringResource(R.string.settings_no_voice_yet)
                                            else stringResource(R.string.settings_active_voice, voice),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            stringResource(R.string.settings_multi_voice_hint),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }

                                    else ->
                                        Text(
                                            stringResource(com.leedor.tts.ui.ModelDisplay.descriptionRes(model.id)),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedButton(
                        onClick = { navController.navigate(com.leedor.tts.ui.navigation.Screen.ModelManager.route) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_manage_models))
                    }
                }
            }
            
            HorizontalDivider()
            
            SettingsSection(title = stringResource(R.string.settings_section_performance)) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    // CPU Threads
                    Text(stringResource(R.string.settings_cpu_threads, threads.toInt()), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.settings_cpu_threads_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = threads,
                        onValueChange = { threads = it },
                        onValueChangeFinished = { prefs.cpuThreads = threads.toInt() },
                        valueRange = 1f..8f,
                        steps = 6
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Streaming Optimization (Token Size)
                    var tokenSize by remember { mutableIntStateOf(prefs.streamTokenSize) }
                    val autoLabel = stringResource(R.string.settings_auto)
                    val displayTokenSize = if (tokenSize == 0) autoLabel else tokenSize.toString()
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_stream_buffer, displayTokenSize), fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(R.string.settings_stream_buffer_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (tokenSize > 0 && tokenSize < 50) {
                                Text(
                                    stringResource(R.string.settings_warning_low_buffer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (currentModel == "kitten_nano" && tokenSize > 0 && tokenSize > 300) {
                                Text(
                                    stringResource(R.string.settings_warning_high_buffer_kitten),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Button(
                            onClick = { 
                                tokenSize = 0 
                                prefs.streamTokenSize = 0
                            },
                            enabled = tokenSize != 0
                        ) {
                            Text(stringResource(R.string.settings_reset))
                        }
                    }
                    
                    Slider(
                        value = if (tokenSize == 0) 50f else tokenSize.toFloat(),
                        onValueChange = { tokenSize = it.toInt() },
                        onValueChangeFinished = { prefs.streamTokenSize = tokenSize },
                        valueRange = 10f..500f,
                        steps = 48 
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Speed Control (Kitten & Piper)
                    if (currentModel == "kitten_nano" || currentModel.startsWith("piper")) {
                         var speed by remember { mutableFloatStateOf(prefs.speechSpeed) }
                         
                         Row(
                             verticalAlignment = Alignment.CenterVertically,
                             modifier = Modifier.fillMaxWidth()
                         ) {
                             Text(stringResource(R.string.settings_speech_speed, String.format("%.1f", speed)), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                             Button(
                                 onClick = { 
                                     speed = 1.0f
                                     prefs.speechSpeed = 1.0f 
                                 },
                                 enabled = speed != 1.0f
                             ) {
                                 Text(stringResource(R.string.settings_reset))
                             }
                         }
                         if (currentModel.startsWith("piper")) {
                             Text(
                                 stringResource(R.string.settings_speed_piper_note),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                             )
                         } else {
                             Text(
                                 stringResource(R.string.settings_speed_hint),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant
                             )
                         }
                         Slider(
                             value = speed,
                             onValueChange = { speed = it },
                             onValueChangeFinished = { prefs.speechSpeed = speed },
                             valueRange = 0.5f..2.0f,
                             steps = 14 // 0.1 increments
                         )
                    } else if (currentModel == "pocket_v1") {
                        // Pocket-TTS specific settings
                        Text(stringResource(R.string.settings_pocket_tts_settings), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Temperature
                        var temperature by remember { mutableFloatStateOf(prefs.pocketTemperature) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.settings_temperature, String.format("%.1f", temperature)), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Button(
                                onClick = { 
                                    temperature = 0.7f
                                    prefs.pocketTemperature = 0.7f 
                                },
                                enabled = temperature != 0.7f
                            ) {
                                Text(stringResource(R.string.settings_reset))
                            }
                        }
                        Text(
                            stringResource(R.string.settings_temperature_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = it },
                            onValueChangeFinished = { prefs.pocketTemperature = temperature },
                            valueRange = 0.3f..1.0f,
                            steps = 6
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // LSD Steps
                        var lsdSteps by remember { mutableIntStateOf(prefs.pocketLsdSteps) }
                        Text(stringResource(R.string.settings_flow_steps, lsdSteps), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.settings_flow_steps_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            listOf(1, 5, 10).forEach { steps ->
                                val isSelected = lsdSteps == steps
                                Button(
                                    onClick = { 
                                        lsdSteps = steps
                                        prefs.pocketLsdSteps = steps
                                    },
                                    colors = if (isSelected) ButtonDefaults.buttonColors() 
                                             else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text("$steps")
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Frames After EOS
                        var framesAfterEos by remember { mutableIntStateOf(prefs.pocketFramesAfterEos) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.settings_frames_after_eos, framesAfterEos), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Button(
                                onClick = { 
                                    framesAfterEos = 3
                                    prefs.pocketFramesAfterEos = 3 
                                },
                                enabled = framesAfterEos != 3
                            ) {
                                Text(stringResource(R.string.settings_reset))
                            }
                        }
                        Text(
                            stringResource(R.string.settings_frames_after_eos_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = framesAfterEos.toFloat(),
                            onValueChange = { framesAfterEos = it.toInt() },
                            onValueChangeFinished = { prefs.pocketFramesAfterEos = framesAfterEos },
                            valueRange = 1f..5f,
                            steps = 3
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Decoding Mode
                        var decodingMode by remember { mutableStateOf(prefs.pocketDecodingMode) }
                        Text(stringResource(R.string.settings_decoding_mode), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.settings_decoding_mode_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val batchLabel = stringResource(R.string.settings_decoding_batch_quality)
                        val streamingLabel = stringResource(R.string.settings_decoding_streaming_fast)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            listOf("batch" to batchLabel, "streaming" to streamingLabel).forEach { (mode, label) ->
                                val isSelected = decodingMode == mode
                                Button(
                                    onClick = { 
                                        decodingMode = mode
                                        prefs.pocketDecodingMode = mode
                                    },
                                    colors = if (isSelected) ButtonDefaults.buttonColors() 
                                             else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(label)
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Decode Chunk Size (only visible in batch mode)
                        if (decodingMode == "batch") {
                            var chunkSize by remember { mutableIntStateOf(prefs.pocketDecodeChunkSize) }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.settings_decode_chunk_size, chunkSize), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Button(
                                    onClick = { 
                                        chunkSize = 15
                                        prefs.pocketDecodeChunkSize = 15 
                                    },
                                    enabled = chunkSize != 15
                                ) {
                                    Text(stringResource(R.string.settings_reset))
                                }
                            }
                            Text(
                                stringResource(R.string.settings_decode_chunk_size_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = chunkSize.toFloat(),
                                onValueChange = { chunkSize = it.toInt() },
                                onValueChangeFinished = { prefs.pocketDecodeChunkSize = chunkSize },
                                valueRange = 5f..30f,
                                steps = 4
                            )
                        }
                    } else {
                        Text(stringResource(R.string.settings_speech_speed_title), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                         Text(
                             stringResource(R.string.settings_speed_kokoro_fixed),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                         )
                    }
                }
            }
            
            HorizontalDivider()

            // ---- 下载源 ----
            // huggingface.co 在部分网络下完全不可达，所以允许手动切换。
            // 默认 auto：镜像优先、官方兜底（见 DownloadHosts）。
            SettingsSection(title = stringResource(R.string.settings_section_download)) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    var hostMode by remember { mutableStateOf(prefs.downloadHostMode) }
                    val options = listOf(
                        com.leedor.tts.data.DownloadHosts.MODE_AUTO to R.string.settings_download_host_auto,
                        com.leedor.tts.data.DownloadHosts.MODE_MIRROR to R.string.settings_download_host_mirror,
                        com.leedor.tts.data.DownloadHosts.MODE_OFFICIAL to R.string.settings_download_host_official,
                    )
                    options.forEach { (mode, labelRes) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    hostMode = mode
                                    prefs.downloadHostMode = mode
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = hostMode == mode,
                                onClick = {
                                    hostMode = mode
                                    prefs.downloadHostMode = mode
                                }
                            )
                            Text(stringResource(labelRes))
                        }
                    }
                    Text(
                        stringResource(R.string.settings_download_host_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            HorizontalDivider()
            
            SettingsSection(title = stringResource(R.string.settings_section_battery)) {
                 SettingsItem(
                    title = stringResource(R.string.settings_disable_battery_optimization),
                    subtitle = stringResource(R.string.settings_disable_battery_optimization_subtitle),
                    icon = Icons.Default.Warning,
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback to generic settings
                             val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                             context.startActivity(intent)
                        }
                    }
                 )
            }

            HorizontalDivider()
            
            SettingsSection(title = stringResource(R.string.settings_section_general)) {
                SettingsItem(
                    title = stringResource(R.string.settings_system_tts),
                    subtitle = stringResource(R.string.settings_system_tts_subtitle),
                    icon = Icons.Default.Settings,
                    onClick = {
                        val intent = android.content.Intent("com.android.settings.TTS_SETTINGS")
                        context.startActivity(intent)
                    }
                )
            }
            
            HorizontalDivider()
            
            // Appearance Section - Book-Story inspired theme picker
            SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                Column {
                    var appTheme by remember { mutableStateOf(prefs.appTheme) }
                    var darkMode by remember { mutableStateOf(prefs.darkMode) }
                    
                    // Determine dark mode state for preview
                    val isDarkPreview = when (darkMode) {
                        "FOLLOW_SYSTEM" -> androidx.compose.foundation.isSystemInDarkTheme()
                        "LIGHT" -> false
                        else -> true
                    }
                    val isPureDark = darkMode == "PURE_DARK"
                    
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
            
            HorizontalDivider()
            
            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                SettingsItem(
                    title = stringResource(R.string.settings_about_app_title),
                    subtitle = stringResource(R.string.settings_version, com.leedor.tts.BuildConfig.VERSION_NAME),
                    icon = Icons.Default.Info,
                    onClick = { }
                )

                SettingsItem(
                    title = if (generatingReport) stringResource(R.string.settings_generating_support_report) else stringResource(R.string.settings_generate_support_report),
                    subtitle = stringResource(R.string.settings_generate_support_report_subtitle),
                    icon = Icons.Default.Warning,
                    onClick = {
                        if (generatingReport) {
                            return@SettingsItem
                        }

                        generatingReport = true
                        scope.launch {
                            try {
                                val report = withContext(Dispatchers.IO) {
                                    SupportReportManager.createReport(context)
                                }
                                lastReport = report
                                snackbarHostState.showSnackbar(context.getString(R.string.settings_support_report_ready, report.name))
                            } catch (t: Throwable) {
                                snackbarHostState.showSnackbar(context.getString(R.string.settings_support_report_failed))
                            } finally {
                                generatingReport = false
                            }
                        }
                    }
                )

                SettingsItem(
                    title = stringResource(R.string.settings_share_support_report),
                    subtitle = stringResource(R.string.settings_share_support_report_subtitle),
                    icon = Icons.Default.Info,
                    onClick = {
                        val report = lastReport ?: SupportReportManager.latestReport(context)
                        if (report == null || !report.exists()) {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.settings_support_report_none))
                            }
                            return@SettingsItem
                        }

                        lastReport = report
                        try {
                            SupportShareHelper.shareReport(context, report)
                        } catch (t: Throwable) {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.settings_support_report_share_failed))
                            }
                        }
                    }
                )

                SettingsItem(
                    title = stringResource(R.string.settings_file_bug_github),
                    subtitle = stringResource(R.string.settings_file_bug_github_subtitle),
                    icon = Icons.Default.Info,
                    onClick = {
                        val reportName = (lastReport ?: SupportReportManager.latestReport(context))?.name
                        try {
                            SupportShareHelper.openIssue(context, reportName)
                        } catch (t: Throwable) {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.settings_open_bug_form_failed))
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let {
            { Icon(imageVector = it, contentDescription = null) }
        },
        modifier = Modifier.clickable { onClick() }
    )
}
