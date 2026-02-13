package com.example.whisper.audio.processing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Android audio focus for the capture pipeline.
 *
 * When focus is lost (e.g. an incoming phone call), the manager signals
 * the [AudioCaptureManager] to pause recording. When focus is regained
 * recording resumes automatically.
 *
 * Uses [AudioFocusRequest] (API 26+) with
 * [AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY] to indicate
 * speech-related capture.
 */
@Singleton
class AudioFocusManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── Observable state ────────────────────────────────────────────────────
    private val _hasFocus = MutableStateFlow(false)

    /** Emits `true` when the app holds audio focus, `false` otherwise. */
    val hasFocus: StateFlow<Boolean> = _hasFocus.asStateFlow()

    // ── Focus request ───────────────────────────────────────────────────────
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                _hasFocus.value = true
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost (change=$focusChange)")
                _hasFocus.value = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // For recording we can't really "duck" — treat as loss.
                Log.d(TAG, "Audio focus lost transient (can duck) — pausing capture")
                _hasFocus.value = false
            }
        }
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Request audio focus. Returns `true` if focus was granted immediately.
     */
    fun requestFocus(): Boolean {
        val result = audioManager.requestAudioFocus(focusRequest)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        _hasFocus.value = granted
        Log.d(TAG, "requestFocus → granted=$granted (result=$result)")
        return granted
    }

    /**
     * Abandon audio focus. Should be called when recording stops.
     */
    fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        _hasFocus.value = false
        Log.d(TAG, "abandonFocus")
    }

    companion object {
        private const val TAG = "AudioFocusManager"
    }
}
