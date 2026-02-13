# Talk to Whisper

An Android app for **on-device speech-to-text** using [whisper.cpp](https://github.com/ggml-org/whisper.cpp). Record audio and see live transcription, with support for multiple languages and downloadable Whisper models.

**This project is for testing purposes** — to explore and verify how Whisper, the native integration, and the app flow work together. It is not intended as a production-ready product.

## What This Project Does

- **Speech recognition** — Records from the microphone and transcribes speech to text in real time using the Whisper model.
- **On-device** — Runs entirely on the device via the native whisper.cpp library (no cloud required).
- **Model management** — Download and use Whisper models (e.g. base, small) from within the app.
- **Language selection** — Choose the language for recognition.
- **Modern Android stack** — Kotlin, Jetpack Compose, Material Design 3, Hilt.

## Getting Started

### Prerequisites

- [Android Studio](https://developer.android.com/studio) (with NDK and CMake)
- Git (for the setup script)

### 1. Run the setup script first

Before building or running the app, you **must** run the Whisper setup script. It clones the whisper.cpp source into the project so the native library can be built.

From the project root:

```bash
chmod +x setup_whisper_cpp.sh
./setup_whisper_cpp.sh
```

This will:

- Clone [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (tag `v1.7.3`) into `whisper-cpp-src/`
- Make the source available for the CMake build that compiles the JNI library used by the app

If you skip this step, the Android build will fail with an error that the whisper.cpp source was not found.

### 2. Open and build in Android Studio

1. Open the project in Android Studio.
2. Sync Gradle and build the app.
3. Run on a device or emulator (ARM recommended for best performance).

### 3. Use the app

1. Grant microphone permission when prompted.
2. Download a Whisper model if needed (e.g. base or small).
3. Select the language and start recording to see live transcription.

## Project Structure (high level)

- **`setup_whisper_cpp.sh`** — One-time setup: fetches whisper.cpp source into `whisper-cpp-src/`.
- **`whisper-cpp-src/`** — whisper.cpp and ggml source (created by the setup script).
- **`app/src/main/jni/whisper/`** — JNI bindings and CMake build that compiles whisper.cpp into the app.
- **`app/src/main/java/.../`** — Kotlin app: audio capture, Whisper recognizer, model download, Compose UI.

![Screenshot_20260213_134238.png](Screenshot_20260213_134238.png)

## License

See the whisper.cpp repository for its license. This project’s app code is as per your project terms.
