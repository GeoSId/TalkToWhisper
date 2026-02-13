package com.example.whisper.audio.processing

import android.media.AudioFormat

/**
 * Central configuration for the audio capture pipeline.
 *
 * Whisper models expect 16 kHz, mono, 16-bit PCM input, so all
 * defaults are tuned to produce exactly that format.
 */
object AudioConfig {

    // ── Recording format ────────────────────────────────────────────────────
    /** Sample rate required by Whisper. */
    const val SAMPLE_RATE: Int = 16_000

    /** Mono channel — single mic input. */
    const val CHANNEL_CONFIG: Int = AudioFormat.CHANNEL_IN_MONO

    /** 16-bit signed PCM (little-endian). */
    const val AUDIO_FORMAT: Int = AudioFormat.ENCODING_PCM_16BIT

    /** Bytes per sample (16-bit = 2 bytes). */
    const val BYTES_PER_SAMPLE: Int = 2

    // ── Buffer sizing ───────────────────────────────────────────────────────
    /**
     * Duration (ms) of each read buffer pulled from [android.media.AudioRecord].
     * 20 ms gives a good balance between latency and CPU overhead.
     */
    const val FRAME_DURATION_MS: Int = 20

    /** Number of samples per read frame (16 kHz × 0.020 s = 320). */
    val FRAME_SIZE: Int get() = SAMPLE_RATE * FRAME_DURATION_MS / 1000

    /** Byte length of one read frame (320 samples × 2 bytes). */
    val FRAME_BYTE_SIZE: Int get() = FRAME_SIZE * BYTES_PER_SAMPLE

    // ── VAD (Voice Activity Detection) ──────────────────────────────────────
    /**
     * RMS energy threshold below which a frame is classified as silence.
     * Tuned empirically — values may need adjustment per device.
     */
    const val VAD_ENERGY_THRESHOLD: Double = 150.0

    /**
     * Number of consecutive silent frames before speech is considered ended.
     * At 20 ms/frame this equals ~600 ms of trailing silence.
     */
    const val VAD_SILENCE_FRAMES: Int = 10

    /**
     * Minimum number of speech frames to form a valid utterance.
     * Prevents very short bursts of noise from being emitted.
     * At 20 ms/frame this equals ~200 ms minimum.
     */
    const val VAD_MIN_SPEECH_FRAMES: Int = 10

    /**
     * Maximum duration (ms) for a single audio chunk before force-flushing.
     * Prevents unbounded memory growth for very long utterances.
     * 30 seconds is a safe upper bound for Whisper processing.
     */
    const val MAX_CHUNK_DURATION_MS: Long = 15_000L

}
