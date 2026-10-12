package com.leedor.tts.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.leedor.tts.R
import com.leedor.tts.data.ModelInfo
import com.leedor.tts.data.ModelRepository
import com.leedor.tts.data.VoiceRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTrigger by remember { mutableIntStateOf(0) } // To force recomposition on state changes
    
    // Get downloaded Piper voices dynamically (must be in composable scope, not LazyColumn)
    val voiceRepository = remember { VoiceRepository(context) }
    val downloadedVoices = remember(refreshTrigger) { 
        voiceRepository.getDownloadedVoices() 
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.models_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.models_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // Iterate over all models in repository
            items(ModelRepository.visibleModels, key = { it.id }) { modelInfo ->
                ModelCardWithState(
                    modelInfo = modelInfo,
                    refreshTrigger = refreshTrigger,
                    onRefresh = { refreshTrigger++ }
                )
            }
            
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // Piper Section with downloaded voices
            item {
                Text(
                    stringResource(R.string.models_downloaded_piper_voices),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            if (downloadedVoices.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.models_no_downloaded_piper_voices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(downloadedVoices, key = { it.id }) { voice ->
                    PiperVoiceCard(
                        voiceName = voice.name,
                        voiceId = voice.id,
                        language = voice.languageName,
                        sizeBytes = voice.sizeBytes,
                        onDelete = {
                            voiceRepository.deleteVoice(voice.id)
                            refreshTrigger++
                        }
                    )
                }
            }
            
            item {
                Text(
                    stringResource(R.string.models_bundled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun ModelCardWithState(
    modelInfo: ModelInfo,
    refreshTrigger: Int,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Track download state locally to trigger recomposition
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    
    val isInstalled = remember(refreshTrigger) { 
        ModelRepository.isInstalled(context, modelInfo.id) 
    }
    
    // Observe the progress flow when downloading
    LaunchedEffect(isDownloading) {
        if (isDownloading) {
            // Poll for progress updates
            while (isDownloading) {
                val flow = ModelRepository.getDownloadProgress(modelInfo.id)
                if (flow != null) {
                    flow.collect { progress ->
                        downloadProgress = progress
                    }
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }
    
    ModelCard(
        title = stringResource(com.leedor.tts.ui.ModelDisplay.nameRes(modelInfo.id)),
        description = stringResource(com.leedor.tts.ui.ModelDisplay.descriptionRes(modelInfo.id)),
        isInstalled = isInstalled,
        isDownloading = isDownloading,
        progress = downloadProgress,
        status = when {
            downloadError != null -> stringResource(R.string.models_status_error, downloadError.orEmpty())
            isDownloading -> stringResource(R.string.models_status_downloading, (downloadProgress * 100).toInt())
            else -> ""
        },
        onDownload = {
            downloadError = null
            isDownloading = true
            downloadProgress = 0f
            
            scope.launch {
                ModelRepository.downloadModel(context, modelInfo.id) { success ->
                    isDownloading = false
                    if (success) {
                        onRefresh() // Trigger UI update
                    } else {
                        downloadError = context.getString(R.string.models_error_download_failed)
                    }
                }
            }
        },
        onDelete = {
            ModelRepository.deleteModel(context, modelInfo.id)
            onRefresh()
        }
    )
}

@Composable
fun ModelCard(
    title: String,
    description: String,
    isInstalled: Boolean,
    isDownloading: Boolean,
    progress: Float,
    status: String,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                if (isInstalled) {
                    Icon(Icons.Default.CheckCircle, stringResource(R.string.models_installed), tint = MaterialTheme.colorScheme.primary)
                } else if (!isDownloading) { // Only show warning if not installed and not downloading
                     Icon(Icons.Default.Warning, stringResource(R.string.models_not_installed), tint = MaterialTheme.colorScheme.error)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(status, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (status.isNotEmpty()) {
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    if (isInstalled) {
                        OutlinedButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.models_delete))
                        }
                    } else {
                        Button(onClick = onDownload) {
                            Text(stringResource(R.string.models_download))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PiperVoiceCard(
    voiceName: String,
    voiceId: String,
    language: String,
    sizeBytes: Long,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(voiceName, fontWeight = FontWeight.Bold)
                Text(voiceId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "$language • ${formatFileSize(sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            IconButton(
                onClick = onDelete,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, stringResource(R.string.models_delete))
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
