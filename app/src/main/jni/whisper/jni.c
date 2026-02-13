/**
 * JNI bridge between WhisperCppLib.kt and the native whisper.cpp library.
 *
 * All function names encode the fully-qualified Kotlin class path:
 *   com.example.whisper.audio.recognition.WhisperCppLib$Companion
 *
 * Based on the official whisper.android example from
 * https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android
 */

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "WhisperJNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* ── initContext ───────────────────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_com_example_whisper_audio_recognition_WhisperCppLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);

    LOGI("Loading whisper.cpp model from: %s", model_path_chars);

    context = whisper_init_from_file_with_params(
            model_path_chars, whisper_context_default_params());

    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);

    if (context == NULL) {
        LOGW("Failed to initialise whisper context");
    } else {
        LOGI("Whisper context created successfully");
    }

    return (jlong) context;
}

/* ── freeContext ───────────────────────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_com_example_whisper_audio_recognition_WhisperCppLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context != NULL) {
        whisper_free(context);
        LOGI("Whisper context freed");
    }
}

/* ── fullTranscribe ───────────────────────────────────────────────────────── */

JNIEXPORT void JNICALL
Java_com_example_whisper_audio_recognition_WhisperCppLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str, jboolean translate) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    /* Resolve language string from Kotlin.
     * "auto" → NULL (let whisper auto-detect).
     * Anything else → the ISO-639-1 language code (e.g. "en", "es"). */
    const char *lang_chars = (*env)->GetStringUTFChars(env, language_str, NULL);
    const char *lang_param = NULL;  /* NULL = auto-detect */
    if (lang_chars != NULL && strcmp(lang_chars, "auto") != 0) {
        lang_param = lang_chars;
    }

    /* Configure Whisper parameters optimised for low-latency mobile STT.
     *
     * Key speed optimizations:
     * - single_segment = true   → skip segment-boundary search (faster for short clips)
     * - no_timestamps  = true   → skip timestamp token prediction (saves ~15-20% time)
     * - print_realtime = false  → skip console printing overhead
     * - greedy strategy         → fastest decoding (no beam search)
     */
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.no_timestamps    = true;
    params.translate        = (translate == JNI_TRUE);
    params.language         = lang_param;
    params.n_threads        = num_threads;
    params.offset_ms        = 0;
    params.no_context       = true;
    params.single_segment   = true;

    whisper_reset_timings(context);

    LOGI("Running whisper_full with %d threads on %d samples, lang=%s, translate=%d",
         num_threads, audio_data_length,
         lang_param ? lang_param : "auto",
         params.translate);

    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGW("whisper_full failed");
    } else {
        whisper_print_timings(context);
    }

    (*env)->ReleaseStringUTFChars(env, language_str, lang_chars);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

/* ── getTextSegmentCount ──────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
Java_com_example_whisper_audio_recognition_WhisperCppLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

/* ── getTextSegment ───────────────────────────────────────────────────────── */

JNIEXPORT jstring JNICALL
Java_com_example_whisper_audio_recognition_WhisperCppLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    return (*env)->NewStringUTF(env, text);
}

/* ── getTextSegmentT0 ─────────────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_com_example_whisper_audio_recognition_WhisperCppLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

/* ── getTextSegmentT1 ─────────────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_com_example_whisper_audio_recognition_WhisperCppLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

/* ── getSystemInfo ────────────────────────────────────────────────────────── */

JNIEXPORT jstring JNICALL
Java_com_example_whisper_audio_recognition_WhisperCppLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    return (*env)->NewStringUTF(env, sysinfo);
}
