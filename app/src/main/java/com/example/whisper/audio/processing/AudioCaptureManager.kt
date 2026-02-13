package com.example.whisper.audio.processing

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages real-time microphone capture via [AudioRecord], pipes each
 * frame through a [VoiceActivityDetector], and emits [AudioChunk]s
 * containing only the segments classified as speech.
 *
 * ### Lifecycle
 * 1. Call [startCapture] to begin recording.
 * 2. Collect [audioChunks] to receive speech-only PCM segments.
 * 3. Call [stopCapture] (or cancel the coroutine scope) to release the mic.
 *
 * ### Audio focus integration
 * When [AudioFocusManager.hasFocus] transitions to `false` the read loop
 * pauses automatically; when focus returns recording resumes without
 * losing the current utterance buffer.
 *
 * ### Threading
 * The read loop runs on [Dispatchers.IO] inside a dedicated coroutine.
 */
@Singleton
class AudioCaptureManager @Inject constructor(
    private val vad: VoiceActivityDetector,
    private val audioFocusManager: AudioFocusManager,
    private val permissionHelper: AudioPermissionHelper,
) {
    // ── Observable state ────────────────────────────────────────────────────

    /** Current capture state. */
    private val _captureState = MutableStateFlow(CaptureState.IDLE)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    /**
     * Speech-only audio chunks. Downstream consumers (e.g. Whisper engine)
     * should collect this flow.
     *
     * Uses a replay of 0 and [BufferOverflow.SUSPEND] so slow collectors
     * back-pressure the producer rather than dropping data.
     */
    private val _audioChunks = MutableSharedFlow<AudioChunk>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val audioChunks: SharedFlow<AudioChunk> = _audioChunks.asSharedFlow()

    /**
     * Per-frame RMS energy — useful for driving a waveform visualiser in
     * the UI layer. Emitted for **every** frame (speech or silence).
     */
    private val _rmsEnergy = MutableSharedFlow<Double>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val rmsEnergy: SharedFlow<Double> = _rmsEnergy.asSharedFlow()

    // ── Internals ───────────────────────────────────────────────────────────
    private var audioRecord: AudioRecord? = null
    private var captureScope: CoroutineScope? = null
    private var captureJob: Job? = null

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Begin capturing audio from the microphone.
     *
     * @throws SecurityException if [Manifest.permission.RECORD_AUDIO] was
     *   not granted.
     * @throws AudioCaptureException if the [AudioRecord] cannot be
     *   initialised or audio focus cannot be obtained.
     */
    fun startCapture() {
        if (_captureState.value == CaptureState.RECORDING) {
            Log.w(TAG, "startCapture called while already recording — ignoring")
            return
        }

        // ── Pre-conditions ──────────────────────────────────────────────
        if (!permissionHelper.hasRecordAudioPermission()) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
        if (!audioFocusManager.requestFocus()) {
            throw AudioCaptureException("Unable to obtain audio focus")
        }

        // ── Create AudioRecord ──────────────────────────────────────────
        val minBuf = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG,
            AudioConfig.AUDIO_FORMAT,
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            throw AudioCaptureException("Device does not support required audio format")
        }

        // Buffer = max(minBuf, 4× frame size) for some headroom.
        val bufferSize = maxOf(minBuf, AudioConfig.FRAME_BYTE_SIZE * 4)

        @SuppressLint("MissingPermission") // checked above
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG,
            AudioConfig.AUDIO_FORMAT,
            bufferSize,
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw AudioCaptureException("AudioRecord failed to initialise")
        }

        audioRecord = record
        vad.reset()

        // ── Launch read loop ────────────────────────────────────────────
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        captureScope = scope

        record.startRecording()
        _captureState.value = CaptureState.RECORDING

        captureJob = scope.launch {
            runCaptureLoop(record)
        }

        Log.i(TAG, "Capture started (bufferSize=$bufferSize)")
    }

    /**
     * Stop capturing and release all resources.
     * Any in-progress speech segment is flushed as a final [AudioChunk].
     */
    fun stopCapture() {
        if (_captureState.value == CaptureState.IDLE) return

        _captureState.value = CaptureState.STOPPING

        captureJob?.cancel()
        captureJob = null

        audioRecord?.let { rec ->
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord.stop() failed", e)
            }
            rec.release()
        }
        audioRecord = null

        captureScope?.cancel()
        captureScope = null

        audioFocusManager.abandonFocus()
        _captureState.value = CaptureState.IDLE

        Log.i(TAG, "Capture stopped")
    }

    /**
     * Convenience — `true` when the manager is actively recording.
     */
    val isRecording: Boolean
        get() = _captureState.value == CaptureState.RECORDING

    // ── Read loop ───────────────────────────────────────────────────────────

    /**
     * Continuously reads frames from [AudioRecord], runs each through the
     * [VoiceActivityDetector], and accumulates speech frames into an
     * [AudioChunk] that is emitted when silence resumes (or the max
     * duration is reached).
     */
    private suspend fun runCaptureLoop(record: AudioRecord) {
        val frameBuffer = ByteArray(AudioConfig.FRAME_BYTE_SIZE)
        val speechAccumulator = ByteArrayOutputStream()
        var chunkStartTimestamp = 0L
        var speechFrameCount = 0

        try {
            while (captureScope?.isActive == true) {
                // ── Audio focus gate ────────────────────────────────────
                if (!audioFocusManager.hasFocus.value) {
                    _captureState.value = CaptureState.PAUSED
                    // Spin-wait with a short sleep to avoid busy loop.
                    Thread.sleep(100)
                    continue
                }
                if (_captureState.value == CaptureState.PAUSED) {
                    _captureState.value = CaptureState.RECORDING
                }

                // ── Read one frame from the mic ─────────────────────────
                val bytesRead = record.read(
                    frameBuffer, 0, frameBuffer.size,
                )
                if (bytesRead <= 0) continue

                val frameData = frameBuffer.copyOf(bytesRead)

                // ── VAD classification ──────────────────────────────────
                val result = vad.processFrame(frameData)
                _rmsEnergy.tryEmit(result.rmsEnergy)

                if (result.isSpeech) {
                    // First speech frame → record the timestamp.
                    if (speechFrameCount == 0) {
                        chunkStartTimestamp = System.currentTimeMillis()
                    }
                    speechAccumulator.write(frameData)
                    speechFrameCount++

                    // Force-flush if the chunk exceeds the max duration.
                    val elapsed = System.currentTimeMillis() - chunkStartTimestamp
                    if (elapsed >= AudioConfig.MAX_CHUNK_DURATION_MS) {
                        emitChunk(speechAccumulator, chunkStartTimestamp, speechFrameCount)
                        speechAccumulator.reset()
                        speechFrameCount = 0
                    }
                } else if (speechFrameCount > 0 && !result.isSpeech) {
                    // Speech just ended — emit the accumulated chunk if it
                    // meets the minimum length requirement.
                    if (speechFrameCount >= AudioConfig.VAD_MIN_SPEECH_FRAMES) {
                        emitChunk(speechAccumulator, chunkStartTimestamp, speechFrameCount)
                    } else {
                        Log.d(TAG, "Discarding short burst ($speechFrameCount frames)")
                    }
                    speechAccumulator.reset()
                    speechFrameCount = 0
                }
            }

            // ── Flush any remaining speech when the loop exits ──────────
            if (speechFrameCount >= AudioConfig.VAD_MIN_SPEECH_FRAMES) {
                emitChunk(speechAccumulator, chunkStartTimestamp, speechFrameCount)
            }
        } catch (_: CancellationException) {
            // Normal shutdown — flush remaining data.
            if (speechFrameCount >= AudioConfig.VAD_MIN_SPEECH_FRAMES) {
                emitChunk(speechAccumulator, chunkStartTimestamp, speechFrameCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture loop error", e)
            _captureState.value = CaptureState.ERROR
        }
    }

    /**
     * Build an [AudioChunk] from the accumulated PCM data and emit it to
     * downstream collectors.
     */
    private suspend fun emitChunk(
        accumulator: ByteArrayOutputStream,
        startTimestamp: Long,
        frameCount: Int,
    ) {
        val pcm = accumulator.toByteArray()
        val durationMs =
            (frameCount.toLong() * AudioConfig.FRAME_DURATION_MS)

        val chunk = AudioChunk(
            pcmData = pcm,
            durationMs = durationMs,
            timestampMs = startTimestamp,
        )
        _audioChunks.emit(chunk)
        Log.d(TAG, "Emitted $chunk")
    }

    // ── Types ───────────────────────────────────────────────────────────────

    /** Possible states of the audio capture pipeline. */
    enum class CaptureState {
        /** Not recording; mic is released. */
        IDLE,

        /** Actively recording from the microphone. */
        RECORDING,

        /** Temporarily paused (e.g. audio focus lost). */
        PAUSED,

        /** Transitioning from recording → idle. */
        STOPPING,

        /** An unrecoverable error occurred. */
        ERROR,
    }

    companion object {
        private const val TAG = "AudioCaptureManager"
    }
}

/**
 * Exception thrown when the audio capture pipeline encounters a
 * configuration or hardware error.
 */
class AudioCaptureException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
