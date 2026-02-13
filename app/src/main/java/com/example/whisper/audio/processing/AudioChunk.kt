package com.example.whisper.audio.processing

/**
 * An immutable segment of captured PCM audio that has been classified
 * as containing speech by the Voice Activity Detector.
 *
 * This is the primary data type emitted by [AudioCaptureManager] and
 * consumed downstream by the STT (Whisper) engine.
 *
 * @property pcmData  Raw 16-bit signed PCM bytes (little-endian, mono, 16 kHz).
 * @property sampleRate  Sample rate of the audio (always [AudioConfig.SAMPLE_RATE]).
 * @property durationMs  Duration of this chunk in milliseconds.
 * @property timestampMs  Wall-clock timestamp (epoch ms) when recording of this chunk started.
 */
data class AudioChunk(
    val pcmData: ByteArray,
    val sampleRate: Int = AudioConfig.SAMPLE_RATE,
    val durationMs: Long,
    val timestampMs: Long,
) {
    /** Number of 16-bit samples in this chunk. */
    val sampleCount: Int get() = pcmData.size / AudioConfig.BYTES_PER_SAMPLE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return pcmData.contentEquals(other.pcmData) &&
                sampleRate == other.sampleRate &&
                durationMs == other.durationMs &&
                timestampMs == other.timestampMs
    }

    override fun hashCode(): Int {
        var result = pcmData.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }

    override fun toString(): String =
        "AudioChunk(samples=$sampleCount, duration=${durationMs}ms, timestamp=$timestampMs)"
}
