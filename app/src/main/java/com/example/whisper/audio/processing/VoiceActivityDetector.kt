package com.example.whisper.audio.processing

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Energy-based Voice Activity Detector (VAD).
 *
 * Classifies each PCM frame as **speech** or **silence** by computing
 * the Root-Mean-Square (RMS) energy and comparing it to a configurable
 * threshold. An adaptive noise-floor is maintained to handle varying
 * background noise levels.
 *
 * ### How it works
 * 1. For every incoming frame the RMS energy is computed.
 * 2. The noise floor is updated with exponential smoothing when the
 *    current frame is classified as silence.
 * 3. A frame is classified as speech when its RMS exceeds
 *    `max(fixedThreshold, noiseFactor × noiseFloor)`.
 * 4. [processFrame] returns the classification result plus the raw
 *    energy value for optional waveform visualisation.
 */
@Singleton
class VoiceActivityDetector @Inject constructor() {

    // ── Adaptive noise floor ────────────────────────────────────────────────
    /** Exponential smoothing factor for the noise floor (0–1). */
    private val noiseAlpha: Double = 0.08

    /** Multiplier above the noise floor to trigger speech detection. */
    private val noiseFactor: Double = 2.5

    /** Running estimate of the background noise energy level. */
    private var noiseFloor: Double = AudioConfig.VAD_ENERGY_THRESHOLD * 0.5

    // ── State ───────────────────────────────────────────────────────────────
    /** Number of consecutive silent frames since the last speech frame. */
    var consecutiveSilenceFrames: Int = 0
        private set

    /** Number of consecutive speech frames in the current utterance. */
    var consecutiveSpeechFrames: Int = 0
        private set

    /** `true` if we're currently inside a speech segment. */
    var isSpeaking: Boolean = false
        private set

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Classify a single PCM frame.
     *
     * @param pcmFrame  Raw 16-bit signed PCM bytes (little-endian).
     * @return A [FrameResult] with the classification and energy value.
     */
    fun processFrame(pcmFrame: ByteArray): FrameResult {
        val rms = computeRms(pcmFrame)
        val effectiveThreshold = maxOf(
            AudioConfig.VAD_ENERGY_THRESHOLD,
            noiseFactor * noiseFloor,
        )

        val speechDetected = rms > effectiveThreshold

        if (speechDetected) {
            consecutiveSpeechFrames++
            consecutiveSilenceFrames = 0

            // Transition: silence → speech
            if (!isSpeaking && consecutiveSpeechFrames >= SPEECH_ONSET_FRAMES) {
                isSpeaking = true
            }
        } else {
            // Update the adaptive noise floor during silence.
            noiseFloor = noiseAlpha * rms + (1.0 - noiseAlpha) * noiseFloor

            consecutiveSilenceFrames++

            // Transition: speech → silence (after enough trailing silence).
            if (isSpeaking && consecutiveSilenceFrames >= AudioConfig.VAD_SILENCE_FRAMES) {
                isSpeaking = false
                consecutiveSpeechFrames = 0
            }
        }

        return FrameResult(
            isSpeech = isSpeaking,
            rmsEnergy = rms,
            threshold = effectiveThreshold,
        )
    }

    /** Reset all internal state. Call when starting a new capture session. */
    fun reset() {
        consecutiveSilenceFrames = 0
        consecutiveSpeechFrames = 0
        isSpeaking = false
        noiseFloor = AudioConfig.VAD_ENERGY_THRESHOLD * 0.5
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Compute the Root-Mean-Square energy of a 16-bit PCM byte array.
     */
    private fun computeRms(pcmBytes: ByteArray): Double {
        val shortBuffer = ByteBuffer
            .wrap(pcmBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val sampleCount = shortBuffer.remaining()
        if (sampleCount == 0) return 0.0

        var sumOfSquares = 0.0
        for (i in 0 until sampleCount) {
            val sample = shortBuffer[i].toDouble()
            sumOfSquares += sample * sample
        }
        return sqrt(sumOfSquares / sampleCount)
    }

    // ── Data ────────────────────────────────────────────────────────────────

    /**
     * Result of processing a single audio frame through the VAD.
     *
     * @property isSpeech    `true` if the frame is part of a speech segment.
     * @property rmsEnergy   The RMS energy of the frame (useful for waveform visualisation).
     * @property threshold   The effective threshold used for this classification.
     */
    data class FrameResult(
        val isSpeech: Boolean,
        val rmsEnergy: Double,
        val threshold: Double,
    )

    companion object {
        /**
         * Number of consecutive speech frames before the onset is confirmed.
         * Avoids triggering on single spiky noise frames.
         */
        private const val SPEECH_ONSET_FRAMES = 3
    }
}
