package com.example.whisper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.whisper.audio.processing.AudioPermissionHelper
import com.example.whisper.presentation.components.RequestMicPermissionOnStart
import com.example.whisper.presentation.screens.home.TalkToWhisperScreen
import com.example.whisper.presentation.theme.TalkToWhisperTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity entry point for Talk to Whisper.
 *
 * Requests microphone permission when the app starts, then shows
 * [TalkToWhisperScreen] for model download, language selection, recording, and transcription.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var audioPermissionHelper: AudioPermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TalkToWhisperTheme {
                Surface(
                    modifier = Modifier.Companion.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RequestMicPermissionOnStart(permissionHelper = audioPermissionHelper) {
                        TalkToWhisperScreen()
                    }
                }
            }
        }
    }
}