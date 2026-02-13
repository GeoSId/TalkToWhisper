package com.example.whisper.audio.recognition

import android.util.Log
import com.example.whisper.modelmanager.storage.ModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device speech recognition using the native **whisper.cpp** library.
 *
 * This is the high-performance alternative to [WhisperRecognizer]
 * (TFLite).  It uses whisper.cpp compiled as a native JNI library,
 * which is typically 5–10× faster on ARM devices.
 *
 * ### Key differences from [WhisperRecognizer]:
 * - **No mel-spectrogram preprocessing** — whisper.cpp handles it
 *   internally in optimised C/NEON code.
 * - **No separate tokeniser** — whisper.cpp decodes tokens to text
 *   internally.  No `vocab.json` needed.
 * - **GGML model format** — uses `.bin` files instead of `.tflite`.
 *
 * ### Lifecycle
 * - [loadModel] creates a native [WhisperContext] from the GGML model
 *   file on disk.
 * - [unloadModel] releases the native context and frees memory.
 * - Between calls, the context stays in memory for low latency.
 *
 * ### Threading
 * Heavy work runs on [Dispatchers.Default]; the [Flow] returned by
 * [recognize] can be collected from any dispatcher.  Internally,
 * whisper.cpp uses its own thread pool (configured via
 * [WhisperCpuConfig.preferredThreadCount]).
 */
@Singleton
class WhisperCppRecognizer @Inject constructor(
    private val modelStorage: ModelStorage,
) : SpeechRecognizer {

    // ── State ────────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(RecognizerState.IDLE)
    override val state: StateFlow<RecognizerState> = _state.asStateFlow()

    private var whisperContext: WhisperContext? = null

    override val isModelLoaded: Boolean
        get() = whisperContext != null && _state.value == RecognizerState.READY

    // ── Language configuration ───────────────────────────────────────────────

    /**
     * Language code for speech recognition.
     *
     * - `"auto"` — auto-detect the spoken language (~99 languages).
     * - `"en"`, `"es"`, `"fr"`, etc. — force a specific language.
     *
     * Can be changed at any time (even between recognition calls).
     * Requires the **multilingual** model (`ggml-tiny.bin`).
     */
    var language: String = WhisperCppConfig.DEFAULT_LANGUAGE

    /**
     * When `true`, Whisper translates recognised speech to English
     * (built-in speech translation). When `false`, outputs the same
     * language as spoken.
     */
    var translateToEnglish: Boolean = WhisperCppConfig.TRANSLATE_TO_ENGLISH

    // ── Model lifecycle ─────────────────────────────────────────────────────

    override suspend fun loadModel() = withContext(Dispatchers.IO) {
        if (isModelLoaded) {
            Log.d(TAG, "Model already loaded — skipping")
            return@withContext
        }

        _state.value = RecognizerState.LOADING

        try {
            val modelFile = modelStorage.getWhisperCppModelFile()
            if (!modelFile.exists()) {
                _state.value = RecognizerState.ERROR
                throw ModelNotAvailableException(
                    "Whisper GGML model not found at ${modelFile.absolutePath}. " +
                            "Download it first using ModelDownloader."
                )
            }

            Log.i(
                TAG, "Loading whisper.cpp model from ${modelFile.absolutePath} " +
                        "(${modelFile.length() / 1_000_000} MB)"
            )

            val ctx = WhisperContext.createFromFile(modelFile.absolutePath)
            whisperContext = ctx

            Log.i(TAG, "whisper.cpp model loaded — ${WhisperContext.getSystemInfo()}")
            _state.value = RecognizerState.READY

        } catch (e: ModelNotAvailableException) {
            throw e
        } catch (e: Exception) {
            _state.value = RecognizerState.ERROR
            throw ModelLoadException("Failed to load whisper.cpp model", e)
        }
    }

    override suspend fun unloadModel() = withContext(Dispatchers.IO) {
        Log.i(TAG, "Unloading whisper.cpp model")
        try {
            whisperContext?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing whisper context", e)
        }
        whisperContext = null
        _state.value = RecognizerState.IDLE
    }

    // ── Recognition ─────────────────────────────────────────────────────────

    override fun recognize(audioData: ByteArray): Flow<RecognitionResult> = flow {
        check(isModelLoaded) {
            "Model not loaded — call loadModel() before recognize()"
        }

        _state.value = RecognizerState.PROCESSING

        try {
            val totalSamples = audioData.size / 2  // 16-bit PCM = 2 bytes/sample

            if (totalSamples <= WhisperCppConfig.MAX_SAMPLES) {
                // ── Single-window processing ─────────────────────────────
                val text = processWindow(audioData)
                emit(RecognitionResult(text = text, isFinal = true, language = language))
            } else {
                // ── Multi-window (chunked) processing ────────────────────
                val windowBytes = WhisperCppConfig.MAX_SAMPLES * 2
                val chunks = (audioData.size + windowBytes - 1) / windowBytes
                val accumulated = StringBuilder()

                for (i in 0 until chunks) {
                    val start = i * windowBytes
                    val end = minOf(start + windowBytes, audioData.size)
                    val windowData = audioData.copyOfRange(start, end)

                    val windowText = processWindow(windowData)
                    accumulated.append(windowText)

                    if (i < chunks - 1) {
                        emit(
                            RecognitionResult(
                                text = accumulated.toString(),
                                isFinal = false,
                            )
                        )
                    }
                }

                emit(
                    RecognitionResult(
                        text = accumulated.toString().trim(),
                        isFinal = true,
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recognition error", e)
            emit(RecognitionResult(text = "", isFinal = true, confidence = 0f))
        } finally {
            _state.value = RecognizerState.READY
        }
    }.flowOn(Dispatchers.Default)

    // ── Inference ────────────────────────────────────────────────────────────

    /**
     * Process a single audio window (≤ 30 s of 16-bit PCM) through
     * whisper.cpp and return the decoded text.
     *
     * whisper.cpp expects **float** PCM samples normalised to [-1, 1].
     */
    private fun processWindow(pcmData: ByteArray): String {
        val ctx = whisperContext
            ?: throw IllegalStateException("WhisperContext is null")

        // Convert 16-bit signed PCM bytes → float samples normalised to [-1, 1]
        val floatSamples = pcmToFloat(pcmData)

        Log.d(TAG, "Processing ${floatSamples.size} float samples")

        val text = ctx.transcribeData(
            data = floatSamples,
            language = language,
            translate = translateToEnglish,
        )

        Log.d(TAG, "Transcription result (lang=$language): \"$text\"")
        return text.trim()
    }

    /**
     * Convert raw 16-bit signed little-endian PCM bytes to a float array
     * with values normalised to [-1.0, 1.0].
     */
    private fun pcmToFloat(pcmData: ByteArray): FloatArray {
        val sampleCount = pcmData.size / 2
        val floats = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcmData[i * 2].toInt() and 0xFF
            val hi = pcmData[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo  // signed 16-bit little-endian
            floats[i] = sample / 32768.0f
        }
        return floats
    }

    companion object {
        private const val TAG = "WhisperCppRecognizer"
    }
}
