package com.example.whisper.di

import android.content.Context
import com.example.whisper.audio.recognition.SpeechRecognizer
import com.example.whisper.audio.recognition.WhisperCppRecognizer
import com.example.whisper.modelmanager.downloader.ModelDownloader
import com.example.whisper.modelmanager.storage.ModelStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

/**
 * Hilt module that provides whisper.cpp speech recognition and model
 * management dependencies.
 *
 * This project is intentionally trimmed down to a single STT engine:
 * **whisper.cpp via JNI** ([WhisperCppRecognizer]).
 */
@Module
@InstallIn(SingletonComponent::class)
object SpeechRecognitionModule {

    // ── Model storage & downloader ──────────────────────────────────────────

    @Provides
    @Singleton
    fun provideModelStorage(
        @ApplicationContext context: Context,
    ): ModelStorage = ModelStorage(context)

    /**
     * Dedicated [HttpClient] for model downloads.
     *
     * Uses OkHttp engine (already a project dependency via Ktor) with
     * generous timeouts suitable for large file downloads.
     */
    @Provides
    @Singleton
    fun provideModelDownloaderHttpClient(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                followRedirects(true)
                connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    @Provides
    @Singleton
    fun provideModelDownloader(
        httpClient: HttpClient,
        modelStorage: ModelStorage,
    ): ModelDownloader = ModelDownloader(
        httpClient = httpClient,
        modelStorage = modelStorage,
    )

    // ── Speech recognizer (engine-selected) ─────────────────────────────────

    /**
     * Provide the single [SpeechRecognizer] implementation: whisper.cpp.
     */
    @Provides
    @Singleton
    fun provideSpeechRecognizer(
        modelStorage: ModelStorage,
    ): SpeechRecognizer = WhisperCppRecognizer(modelStorage = modelStorage)
}
