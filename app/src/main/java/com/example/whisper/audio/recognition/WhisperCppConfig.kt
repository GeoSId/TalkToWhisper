package com.example.whisper.audio.recognition

/**
 * Constants for the whisper.cpp (GGML) speech recognition engine.
 *
 * Unlike the TFLite path ([WhisperConfig]), whisper.cpp:
 * - Uses GGML-format model files (`.bin`), not `.tflite`.
 * - Handles tokenisation internally — no separate `vocab.json` needed.
 * - Expects raw float-PCM input, not mel spectrograms.
 *
 * @see WhisperCppRecognizer
 */
object WhisperCppConfig {

    // ── Audio ────────────────────────────────────────────────────────────────

    /** Expected sample rate (Hz). Must match the audio capture pipeline. */
    const val SAMPLE_RATE: Int = 16_000

    /** Maximum audio duration (seconds) per inference window. */
    const val MAX_AUDIO_SECONDS: Int = 30

    /** Total float samples in a 30-second window. */
    const val MAX_SAMPLES: Int = SAMPLE_RATE * MAX_AUDIO_SECONDS  // 480,000

    // ── Language ─────────────────────────────────────────────────────────────

    /**
     * Language code to use for speech recognition.
     *
     * - `"auto"` — let Whisper auto-detect the spoken language.
     * - `"en"`, `"es"`, `"fr"`, `"de"`, `"zh"`, `"ja"`, `"ar"`, etc.
     *   — force a specific language (ISO-639-1 code).
     *
     * Using `"auto"` adds a small overhead (~100 ms) for language detection
     * but allows recognising any of the ~99 supported languages.
     *
     * @see SUPPORTED_LANGUAGES
     */
    const val DEFAULT_LANGUAGE: String = "auto"

    /**
     * Whether to translate the recognised speech to English.
     *
     * When `true`, Whisper will output English text regardless of the
     * spoken language (built-in speech translation).
     * When `false`, Whisper outputs text in the same language as spoken.
     */
    const val TRANSLATE_TO_ENGLISH: Boolean = false

    /**
     * Subset of languages supported by the multilingual Whisper model.
     * Full list: https://github.com/openai/whisper#available-models-and-languages
     */
    val SUPPORTED_LANGUAGES: Map<String, String> = mapOf(
        "auto" to "Auto-detect",
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "nl" to "Dutch",
        "pl" to "Polish",
        "ru" to "Russian",
        "uk" to "Ukrainian",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "hi" to "Hindi",
        "tr" to "Turkish",
        "vi" to "Vietnamese",
        "th" to "Thai",
        "el" to "Greek",
        "cs" to "Czech",
        "ro" to "Romanian",
        "da" to "Danish",
        "fi" to "Finnish",
        "hu" to "Hungarian",
        "sv" to "Swedish",
        "no" to "Norwegian",
        "id" to "Indonesian",
        "ms" to "Malay",
        "he" to "Hebrew",
        "fa" to "Persian",
        "ta" to "Tamil",
        "bg" to "Bulgarian",
        "hr" to "Croatian",
        "sr" to "Serbian",
        "sk" to "Slovak",
        "sl" to "Slovenian",
        "ca" to "Catalan",
        "sw" to "Swahili",
        "tl" to "Tagalog",
        "ur" to "Urdu",
    )

    // ── Model files ──────────────────────────────────────────────────────────

    /**
     * Default GGML model filename stored on disk.
     *
     * **Base multilingual** — much better accuracy for non-English languages
     * (Greek, Arabic, Chinese, etc.) compared to the tiny model.
     *
     * Available models (multilingual):
     * | Model   | File                | Size    | Speed   | Accuracy |
     * |---------|---------------------|---------|---------|----------|
     * | tiny    | ggml-tiny.bin       | ~75 MB  | Fastest | Low      |
     * | base    | ggml-base.bin       | ~142 MB | Fast    | Good     |
     * | small   | ggml-small.bin      | ~466 MB | Moderate| Better   |
     */
    const val DEFAULT_MODEL_FILENAME: String = "ggml-small.bin"

    /**
     * Download URL for the **multilingual** Whisper Base GGML model (~142 MB).
     * Hosted on HuggingFace by the whisper.cpp author.
     */
    const val DEFAULT_MODEL_URL: String =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"

    /** Approximate file size of the GGML base model (bytes). */
    const val BASE_MODEL_SIZE_BYTES: Long = 142_000_000L  // ~142 MB
}
