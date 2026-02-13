package com.example.whisper.modelmanager.storage

import android.content.Context
import android.util.Log
import com.example.whisper.audio.recognition.WhisperCppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages on-disk storage for AI model files (Whisper STT, LLM brain,
 * Piper TTS voices, etc.).
 *
 * All models are stored under a dedicated `models/` directory inside
 * the app's internal files directory.  This keeps them protected by
 * Android's sandboxed storage and automatically cleaned up on uninstall.
 *
 * ### Directory layout
 * ```
 * /data/data/com.example.whisper/files/models/
 * ├── whisper/
 * │   └── ggml-base.bin
 * ```
 */
@Singleton
class ModelStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // ── Root directories ─────────────────────────────────────────────────────

    /** Top-level models directory. */
    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    /** Directory for Whisper STT model files. */
    val whisperDir: File
        get() = File(modelsDir, "whisper").also { it.mkdirs() }

    // ── Whisper (whisper.cpp / GGML) ─────────────────────────────────────────

    /**
     * Get the [File] reference for the whisper.cpp GGML model.
     * The file may or may not exist on disk.
     */
    fun getWhisperCppModelFile(): File =
        File(whisperDir, WhisperCppConfig.DEFAULT_MODEL_FILENAME)

    /**
     * Check whether the whisper.cpp GGML model has been downloaded.
     * No separate vocabulary file is needed for whisper.cpp.
     */
    fun isWhisperCppModelDownloaded(): Boolean {
        val modelFile = getWhisperCppModelFile()
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * Delete all downloaded Whisper model files.
     */
    fun clearWhisperModels(): Boolean {
        return whisperDir.deleteRecursively().also { success ->
            Log.i(TAG, "Cleared Whisper models: $success")
        }
    }

    companion object {
        private const val TAG = "ModelStorage"
    }
}
