package com.leedor.tts.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leedor.tts.R
import com.leedor.tts.engine.ModelLanguageSupport
import com.leedor.tts.ui.viewmodel.Voice
import java.util.Locale

@Composable
fun VoiceCard(
    voice: Voice,
    isSelected: Boolean,
    onVoiceSelected: () -> Unit,
    onDownload: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDownloaded = voice.downloadState == com.leedor.tts.data.DownloadState.Downloaded
    val isDownloading = voice.downloadState == com.leedor.tts.data.DownloadState.Downloading
    val canDelete = voice.isCloned && onDelete != null
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onVoiceSelected() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Voice Icon / Initials
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = voice.name.first().toString(),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // Voice Details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = voice.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Flag or Region Tag
                        SuggestionChip(
                            onClick = { },
                            label = { Text(voice.region) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.height(24.dp)
                        )

                        // 中文能力标注：在 148 个语音里一眼找到能读中文的
                        if (ModelLanguageSupport.voiceIsChinese(voice.id)) {
                            Spacer(modifier = Modifier.width(6.dp))
                            val usable = ModelLanguageSupport.voiceChineseUsable(voice.id)
                            SuggestionChip(
                                onClick = { },
                                label = {
                                    Text(
                                        stringResource(
                                            if (usable) R.string.lang_badge_chinese
                                            else R.string.lang_badge_chinese_unavailable
                                        )
                                    )
                                },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (usable)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.errorContainer,
                                    labelColor = if (usable)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.height(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (voice.gender == "Unknown" || voice.gender.isEmpty()) voice.language else "${genderLabel(voice.gender)} • ${voice.language}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                
                // Download Action (show for Piper voices with metadata or celebrity voices without downloads)
                if (!isDownloaded && !isDownloading) {
                     IconButton(onClick = onDownload) {
                         Icon(
                             imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                             contentDescription = stringResource(R.string.voicecard_download),
                             tint = MaterialTheme.colorScheme.primary
                         )
                     }
                }
                
                // Delete button for cloned voices
                if (canDelete) {
                    IconButton(onClick = { onDelete?.invoke() }) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Close,
                            contentDescription = stringResource(R.string.voicecard_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            if (isDownloading) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.voicecard_downloading), 
                        style = MaterialTheme.typography.labelSmall, 
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${(voice.downloadProgress * 100).toInt()}%", 
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { voice.downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

/**
 * Localized display label for a voice gender value.
 * The raw value ("Male" / "Female") comes from the data model and is left untouched;
 * only the label shown to the user is translated.
 */
@Composable
private fun genderLabel(value: String): String = when (value.lowercase(Locale.US)) {
    "male" -> stringResource(R.string.voices_gender_male)
    "female" -> stringResource(R.string.voices_gender_female)
    else -> value
}
