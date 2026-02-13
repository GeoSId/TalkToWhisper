package com.example.whisper.di

import android.content.Context
import com.example.whisper.audio.processing.AudioCaptureManager
import com.example.whisper.audio.processing.AudioFocusManager
import com.example.whisper.audio.processing.AudioPermissionHelper
import com.example.whisper.audio.processing.VoiceActivityDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that provides all audio capture & processing dependencies.
 *
 * Scoped to [SingletonComponent] because the capture pipeline must
 * survive configuration changes and be shared across the foreground
 * service and UI layer.
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideVoiceActivityDetector(): VoiceActivityDetector =
        VoiceActivityDetector()

    @Provides
    @Singleton
    fun provideAudioFocusManager(
        @ApplicationContext context: Context,
    ): AudioFocusManager = AudioFocusManager(context)

    @Provides
    @Singleton
    fun provideAudioPermissionHelper(
        @ApplicationContext context: Context,
    ): AudioPermissionHelper = AudioPermissionHelper(context)

    @Provides
    @Singleton
    fun provideAudioCaptureManager(
        vad: VoiceActivityDetector,
        audioFocusManager: AudioFocusManager,
        permissionHelper: AudioPermissionHelper,
    ): AudioCaptureManager = AudioCaptureManager(
        vad = vad,
        audioFocusManager = audioFocusManager,
        permissionHelper = permissionHelper,
    )
}
