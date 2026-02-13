package com.example.whisper.audio.recognition

import android.os.Build
import android.util.Log
import java.io.File

private const val LOG_TAG = "WhisperCppLib"

/**
 * Kotlin wrapper around a native whisper.cpp context.
 *
 * Each instance holds a pointer (`ptr`) to a `struct whisper_context *`
 * allocated in native memory.  All inference calls are serialised onto
 * a single-threaded dispatcher to satisfy whisper.cpp's requirement
 * that a context is not accessed from more than one thread at a time.
 *
 * ### Usage
 * ```kotlin
 * val ctx = WhisperContext.createFromFile("/path/to/ggml-tiny.en.bin")
 * val text = ctx.transcribeData(floatSamples)
 * ctx.release()
 * ```
 *
 * @see WhisperCppRecognizer  The [SpeechRecognizer] implementation that
 *   uses this wrapper.
 */
class WhisperContext private constructor(private var ptr: Long) {

    /**
     * Transcribe float-PCM audio data and return the recognised text.
     *
     * @param data       Float array of PCM audio samples (mono, 16 kHz,
     *                   normalised to [-1.0, 1.0]).
     * @param language   ISO-639-1 language code (e.g. `"en"`, `"es"`,
     *                   `"fr"`) or `"auto"` to let Whisper auto-detect.
     * @param translate  If `true`, Whisper translates speech to English.
     * @param numThreads Number of CPU threads to use for inference.
     * @return The full transcription text (all segments concatenated).
     */
    fun transcribeData(
        data: FloatArray,
        language: String = WhisperCppConfig.DEFAULT_LANGUAGE,
        translate: Boolean = WhisperCppConfig.TRANSLATE_TO_ENGLISH,
        numThreads: Int = WhisperCpuConfig.preferredThreadCount,
    ): String {
        require(ptr != 0L) { "WhisperContext has been released" }
        Log.d(LOG_TAG, "Transcribing ${data.size} samples with $numThreads threads, lang=$language, translate=$translate")

        WhisperCppLib.fullTranscribe(ptr, numThreads, data, language, translate)

        val segmentCount = WhisperCppLib.getTextSegmentCount(ptr)
        return buildString {
            for (i in 0 until segmentCount) {
                append(WhisperCppLib.getTextSegment(ptr, i))
            }
        }
    }

    /**
     * Release the native whisper context and free all associated memory.
     * Safe to call multiple times.
     */
    fun release() {
        if (ptr != 0L) {
            WhisperCppLib.freeContext(ptr)
            ptr = 0
            Log.d(LOG_TAG, "WhisperContext released")
        }
    }

    protected fun finalize() {
        release()
    }

    companion object {
        /**
         * Create a [WhisperContext] by loading a GGML model from a file path.
         *
         * @param filePath Absolute path to the `.bin` GGML model file.
         * @throws RuntimeException if the model cannot be loaded.
         */
        fun createFromFile(filePath: String): WhisperContext {
            val ptr = WhisperCppLib.initContext(filePath)
            if (ptr == 0L) {
                throw RuntimeException(
                    "Failed to create whisper.cpp context from $filePath"
                )
            }
            return WhisperContext(ptr)
        }

        /** Get a summary of CPU/SIMD features available to whisper.cpp. */
        fun getSystemInfo(): String = WhisperCppLib.getSystemInfo()
    }
}

// ── Native method declarations ──────────────────────────────────────────────

/**
 * Declares the JNI `external` methods that bridge to the C code in
 * `jni.c`.  The native library is loaded once in the `init` block.
 */
internal class WhisperCppLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")

            var loadVfpv4 = false
            var loadV8fp16 = false

            if (isArmEabiV7a()) {
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("vfpv4")) {
                        Log.d(LOG_TAG, "CPU supports vfpv4")
                        loadVfpv4 = true
                    }
                }
            } else if (isArmEabiV8a()) {
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("fphp")) {
                        Log.d(LOG_TAG, "CPU supports fp16 arithmetic")
                        loadV8fp16 = true
                    }
                }
            }

            when {
                loadVfpv4 -> {
                    Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                    System.loadLibrary("whisper_vfpv4")
                }
                loadV8fp16 -> {
                    Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                    System.loadLibrary("whisper_v8fp16_va")
                }
                else -> {
                    Log.d(LOG_TAG, "Loading libwhisper.so")
                    System.loadLibrary("whisper")
                }
            }
        }

        // ── JNI methods ──────────────────────────────────────────────────

        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String, translate: Boolean)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
    }
}

// ── CPU info helpers ────────────────────────────────────────────────────────

private fun isArmEabiV7a(): Boolean =
    Build.SUPPORTED_ABIS[0] == "armeabi-v7a"

private fun isArmEabiV8a(): Boolean =
    Build.SUPPORTED_ABIS[0] == "arm64-v8a"

private fun cpuInfo(): String? = try {
    File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
    null
}
