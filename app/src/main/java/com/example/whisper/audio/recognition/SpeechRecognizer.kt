package com.example.whisper.audio.recognition

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over a speech-to-text engine.
 *
 * The default implementation is [WhisperRecognizer] which uses a Whisper
 * TFLite model running on-device.  The interface exists so that:
 * - The presentation layer depends on an abstraction, not a concrete engine.
 * - Alternative STT back-ends (Google Cloud Speech, Android built-in, etc.)
 *   can be swapped in without touching the UI or domain layers.
 *
 * ### Lifecycle
 * 1. Call [loadModel] **once** before the first recognition.
 * 2. Call [recognize] as many times as needed — each call processes one
 *    audio segment and returns streaming partial results as a [Flow].
 * 3. Call [unloadModel] when the recogniser is no longer needed (e.g.
 *    when the user leaves the interpreter screen) to free memory.
 *
 * ### Threading
 * All suspend functions are safe to call from the Main dispatcher; heavy
 * work is dispatched internally.
 */
interface SpeechRecognizer {

    // ── Model lifecycle ─────────────────────────────────────────────────────

    /** Current state of the recognition engine. */
    val state: StateFlow<RecognizerState>

    /**
     * Load the STT model into memory.
     *
     * This is a potentially expensive operation (tens to hundreds of MB)
     * and should be called once, ideally when the translation pipeline
     * is started.
     *
     * @throws ModelNotAvailableException if the model file has not been
     *   downloaded yet.
     * @throws ModelLoadException if the model cannot be initialised.
     */
    suspend fun loadModel()

    /**
     * Unload the STT model from memory.
     * Safe to call even if the model is not currently loaded.
     */
    suspend fun unloadModel()

    /** `true` when the model is loaded and ready to process audio. */
    val isModelLoaded: Boolean

    // ── Recognition ─────────────────────────────────────────────────────────

    /**
     * Recognise speech from raw PCM audio data.
     *
     * @param audioData Raw 16-bit signed PCM bytes (little-endian, mono,
     *   16 kHz) — the format produced by [AudioCaptureManager].
     * @return A [Flow] that emits [RecognitionResult]s.  Partial results
     *   (`isFinal = false`) are emitted during processing, followed by
     *   a single final result (`isFinal = true`).
     * @throws IllegalStateException if the model has not been loaded.
     */
    fun recognize(audioData: ByteArray): Flow<RecognitionResult>
}

// ── State enum ──────────────────────────────────────────────────────────────

/**
 * Possible states of a [SpeechRecognizer] engine.
 */
enum class RecognizerState {
    /** No model loaded — call [SpeechRecognizer.loadModel] first. */
    IDLE,

    /** The model is currently being loaded into memory. */
    LOADING,

    /** The model is loaded and ready to accept audio. */
    READY,

    /** The engine is actively processing an audio segment. */
    PROCESSING,

    /** An error occurred — check logs for details. */
    ERROR,
}

// ── Exceptions ──────────────────────────────────────────────────────────────

/**
 * Thrown when the model file has not been downloaded to disk yet.
 */
class ModelNotAvailableException(message: String) : RuntimeException(message)

/**
 * Thrown when the TFLite model fails to load or initialise.
 */
class ModelLoadException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
