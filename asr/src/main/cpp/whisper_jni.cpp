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

#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <exception>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "whisper.h"
#include "ggml-backend.h"

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

struct CancellationToken {
    std::atomic<bool> cancelled{false};
};

/** JNI allocation helpers leave a pending Java exception when they return null. */
void clear_pending_java_exception(JNIEnv *env, const char *operation) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Java allocation failed during %s", operation);
    }
}

class UtfChars {
public:
    UtfChars(JNIEnv *env, jstring value) : env_(env), value_(value) {
        if (value_ != nullptr) {
            chars_ = env_->GetStringUTFChars(value_, nullptr);
            if (chars_ == nullptr) clear_pending_java_exception(env_, "model-name conversion");
        }
    }

    ~UtfChars() {
        if (chars_ != nullptr) {
            env_->ReleaseStringUTFChars(value_, chars_);
        }
    }

    UtfChars(const UtfChars &) = delete;
    UtfChars &operator=(const UtfChars &) = delete;

    const char *get() const { return chars_; }

private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_ = nullptr;
};

class AssetHandle {
public:
    explicit AssetHandle(AAsset *asset) : asset_(asset) {}
    ~AssetHandle() {
        if (asset_ != nullptr) AAsset_close(asset_);
    }

    AssetHandle(const AssetHandle &) = delete;
    AssetHandle &operator=(const AssetHandle &) = delete;

    AAsset *get() const { return asset_; }
    AAsset *release() {
        AAsset *asset = asset_;
        asset_ = nullptr;
        return asset;
    }

private:
    AAsset *asset_;
};

/** Wipes and discards any JNI copy on every return and C++ exception path. */
class AudioElements {
public:
    AudioElements(JNIEnv *env, jfloatArray array) : env_(env), array_(array) {
        if (array_ != nullptr) {
            count_ = env_->GetArrayLength(array_);
            data_ = env_->GetFloatArrayElements(array_, nullptr);
            if (data_ == nullptr) clear_pending_java_exception(env_, "audio-array access");
        }
    }

    ~AudioElements() {
        if (data_ != nullptr) {
            std::fill(data_, data_ + count_, 0.0F);
            env_->ReleaseFloatArrayElements(array_, data_, JNI_ABORT);
        }
    }

    AudioElements(const AudioElements &) = delete;
    AudioElements &operator=(const AudioElements &) = delete;

    jfloat *data() const { return data_; }
    jsize count() const { return count_; }

private:
    JNIEnv *env_;
    jfloatArray array_;
    jfloat *data_ = nullptr;
    jsize count_ = 0;
};

Session *as_session(jlong handle) {
    return reinterpret_cast<Session *>(handle);
}

CancellationToken *as_cancellation_token(jlong handle) {
    return reinterpret_cast<CancellationToken *>(handle);
}

bool should_abort(void *data) {
    const auto *token = static_cast<CancellationToken *>(data);
    return token != nullptr && token->cancelled.load(std::memory_order_relaxed);
}

/** Loads the best packaged ARM64 CPU backend directly through Android's native-library namespace. */
bool load_dynamic_cpu_backend() {
#if defined(GGML_BACKEND_DL)
    static std::once_flag once;
    static bool loaded = false;
    std::call_once(once, [] {
        // extractNativeLibs=false means these files can live inside base.apk. dlopen still resolves
        // their SONAMEs from the app namespace, while std::filesystem cannot enumerate that ZIP.
        constexpr const char *variants[] = {
            "libggml-cpu-android_armv8.0_1.so",
            "libggml-cpu-android_armv8.2_1.so",
            "libggml-cpu-android_armv8.2_2.so",
            "libggml-cpu-android_armv8.6_1.so",
            "libggml-cpu-android_armv9.0_1.so",
            "libggml-cpu-android_armv9.2_1.so",
            "libggml-cpu-android_armv9.2_2.so",
        };
        const char *best_variant = nullptr;
        int best_score = 0;
        for (const char *variant : variants) {
            void *handle = dlopen(variant, RTLD_NOW | RTLD_LOCAL);
            if (handle == nullptr) continue;
            const auto score = reinterpret_cast<int (*)()>(dlsym(handle, "ggml_backend_score"));
            const int value = score == nullptr ? 0 : score();
            dlclose(handle);
            if (value > best_score) {
                best_score = value;
                best_variant = variant;
            }
        }
        if (best_variant != nullptr && ggml_backend_load(best_variant) != nullptr) {
            loaded = ggml_backend_dev_count() > 0;
        }
        if (loaded) {
            LOGI("Loaded speech CPU backend %s (feature score %d)", best_variant, best_score);
        } else {
            LOGE("No compatible packaged speech CPU backend was found");
        }
    });
    return loaded;
#else
    return true;
#endif
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
    try {
        if (!load_dynamic_cpu_backend()) return 0;
        AAssetManager *manager = AAssetManager_fromJava(env, asset_manager);
        if (manager == nullptr) {
            LOGE("No asset manager");
            return 0;
        }

        UtfChars name(env, asset_name);
        if (name.get() == nullptr) {
            LOGE("No model asset name");
            return 0;
        }

        // AASSET_MODE_BUFFER memory-maps an uncompressed asset rather than expanding a second copy.
        AssetHandle asset(AAssetManager_open(manager, name.get(), AASSET_MODE_BUFFER));
        if (asset.get() == nullptr) {
            LOGE("Model asset '%s' not found", name.get());
            return 0;
        }

        const off_t size = AAsset_getLength(asset.get());
        const void *buffer = AAsset_getBuffer(asset.get());
        if (size <= 0 || buffer == nullptr) {
            LOGE("Could not map '%s'; is it being compressed into the APK?", name.get());
            return 0;
        }

        whisper_context_params params = whisper_context_default_params();
        params.use_gpu = false;
        // Flash attention is a CPU graph optimization too. It produces the same model operation
        // while avoiding the full attention matrix, which cuts memory traffic on phone CPUs.
        params.flash_attn = true;

        std::unique_ptr<Session> session(new (std::nothrow) Session());
        if (!session) {
            LOGE("Could not allocate a speech session");
            return 0;
        }
        session->asset = asset.release();
        session->ctx = whisper_init_from_buffer_with_params(
                const_cast<void *>(buffer), static_cast<size_t>(size), params);

        if (session->ctx == nullptr) {
            LOGE("whisper failed to load '%s'", name.get());
            return 0;
        }

        LOGI("Loaded '%s' (%ld bytes, %d threads)",
             name.get(), static_cast<long>(size), threads);
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::bad_alloc &) {
        LOGE("Out of memory while opening the speech model");
        return 0;
    } catch (const std::exception &error) {
        LOGE("C++ failure while opening the speech model: %s", error.what());
        return 0;
    } catch (...) {
        LOGE("Unknown C++ failure while opening the speech model");
        return 0;
    }
}

JNI_EXPORT void JNICALL
Java_com_slide_asr_WhisperNative_closeModel(JNIEnv *, jclass, jlong handle) {
    delete as_session(handle);
}

JNI_EXPORT jlong JNICALL
Java_com_slide_asr_WhisperNative_createCancellationToken(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new (std::nothrow) CancellationToken());
}

JNI_EXPORT void JNICALL
Java_com_slide_asr_WhisperNative_cancelTranscription(JNIEnv *, jclass, jlong handle) {
    CancellationToken *token = as_cancellation_token(handle);
    if (token != nullptr) {
        token->cancelled.store(true, std::memory_order_relaxed);
    }
}

JNI_EXPORT void JNICALL
Java_com_slide_asr_WhisperNative_closeCancellationToken(JNIEnv *, jclass, jlong handle) {
    delete as_cancellation_token(handle);
}

JNI_EXPORT jbyteArray JNICALL
Java_com_slide_asr_WhisperNative_transcribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jint threads,
        jlong cancellation_handle) {
    try {
        Session *session = as_session(handle);
        CancellationToken *cancellation = as_cancellation_token(cancellation_handle);
        if (session == nullptr || session->ctx == nullptr || cancellation == nullptr ||
            samples == nullptr) {
            return nullptr;
        }

        int status;
        {
            AudioElements audio(env, samples);
            if (audio.data() == nullptr) return nullptr;

            whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
            params.n_threads = threads;
            params.language = "en";
            params.translate = false;
            params.abort_callback = should_abort;
            params.abort_callback_user_data = cancellation;
            params.no_context = true;
            params.single_segment = false;
            params.print_progress = false;
            params.print_realtime = false;
            params.print_timestamps = false;
            params.no_timestamps = true;
            params.suppress_blank = true;
            params.suppress_nst = true;
            // Small improves the first-pass recognition margin without making an uncertain result
            // wait through whisper.cpp's default 26 decoder paths. Retain two independent
            // candidates at two fallback temperatures: ambiguity still gets another chance, while
            // a noisy microphone cannot leave the keyboard apparently stuck for tens of seconds.
            params.greedy.best_of = 2;
            params.temperature_inc = 0.4F;
            // audio_ctx deliberately stays at its default (the full 30-second window), although
            // bounding it to the clip length is the classic short-utterance speedup. Measured
            // against this exact model and fixture on x86-64, a bounded context misdecodes
            // "ask not" as "asked not" at every margin up to ~1250 of 1500 positions, and on a
            // 4-second slice hallucinates a clause outright. The encode-time saving is not worth
            // transcripts that read wrong.

            status = whisper_full(session->ctx, params, audio.data(), audio.count());
        } // Always wipe and discard any JNI float copy before allocating the transcript string.

        if (status != 0) {
            if (cancellation->cancelled.load(std::memory_order_relaxed)) {
                LOGI("whisper_full cancelled");
            } else {
                LOGE("whisper_full failed: %d", status);
            }
            return nullptr;
        }

        std::string text;
        const int segments = whisper_full_n_segments(session->ctx);
        for (int i = 0; i < segments; ++i) {
            const char *segment = whisper_full_get_segment_text(session->ctx, i);
            if (segment != nullptr) text += segment;
        }
        const std::string cleaned = tidy(text);
        if (cleaned.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            LOGE("Transcript is too large for a Java byte array");
            return nullptr;
        }
        const jsize length = static_cast<jsize>(cleaned.size());
        jbyteArray result = env->NewByteArray(length);
        if (result == nullptr) {
            clear_pending_java_exception(env, "transcript allocation");
            return nullptr;
        }
        if (length > 0) {
            env->SetByteArrayRegion(
                    result,
                    0,
                    length,
                    reinterpret_cast<const jbyte *>(cleaned.data()));
            if (env->ExceptionCheck()) {
                clear_pending_java_exception(env, "transcript copy");
                env->DeleteLocalRef(result);
                return nullptr;
            }
        }
        return result;
    } catch (const std::bad_alloc &) {
        LOGE("Out of memory while building the transcript");
        return nullptr;
    } catch (const std::exception &error) {
        LOGE("C++ failure while transcribing: %s", error.what());
        return nullptr;
    } catch (...) {
        LOGE("Unknown C++ failure while transcribing");
        return nullptr;
    }
}
