// JNI bridge to whisper.cpp.
//
// Deliberately thin: it owns a whisper_context and turns one float array of audio into one string.
// Everything decidable in Kotlin -- when to record, what to do with the text, how to report
// progress -- is decided there, because this side of the boundary has no exceptions to throw and
// no way to fail politely.

#include <jni.h>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "SlideAsr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define JNI_EXPORT extern "C" JNIEXPORT

namespace {

// Held open for the life of the context. whisper.cpp reads the model out of this buffer during
// init and does not reference it afterwards, but the asset is closed alongside the context anyway
// so there is exactly one thing to release.
struct Session {
    whisper_context *ctx = nullptr;
    AAsset *asset = nullptr;

    ~Session() {
        if (ctx != nullptr) {
            whisper_free(ctx);
        }
        if (asset != nullptr) {
            AAsset_close(asset);
        }
    }
};

Session *as_session(jlong handle) {
    return reinterpret_cast<Session *>(handle);
}

/** Trims the leading space whisper puts on every segment, and any stray newlines. */
std::string tidy(const std::string &text) {
    const auto first = text.find_first_not_of(" \t\n");
    if (first == std::string::npos) {
        return {};
    }
    const auto last = text.find_last_not_of(" \t\n");
    return text.substr(first, last - first + 1);
}

} // namespace

JNI_EXPORT jlong JNICALL
Java_com_slide_asr_WhisperNative_openModel(
        JNIEnv *env, jclass, jobject asset_manager, jstring asset_name, jint threads) {
    AAssetManager *manager = AAssetManager_fromJava(env, asset_manager);
    if (manager == nullptr) {
        LOGE("No asset manager");
        return 0;
    }

    const char *name = env->GetStringUTFChars(asset_name, nullptr);

    // AASSET_MODE_BUFFER memory-maps an uncompressed asset rather than reading it, so a 180MB
    // model costs no copy and no unpacked second copy on disk. It relies on the model being
    // stored uncompressed -- see androidResources.noCompress in build.gradle.kts.
    AAsset *asset = AAssetManager_open(manager, name, AASSET_MODE_BUFFER);
    if (asset == nullptr) {
        LOGE("Model asset '%s' not found", name);
        env->ReleaseStringUTFChars(asset_name, name);
        return 0;
    }

    const off_t size = AAsset_getLength(asset);
    const void *buffer = AAsset_getBuffer(asset);
    if (buffer == nullptr) {
        LOGE("Could not map '%s'; is it being compressed into the APK?", name);
        AAsset_close(asset);
        env->ReleaseStringUTFChars(asset_name, name);
        return 0;
    }

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;

    auto *session = new Session();
    session->asset = asset;
    session->ctx = whisper_init_from_buffer_with_params(
            const_cast<void *>(buffer), static_cast<size_t>(size), params);

    if (session->ctx == nullptr) {
        LOGE("whisper failed to load '%s'", name);
        env->ReleaseStringUTFChars(asset_name, name);
        delete session;
        return 0;
    }

    LOGI("Loaded '%s' (%ld bytes, %d threads)", name, static_cast<long>(size), threads);
    env->ReleaseStringUTFChars(asset_name, name);
    return reinterpret_cast<jlong>(session);
}

JNI_EXPORT void JNICALL
Java_com_slide_asr_WhisperNative_closeModel(JNIEnv *, jclass, jlong handle) {
    delete as_session(handle);
}

JNI_EXPORT jstring JNICALL
Java_com_slide_asr_WhisperNative_transcribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jint threads) {
    Session *session = as_session(handle);
    if (session == nullptr || session->ctx == nullptr) {
        return nullptr;
    }

    const jsize count = env->GetArrayLength(samples);
    jfloat *audio = env->GetFloatArrayElements(samples, nullptr);
    if (audio == nullptr) {
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = "en";
    params.translate = false;

    // Dictation is one utterance with no history: conditioning on the previous decode is what
    // makes whisper repeat itself or invent a continuation of something the user already sent.
    params.no_context = true;
    params.single_segment = false;

    // Nothing here has a console to print to, and print_realtime writes from the decode thread.
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_timestamps = true;

    // Whisper's classic failure on silence is to emit training-set boilerplate -- subtitle credits
    // and the like. Suppressing non-speech tokens and keeping the blank suppression on makes a
    // silent recording come back empty, which is the honest answer.
    params.suppress_blank = true;
    params.suppress_nst = true;

    const int status = whisper_full(session->ctx, params, audio, count);
    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT); // read-only; skip the copy back

    if (status != 0) {
        LOGE("whisper_full failed: %d", status);
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(session->ctx);
    for (int i = 0; i < segments; ++i) {
        text += whisper_full_get_segment_text(session->ctx, i);
    }

    return env->NewStringUTF(tidy(text).c_str());
}
