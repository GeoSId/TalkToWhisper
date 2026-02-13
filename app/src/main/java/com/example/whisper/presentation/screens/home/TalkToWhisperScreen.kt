package com.example.whisper.presentation.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.whisper.R
import com.example.whisper.audio.recognition.RecognizerState
import com.example.whisper.modelmanager.downloader.DownloadState
import com.example.whisper.presentation.viewmodels.WhisperUiState
import com.example.whisper.presentation.viewmodels.WhisperViewModel

/**
 * The single main screen for Talk to Whisper.
 *
 * Handles model download, language selection, recording controls,
 * and live transcription display — all in one unified interface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkToWhisperScreen(
    viewModel: WhisperViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error via snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Model status / download section ─────────────────────────
            if (!uiState.isModelDownloaded) {
                ModelDownloadSection(
                    downloadState = uiState.downloadState,
                    onDownloadClick = viewModel::downloadModel,
                )
            } else {
                ModelReadyBadge()
            }

            Spacer(Modifier.height(8.dp))

            // ── Language picker ────────────────────────────────────────
            LanguagePicker(
                selectedLanguage = uiState.selectedLanguage,
                onLanguageSelected = viewModel::setLanguage,
            )

            Spacer(Modifier.height(8.dp))

            // ── Transcription display ─────────────────────────────────
            TranscriptionCard(
                uiState = uiState,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.height(24.dp))

            // ── Recognizer state indicator ──────────────────────────────
            RecognizerStateIndicator(state = uiState.recognizerState)

            Spacer(Modifier.height(16.dp))

            // ── Mic button ──────────────────────────────────────────────
            MicButton(
                isRecording = uiState.isRecording,
                isEnabled = uiState.isModelDownloaded &&
                        uiState.recognizerState != RecognizerState.LOADING,
                isLoadingModel = uiState.isLoadingModel,
                rmsEnergy = uiState.rmsEnergy,
                onClick = viewModel::toggleRecording,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Model download section ──────────────────────────────────────────────────

@Composable
private fun ModelDownloadSection(
    downloadState: DownloadState,
    onDownloadClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.whisper_model_required),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.whisper_model_description_cpp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            // Engine badge
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.whisper_engine_label,
                    stringResource(R.string.whisper_engine_cpp),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
            )

            Spacer(Modifier.height(16.dp))

            when (downloadState) {
                is DownloadState.Idle, is DownloadState.Failed -> {
                    Button(
                        onClick = onDownloadClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.whisper_download_model))
                    }

                    if (downloadState is DownloadState.Failed) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 100.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                )
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.whisper_download_failed,
                                    downloadState.error.message ?: "Unknown error",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                is DownloadState.Starting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.whisper_download_starting),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is DownloadState.Downloading -> {
                    val progressText = if (downloadState.totalBytes > 0) {
                        val downloadedMb = downloadState.bytesDownloaded / 1_000_000f
                        val totalMb = downloadState.totalBytes / 1_000_000f
                        stringResource(
                            R.string.whisper_download_progress_mb,
                            downloadedMb,
                            totalMb,
                        )
                    } else {
                        stringResource(
                            R.string.whisper_download_progress_bytes,
                            downloadState.bytesDownloaded / 1_000_000f,
                        )
                    }

                    if (downloadState.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            strokeCap = StrokeCap.Round,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            strokeCap = StrokeCap.Round,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }

                is DownloadState.Completed -> {
                    Text(
                        text = stringResource(R.string.whisper_download_complete),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelReadyBadge() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.whisper_model_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(
                        R.string.whisper_engine_label,
                        stringResource(R.string.whisper_engine_cpp),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// ── Language picker ─────────────────────────────────────────────────────────

/**
 * A list of selectable languages for the Whisper recognizer.
 * Each pair is (ISO-639-1 code, user-facing label).
 */
private val LANGUAGE_OPTIONS = listOf(
    "auto" to "Auto",
    "el" to "Ελληνικά",
    "en" to "English",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "it" to "Italiano",
    "pt" to "Português",
    "ar" to "العربية",
    "zh" to "中文",
    "ja" to "日本語",
    "ko" to "한국어",
    "ru" to "Русский",
    "tr" to "Türkçe",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LANGUAGE_OPTIONS.forEach { (code, label) ->
            FilterChip(
                selected = selectedLanguage == code,
                onClick = { onLanguageSelected(code) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

// ── Transcription display ───────────────────────────────────────────────────

@Composable
private fun TranscriptionCard(
    uiState: WhisperUiState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.transcription_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(12.dp))

            if (uiState.displayText.isBlank()) {
                // Placeholder
                Text(
                    text = if (uiState.isRecording) {
                        stringResource(R.string.transcription_listening)
                    } else {
                        stringResource(R.string.transcription_tap_to_speak)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            } else {
                // Final text
                if (uiState.recognizedText.isNotBlank()) {
                    Text(
                        text = uiState.recognizedText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Default,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                    )
                }

                // Partial (in-progress) text shown in a different style
                AnimatedVisibility(
                    visible = uiState.partialText.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        text = uiState.partialText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Default,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

// ── Recognizer state indicator ──────────────────────────────────────────────

@Composable
private fun RecognizerStateIndicator(state: RecognizerState) {
    val (text, color) = when (state) {
        RecognizerState.IDLE -> stringResource(R.string.state_idle) to
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        RecognizerState.LOADING -> stringResource(R.string.state_loading) to
                MaterialTheme.colorScheme.tertiary
        RecognizerState.READY -> stringResource(R.string.state_ready) to
                MaterialTheme.colorScheme.primary
        RecognizerState.PROCESSING -> stringResource(R.string.state_processing) to
                MaterialTheme.colorScheme.secondary
        RecognizerState.ERROR -> stringResource(R.string.state_error) to
                MaterialTheme.colorScheme.error
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

// ── Mic button ──────────────────────────────────────────────────────────────

@Composable
private fun MicButton(
    isRecording: Boolean,
    isEnabled: Boolean,
    isLoadingModel: Boolean,
    rmsEnergy: Double,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isRecording) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "micButtonColor",
    )

    // Pulsating scale when recording (driven by energy)
    val pulseTransition = rememberInfiniteTransition(label = "micPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "micPulseScale",
    )
    val scale = if (isRecording) pulse else 1f

    Box(contentAlignment = Alignment.Center) {
        // Outer ring animation when recording
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(scale * 1.1f)
                    .clip(CircleShape)
                    .background(
                        containerColor.copy(alpha = 0.15f)
                    ),
            )
        }

        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier
                .size(80.dp)
                .scale(scale),
            shape = CircleShape,
            containerColor = containerColor,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = if (isRecording) 8.dp else 4.dp,
            ),
        ) {
            if (isLoadingModel) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = when {
                        !isEnabled -> Icons.Default.MicOff
                        isRecording -> Icons.Default.Stop
                        else -> Icons.Default.Mic
                    },
                    contentDescription = if (isRecording) {
                        stringResource(R.string.recording_stop)
                    } else {
                        stringResource(R.string.recording_start)
                    },
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
