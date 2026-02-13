package com.example.whisper.presentation.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whisper.audio.processing.AudioCaptureManager
import com.example.whisper.audio.processing.AudioChunk
import com.example.whisper.audio.recognition.RecognizerState
import com.example.whisper.audio.recognition.SpeechRecognizer
import com.example.whisper.audio.recognition.WhisperCppConfig
import com.example.whisper.audio.recognition.WhisperCppRecognizer
import com.example.whisper.modelmanager.downloader.DownloadState
import com.example.whisper.modelmanager.downloader.ModelDownloader
import com.example.whisper.modelmanager.storage.ModelStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Talk to Whisper main screen.
 *
 * Orchestrates the full pipeline:
 * 1. Model download (if needed)
 * 2. Model loading
 * 3. Audio capture via [AudioCaptureManager]
 * 4. Speech recognition via [SpeechRecognizer]
 *
 * Exposes a single [uiState] flow that the UI observes.
 */
@HiltViewModel
class WhisperViewModel @Inject constructor(
    private val speechRecognizer: SpeechRecognizer,
    private val audioCaptureManager: AudioCaptureManager,
    private val modelDownloader: ModelDownloader,
    private val modelStorage: ModelStorage,
) : ViewModel() {

    // ── UI State ─────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(WhisperUiState())
    val uiState: StateFlow<WhisperUiState> = _uiState.asStateFlow()

    /** Job tracking audio chunk collection — cancelled when recording stops. */
    private var chunkCollectionJob: Job? = null

    /** Idle timeout job — unloads the model after a period of inactivity. */
    private var idleTimeoutJob: Job? = null

    init {
        // Check if model is already downloaded
        _uiState.update {
            it.copy(isModelDownloaded = modelStorage.isWhisperCppModelDownloaded())
        }

        // Observe recognizer state
        viewModelScope.launch {
            speechRecognizer.state.collect { recognizerState ->
                _uiState.update { it.copy(recognizerState = recognizerState) }
            }
        }

        // Observe audio capture state
        viewModelScope.launch {
            audioCaptureManager.captureState.collect { captureState ->
                _uiState.update {
                    it.copy(
                        isRecording = captureState == AudioCaptureManager.CaptureState.RECORDING
                    )
                }
            }
        }

        // Observe RMS energy for waveform visualisation
        viewModelScope.launch {
            audioCaptureManager.rmsEnergy.collect { energy ->
                _uiState.update { it.copy(rmsEnergy = energy) }
            }
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────

    /**
     * Start downloading the Whisper model.
     * Progress is reported through [uiState].
     */
    fun downloadModel() {
        viewModelScope.launch {
            modelDownloader.downloadWhisperModel()
                .catch { e ->
                    Log.e(TAG, "Download error", e)
                    _uiState.update {
                        it.copy(
                            downloadState = DownloadState.Failed(e),
                            errorMessage = "Download failed: ${e.message}",
                        )
                    }
                }
                .collect { state ->
                    _uiState.update { current ->
                        current.copy(
                            downloadState = state,
                            isModelDownloaded = state is DownloadState.Completed,
                            errorMessage = if (state is DownloadState.Failed) {
                                "Download failed: ${state.error.message}"
                            } else {
                                current.errorMessage
                            },
                        )
                    }
                }
        }
    }

    // ── Recording ────────────────────────────────────────────────────────────

    /**
     * Toggle recording on/off.
     *
     * When starting:
     * 1. Loads the Whisper model if not already loaded.
     * 2. Starts audio capture.
     * 3. Begins collecting audio chunks and running recognition.
     *
     * When stopping:
     * 1. Stops audio capture.
     * 2. Starts an idle timeout to unload the model.
     */
    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        // Cancel any pending idle timeout and previous chunk collection
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
        chunkCollectionJob?.cancel()
        chunkCollectionJob = null

        viewModelScope.launch {
            try {
                // Clear previous results
                _uiState.update {
                    it.copy(
                        recognizedText = "",
                        partialText = "",
                        errorMessage = null,
                    )
                }

                // Ensure model is loaded
                if (!speechRecognizer.isModelLoaded) {
                    _uiState.update { it.copy(isLoadingModel = true) }
                    speechRecognizer.loadModel()
                    _uiState.update { it.copy(isLoadingModel = false) }
                }

                // Start audio capture
                audioCaptureManager.startCapture()

                // Start collecting audio chunks sequentially.
                chunkCollectionJob = viewModelScope.launch {
                    audioCaptureManager.audioChunks.collect { chunk ->
                        processAudioChunk(chunk)
                    }
                }

            } catch (e: SecurityException) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Microphone permission required",
                        isLoadingModel = false,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                _uiState.update {
                    it.copy(
                        errorMessage = "Error: ${e.message}",
                        isLoadingModel = false,
                    )
                }
            }
        }
    }

    private fun stopRecording() {
        audioCaptureManager.stopCapture()
        startIdleTimeout()
    }

    // ── Audio chunk processing ───────────────────────────────────────────────

    /**
     * Run speech recognition on an audio chunk from the VAD pipeline.
     */
    private suspend fun processAudioChunk(chunk: AudioChunk) {
        Log.d(TAG, "Processing audio chunk: $chunk")

        try {
            speechRecognizer.recognize(chunk.pcmData)
                .catch { e ->
                    Log.e(TAG, "Recognition error for chunk", e)
                }
                .collect { result ->
                    if (result.text.isBlank()) return@collect

                    _uiState.update { current ->
                        if (result.isFinal) {
                            val separator = if (current.recognizedText.isNotBlank()) " " else ""
                            current.copy(
                                recognizedText = current.recognizedText + separator + result.text,
                                partialText = "",
                            )
                        } else {
                            current.copy(partialText = result.text)
                        }
                    }
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio chunk", e)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Start a timer that unloads the model if no recording occurs
     * within [IDLE_TIMEOUT_MS].
     */
    private fun startIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = viewModelScope.launch {
            delay(IDLE_TIMEOUT_MS)
            Log.i(TAG, "Idle timeout — unloading model")
            speechRecognizer.unloadModel()
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (audioCaptureManager.isRecording) {
            audioCaptureManager.stopCapture()
        }
        viewModelScope.launch {
            speechRecognizer.unloadModel()
        }
    }

    // ── Language selection ──────────────────────────────────────────────────

    /**
     * Change the speech recognition language.
     *
     * @param languageCode ISO-639-1 code (e.g. `"el"`, `"en"`, `"es"`)
     *   or `"auto"` for auto-detection.
     */
    fun setLanguage(languageCode: String) {
        _uiState.update { it.copy(selectedLanguage = languageCode) }
        (speechRecognizer as? WhisperCppRecognizer)?.language = languageCode
    }

    /**
     * Clear any displayed error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        private const val TAG = "WhisperViewModel"

        /** Unload model after 2 minutes of inactivity. */
        private const val IDLE_TIMEOUT_MS = 2 * 60 * 1000L
    }
}

// ── UI State ────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of the Talk to Whisper screen's UI state.
 */
data class WhisperUiState(
    /** Whether the Whisper model file is on disk. */
    val isModelDownloaded: Boolean = false,
    /** Whether the model is currently being loaded into memory. */
    val isLoadingModel: Boolean = false,
    /** Current state of the recognizer engine. */
    val recognizerState: RecognizerState = RecognizerState.IDLE,
    /** Current model download state. */
    val downloadState: DownloadState = DownloadState.Idle,
    /** Whether audio is actively being captured. */
    val isRecording: Boolean = false,
    /** Current mic energy level (for waveform animation). */
    val rmsEnergy: Double = 0.0,
    /** Accumulated finalised recognised text. */
    val recognizedText: String = "",
    /** Current partial (in-progress) recognition result. */
    val partialText: String = "",
    /** Error message to display, or null if none. */
    val errorMessage: String? = null,
    /** Currently selected language code (e.g. "auto", "en", "el"). */
    val selectedLanguage: String = WhisperCppConfig.DEFAULT_LANGUAGE,
) {
    /** Combined display text: finalised + partial. */
    val displayText: String
        get() = buildString {
            append(recognizedText)
            if (partialText.isNotBlank()) {
                if (isNotBlank()) append(" ")
                append(partialText)
            }
        }
}
