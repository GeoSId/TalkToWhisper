package com.example.whisper.audio.recognition

/**
 * Represents the output of a speech recognition pass.
 *
 * A recogniser emits a stream of [RecognitionResult]s as it processes
 * audio data.  Consumers should update their display on each emission.
 *
 * @property text       The recognised (or partially recognised) text.
 * @property isFinal    `true` when this result represents a complete,
 *                      finalised transcription of the audio chunk.
 * @property confidence Estimated confidence in [0.0 … 1.0]. `-1.0` when
 *                      confidence is unavailable.
 * @property language   ISO-639-1 language code detected by the model, or
 *                      `null` if language detection is not supported.
 */
data class RecognitionResult(
    val text: String,
    val isFinal: Boolean = false,
    val confidence: Float = -1f,
    val language: String? = null,
)
