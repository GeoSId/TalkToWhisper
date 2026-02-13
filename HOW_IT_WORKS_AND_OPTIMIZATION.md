# How the App Works & Optimization Guide

This document describes how **Talk to Whisper** works end-to-end and suggests concrete ways to optimize performance, latency, and resource usage.

---

## 1. How the App Works

### 1.1 High-level flow

```
[User] → Start recording
    → Load Whisper model (if needed)
    → Start microphone capture
    → Audio frames → VAD (speech vs silence)
    → Speech chunks → Whisper.cpp (JNI)
    → Text results → UI state → Screen
```

When the user stops recording, capture stops and an **idle timeout** (2 minutes) starts; after that the model is unloaded to free memory.

### 1.2 Main components

| Component | Role |
|-----------|------|
| **WhisperViewModel** | Orchestrates model load, recording toggle, and audio chunk processing. Exposes a single `StateFlow<WhisperUiState>` for the UI. |
| **AudioCaptureManager** | Uses `AudioRecord` to read PCM frames (duration from `AudioConfig.FRAME_DURATION_MS`) at 16 kHz mono, 16-bit. Runs a loop on `Dispatchers.IO`, passes each frame to the VAD. |
| **VoiceActivityDetector (VAD)** | Energy-based: computes RMS per frame, keeps an adaptive noise floor. Emits “speech” only when RMS exceeds a threshold; speech ends after a number of consecutive silent frames. Accumulated speech frames form one **AudioChunk**. |
| **WhisperCppRecognizer** | Loads the GGML model from disk into a native `whisper_context`. Receives `AudioChunk` PCM, converts to float, calls whisper.cpp via JNI. Returns a `Flow<RecognitionResult>`. |
| **ModelStorage** | Paths for model files (e.g. `files/models/whisper/ggml-base.bin`). |
| **ModelDownloader** | Downloads the GGML model with progress; writes to a temp file then renames for atomicity. |

The UI (e.g. `TalkToWhisperScreen`) subscribes to `WhisperViewModel.uiState` and shows recording state, RMS (waveform), recognised text, partial text, errors, and model download progress.

### 1.3 Data flow in detail

1. **Microphone → chunks**  
   `AudioCaptureManager` reads frames of `AudioConfig.FRAME_BYTE_SIZE` (e.g. 10 ms at 16 kHz × 2 bytes = 320 bytes when `FRAME_DURATION_MS = 10`). Each frame is passed to `VoiceActivityDetector.processFrame()`. Consecutive “speech” frames are buffered; when silence is detected (or max chunk duration is reached), an `AudioChunk` is emitted on `audioChunks` (`SharedFlow`). Chunks are capped at `AudioConfig.MAX_CHUNK_DURATION_MS` (30 s).

2. **Chunks → recognition**  
   The ViewModel collects `audioChunks` in a coroutine and, for each chunk, calls `speechRecognizer.recognize(chunk.pcmData)`. Recognition runs on `Dispatchers.Default`. The recognizer converts PCM to float, then calls the native `whisper_full()` (see `jni.c`). Results are emitted as `RecognitionResult` (text + `isFinal`). The ViewModel appends final results to `recognizedText` and shows non-final as `partialText`.

3. **Native layer**  
   `WhisperCppLib` (Kotlin) holds a pointer to `whisper_context`. `jni.c` implements `initContext`, `freeContext`, `fullTranscribe`, and segment reading. The JNI uses `whisper_full()` with greedy decoding, `single_segment=true`, `no_timestamps=true` for low latency. The whisper.cpp and ggml sources live in `whisper-cpp-src/` (provided by `setup_whisper_cpp.sh`).

4. **Model lifecycle**  
   Model is loaded on first “start recording” if not already loaded. It stays in memory until `unloadModel()` is called (idle timeout or `onCleared`). No model is loaded at app startup.

### 1.4 Threading and concurrency

- **Audio capture**: One coroutine on `Dispatchers.IO` in a dedicated `CoroutineScope`; back-pressure via `SharedFlow` (suspend on overflow).
- **Recognition**: `recognize()` runs on `Dispatchers.Default` (`.flowOn(Dispatchers.Default)`). Chunks are processed **sequentially**: the next chunk is only processed after the previous `recognize()` flow completes.
- **UI**: State updates from the ViewModel happen on the coroutine context of the collectors (Main for `state`/`captureState`/`rmsEnergy`, and Default for the recognition path); `MutableStateFlow.update` is used to push new UI state.

---

## 2. How the App Could Be Optimised

### 2.0 Numbers to change (quick reference)

Change these constants in code to tune behaviour. File paths are under `app/src/main/java/com/example/whisper/`.

| Constant | File | Current | Suggested range / alternatives |
|----------|------|---------|----------------------------------|
| **FRAME_DURATION_MS** | `audio/processing/AudioConfig.kt` | `10` | `10`–`30`. Lower = more CPU, lower latency. `20` is a common choice. |
| **VAD_ENERGY_THRESHOLD** | `audio/processing/AudioConfig.kt` | `250.0` | `150.0`–`500.0`. Lower = more sensitive (noisy env may trigger); higher = less sensitive. |
| **VAD_SILENCE_FRAMES** | `audio/processing/AudioConfig.kt` | `30` | `15`–`50`. Frames of silence before “speech ended”. At 10 ms/frame: 30 ≈ 300 ms. Fewer = shorter chunks, more inference calls. |
| **VAD_MIN_SPEECH_FRAMES** | `audio/processing/AudioConfig.kt` | `10` | `5`–`20`. Min speech frames to emit a chunk. At 10 ms: 10 = 100 ms. Higher = ignore very short bursts. |
| **MAX_CHUNK_DURATION_MS** | `audio/processing/AudioConfig.kt` | `30_000` (30 s) | `10_000`–`30_000`. Shorter = more partial results, less memory per chunk; longer = fewer calls, more latency. |
| **SPEECH_ONSET_FRAMES** | `audio/processing/VoiceActivityDetector.kt` | `3` | `2`–`5`. Consecutive speech frames to confirm “speech started”. Higher = less false triggers. |
| **noiseAlpha** | `audio/processing/VoiceActivityDetector.kt` | `0.05` | `0.02`–`0.1`. Lower = slower noise-floor adaptation; higher = faster but noisier. |
| **noiseFactor** | `audio/processing/VoiceActivityDetector.kt` | `2.5` | `2.0`–`4.0`. Speech threshold = max(fixed, noiseFactor × noiseFloor). Higher = need louder speech. |
| **preferredThreadCount** | `audio/recognition/WhisperCpuConfig.kt` | `(cores/2).coerceIn(2, 4)` | Use fixed `2` or `3` for thermal/battery; or `.coerceIn(2, 6)` for speed on powerful devices. |
| **IDLE_TIMEOUT_MS** | `presentation/viewmodels/WhisperViewModel.kt` | `2 * 60 * 1000` (2 min) | `60_000` (1 min) to save memory; `5 * 60 * 1000` (5 min) to keep model loaded longer. |
| **MAX_AUDIO_SECONDS** | `audio/recognition/WhisperCppConfig.kt` | `30` | Leave `30` (Whisper limit). Only change if you use a different window in native code. |
| **DEFAULT_MODEL_FILENAME** | `audio/recognition/WhisperCppConfig.kt` | `"ggml-small.bin"` | `"ggml-tiny.bin"` (faster, less accurate), `"ggml-base.bin"` (balance). Must match **DEFAULT_MODEL_URL**. |
| **DEFAULT_MODEL_URL** | `audio/recognition/WhisperCppConfig.kt` | `.../ggml-small.bin` | Same filename as above. Options: `ggml-tiny.bin`, `ggml-base.bin`, `ggml-small.bin` (HuggingFace). |
| **BASE_MODEL_SIZE_BYTES** | `audio/recognition/WhisperCppConfig.kt` | `142_000_000` | Set to actual model size for progress: tiny ~75M, base ~142M, small ~466M. |
| **BUFFER_SIZE** | `modelmanager/downloader/ModelDownloader.kt` | `8 * 1024` (8 KB) | `16 * 1024`–`64 * 1024` for faster download on good networks. |
| **extraBufferCapacity** (audioChunks) | `audio/processing/AudioCaptureManager.kt` | `8` | `4`–`16`. Chunks buffered before back-pressure. Larger = more memory, smoother under load. |
| **replay** (rmsEnergy) | `audio/processing/AudioCaptureManager.kt` | `1` | Keep `1` for waveform. For less UI updates, throttle in ViewModel instead. |

**VAD note:** With `FRAME_DURATION_MS = 10`, one frame = 10 ms. So e.g. `VAD_SILENCE_FRAMES = 30` → 300 ms of silence to end a chunk; `VAD_MIN_SPEECH_FRAMES = 10` → 100 ms minimum speech length.

### 2.1 Audio and VAD

- **Frame size**  
  `AudioConfig.FRAME_DURATION_MS` (see table above; current default 10 ms). Smaller = more CPU and VAD calls; larger (e.g. 20–30 ms) = lower CPU, more latency before a chunk is emitted.

- **VAD tuning**  
  - `VAD_ENERGY_THRESHOLD`, `VAD_SILENCE_FRAMES`, `VAD_MIN_SPEECH_FRAMES` are fixed. Making them configurable (or device-dependent) could reduce false triggers and cut unnecessary chunks in noisy environments.
  - A more advanced VAD (e.g. silero-vad or a small on-device model) could improve segment boundaries and reduce work for Whisper.

- **Chunk duration**  
  Shorter max chunk (e.g. 10–15 s instead of 30 s) would give more frequent partial results and lower peak memory per chunk, at the cost of more inference calls and possible word-boundary cuts.

### 2.2 Recognition pipeline

- **Sequential processing**  
  Chunks are processed one-by-one. If the user speaks in long bursts, chunks can queue. Options:
  - Keep sequential processing but add a **bounded queue**: drop or merge the oldest chunks when the queue grows (e.g. keep only the latest N chunks) so the UI stays responsive.
  - Alternatively, run recognition in a single worker coroutine that takes chunks from a channel and processes them in order, so collection from the capture manager never blocks.

- **PCM → float conversion**  
  Done in Kotlin in `WhisperCppRecognizer.pcmToFloat()`. Moving this into the JNI (e.g. in `jni.c` before `whisper_full`) would avoid an extra copy and might allow reuse of a native buffer. Minor win but cleaner.

- **Thread count**  
  `WhisperCpuConfig.preferredThreadCount` uses `(availableProcessors() / 2).coerceIn(2, 4)`. On hot devices, reducing to 2 threads can reduce thermal throttling and sometimes improve sustained throughput. Consider making this configurable or adaptive (e.g. reduce after N runs).

- **Model choice**  
  Default is `ggml-base.bin` (~142 MB). For faster inference and lower memory:
  - Use `ggml-tiny.bin` for speed at the cost of accuracy.
  - Expose model selection in the UI (tiny / base / small) and store the chosen filename in preferences so the same model is loaded next time.

### 2.3 Memory and lifecycle

- **Idle timeout**  
  Model is unloaded after 2 minutes without recording. For “always ready” use, increase the timeout or make it configurable; for low-memory devices, decrease it or unload immediately on stop.

- **Chunk size**  
  Already capped at 30 s; no change needed unless you want shorter segments (see above).

- **Releasing native context**  
  `WhisperContext.release()` is called on unload and in `finalize()`. Ensure all recognition flows are cancelled before calling `unloadModel()` so no JNI call runs after release (current design does this via the single sequential collector).

### 2.4 UI and state

- **Batching state updates**  
  Multiple rapid updates (e.g. `recognizedText`, `partialText`, `rmsEnergy`) can be combined: e.g. collect in a short time window and emit one `WhisperUiState` update per frame or per recognition result to reduce recomposition.

- **Stable keys and derived state**  
  In Compose, use stable keys for lists (e.g. transcript segments) and `derivedStateOf` where a composable depends on a subset of `WhisperUiState` to limit recomposition scope.

- **RMS updates**  
  `rmsEnergy` updates every frame (e.g. every 10 ms when `FRAME_DURATION_MS = 10`). Throttling to ~50–100 ms in the ViewModel before updating UI would reduce recomposition without noticeably affecting the waveform animation.

### 2.5 Native (whisper.cpp / JNI)

- **Already in place**  
  The JNI uses greedy decoding, `single_segment=true`, `no_timestamps=true`, and the CMake build uses `-O3` and ARM NEON/FP16 where available. So the main low-hanging native optimisations are already applied.

- **Further native options**  
  - Experiment with `n_max_text_ctx` or other whisper params if you hit quality/speed trade-offs.
  - If you move to a quantised model (e.g. Q4), ensure the build uses the matching ggml/whisper quantisation paths.

### 2.6 Model download and storage

- **Resumable downloads**  
  `ModelDownloader` currently streams to a temp file. Adding `Range` header support would allow resuming after network drop, which helps for large models on unstable networks.

- **Integrity**  
  Optional checksum (e.g. SHA-256) after download would avoid loading a corrupted model and hard-to-debug failures.

### 2.7 Battery and thermal

- **Thread count**  
  Lower `preferredThreadCount` (e.g. 2) under thermal or power constraints.
- **Model unload**  
  Shorter idle timeout or “unload when app backgrounded” reduces long-term memory and can help with battery (fewer background threads if any).
- **Avoid running recognition when in background**  
  Already: recording is tied to the screen; stopping recording stops chunk emission and thus recognition. If you add background recording later, consider pausing recognition when the app is not in foreground.

---

## 3. Summary

- **Flow**: Mic → configurable frame size (see **2.0 Numbers to change**) → VAD → speech chunks → `recognize()` → whisper.cpp (JNI) → text → `WhisperUiState` → UI.
- **Optimisation levers**: VAD and chunk sizing, sequential vs queued chunk processing, PCM conversion location, thread count, model size, state update batching and RMS throttling, optional resumable download and checksum, and lifecycle/timeout tuning. The native pipeline is already tuned for low-latency mobile STT; further gains are mostly from model choice, concurrency design, and UI/state tuning.
