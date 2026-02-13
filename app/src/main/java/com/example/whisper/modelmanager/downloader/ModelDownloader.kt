package com.example.whisper.modelmanager.downloader

import android.util.Log
import com.example.whisper.audio.recognition.WhisperCppConfig
import com.example.whisper.modelmanager.storage.ModelStorage
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads AI model files (Whisper STT, LLM brains, TTS voices) from
 * remote URLs and writes them to local storage managed by [ModelStorage].
 *
 * ### Features
 * - Streaming download with progress reporting via [Flow].
 * - Atomic writes: the file is written to a `.tmp` file first, then
 *   renamed to the final filename on success — this prevents partial
 *   files from being picked up by the recogniser.
 * - Cancellation-safe: cancelling the collecting coroutine will abort
 *   the download and clean up the temporary file.
 *
 * ### Usage
 * ```kotlin
 * modelDownloader.downloadWhisperModel().collect { state ->
 *     when (state) {
 *         is DownloadState.Downloading -> updateProgress(state.progress)
 *         is DownloadState.Completed   -> onModelReady()
 *         is DownloadState.Failed      -> showError(state.error)
 *         else -> { }
 *     }
 * }
 * ```
 */
@Singleton
class ModelDownloader @Inject constructor(
    private val httpClient: HttpClient,
    private val modelStorage: ModelStorage,
) {
    // ── Observable download state (for UI binding) ──────────────────────────

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Download the whisper.cpp GGML model file.
     */
    fun downloadWhisperModel(
        modelUrl: String = WhisperCppConfig.DEFAULT_MODEL_URL,
    ): Flow<DownloadState> = channelFlow {
        if (modelStorage.isWhisperCppModelDownloaded()) {
            Log.i(TAG, "GGML model already on disk — skipping download")
            val state = DownloadState.Completed(modelStorage.getWhisperCppModelFile())
            _downloadState.value = state
            send(state)
            return@channelFlow
        }

        val modelFile = modelStorage.getWhisperCppModelFile()
        downloadFileInternal(modelUrl, modelFile)
    }.flowOn(Dispatchers.IO)

    /**
     * Download any model file to a specified location.
     *
     * @param url       The remote URL of the model file.
     * @param targetFile The local file to write to.
     * @return A [Flow] of [DownloadState] events.
     */
    fun downloadFile(
        url: String,
        targetFile: File,
    ): Flow<DownloadState> = channelFlow {
        downloadFileInternal(url, targetFile)
    }.flowOn(Dispatchers.IO)

    /**
     * Internal download logic shared by [downloadWhisperModel] and
     * [downloadFile].  Uses [ProducerScope.send] which is safe to call
     * from any coroutine context (unlike `flow { emit() }`).
     */
    private suspend fun ProducerScope<DownloadState>.downloadFileInternal(
        url: String,
        targetFile: File,
    ) {
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")

        try {
            val starting = DownloadState.Starting
            _downloadState.value = starting
            send(starting)

            Log.i(TAG, "Downloading $url → ${targetFile.absolutePath}")

            httpClient.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    val error = DownloadState.Failed(
                        RuntimeException("HTTP ${response.status.value}: ${response.status.description}")
                    )
                    _downloadState.value = error
                    send(error)
                    return@execute
                }

                val totalBytes = response.contentLength() ?: -1L
                var downloadedBytes = 0L

                val channel = response.bodyAsChannel()
                val buffer = ByteArray(BUFFER_SIZE)

                tmpFile.outputStream().use { output ->
                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead <= 0) continue

                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val progress = if (totalBytes > 0) {
                            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                        } else {
                            -1f  // indeterminate
                        }

                        val state = DownloadState.Downloading(
                            bytesDownloaded = downloadedBytes,
                            totalBytes = totalBytes,
                            progress = progress,
                        )
                        _downloadState.value = state
                        send(state)
                    }
                }
            }

            // Atomic rename: tmp → final
            if (tmpFile.exists()) {
                targetFile.delete()
                if (tmpFile.renameTo(targetFile)) {
                    Log.i(TAG, "Download complete: ${targetFile.absolutePath} " +
                            "(${targetFile.length() / 1_000_000} MB)")
                    val state = DownloadState.Completed(targetFile)
                    _downloadState.value = state
                    send(state)
                } else {
                    throw RuntimeException("Failed to rename tmp file to ${targetFile.name}")
                }
            } else {
                throw RuntimeException("Download produced no data")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            tmpFile.delete()  // Clean up partial file
            val state = DownloadState.Failed(e)
            _downloadState.value = state
            send(state)
        }
    }

    /**
     * Cancel any in-progress download by resetting state.
     * The actual cancellation happens when the collecting coroutine is
     * cancelled.
     */
    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val BUFFER_SIZE = 8 * 1024  // 8 KB read buffer
    }
}

// ── Download state ──────────────────────────────────────────────────────────

/**
 * Represents the current state of a model download operation.
 */
sealed interface DownloadState {

    /** No download in progress. */
    data object Idle : DownloadState

    /** Download is initialising (HTTP request sent). */
    data object Starting : DownloadState

    /**
     * Download is actively receiving data.
     *
     * @property bytesDownloaded Bytes received so far.
     * @property totalBytes Total expected bytes, or `-1` if unknown.
     * @property progress Progress in `[0.0, 1.0]`, or `-1` if indeterminate.
     */
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progress: Float,
    ) : DownloadState

    /**
     * Download completed successfully.
     *
     * @property file The local file containing the downloaded model.
     */
    data class Completed(val file: File) : DownloadState

    /**
     * Download failed.
     *
     * @property error The exception that caused the failure.
     */
    data class Failed(val error: Throwable) : DownloadState
}
