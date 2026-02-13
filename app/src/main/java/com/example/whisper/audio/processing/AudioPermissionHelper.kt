package com.example.whisper.audio.processing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility for checking audio-related runtime permissions before
 * starting the capture pipeline.
 *
 * Does **not** request permissions itself — that is the responsibility
 * of the UI layer (e.g. Accompanist Permissions in Compose).
 */
@Singleton
class AudioPermissionHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * `true` when [Manifest.permission.RECORD_AUDIO] has been granted.
     */
    fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * `true` when [Manifest.permission.POST_NOTIFICATIONS] has been
     * granted (Android 13+). On older versions this always returns `true`
     * because no runtime permission is needed.
     */
    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * `true` when **all** permissions required to start background
     * audio capture are granted (record audio + notifications on API 33+).
     */
    fun hasAllRequiredPermissions(): Boolean =
        hasRecordAudioPermission() && hasNotificationPermission()

    companion object {
        /**
         * List of runtime permissions that the UI should request before
         * starting capture.
         */
        val requiredPermissions: List<String> = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
