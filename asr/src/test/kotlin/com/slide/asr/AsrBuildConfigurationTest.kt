package com.slide.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrBuildConfigurationTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
        it.parentFile
    }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun nativeLibraryIsPackagedForEveryAndroidAbi() {
        val build = File(root, "asr/build.gradle.kts").readText()

        listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64").forEach { abi ->
            assertTrue("Missing $abi", build.contains("\"$abi\""))
        }
    }

    @Test
    fun armBuildHasNoOptionalInstructionFloor() {
        val build = File(root, "asr/build.gradle.kts").readText()
        val cmake = File(root, "asr/src/main/cpp/CMakeLists.txt").readText()
        val configuredArch = Regex("""set\(GGML_CPU_ARM_ARCH\s+\"([^\"]*)\"""")
            .find(cmake)
            ?.groupValues
            ?.get(1)

        assertEquals("", configuredArch)
        assertTrue(cmake.contains("ANDROID_ABI STREQUAL \"arm64-v8a\""))
        assertTrue(cmake.contains("set(GGML_CPU_ALL_VARIANTS  ON"))
        assertTrue(cmake.contains("set(GGML_BACKEND_DL        ON"))
        assertTrue(build.contains("addGeneratedSourceDirectory(packageBackends)"))
        assertTrue(build.contains("dependsOn(\"externalNativeBuild\$capitalized\")"))
        assertTrue(cmake.contains("ggml-cpu-android_armv8.0_1"))
        assertTrue(cmake.contains("ggml-cpu-android_armv9.2_2"))
    }

    @Test
    fun arm64RuntimeSelectsACompatibleCpuBackend() {
        val bridge = File(root, "asr/src/main/cpp/whisper_jni.cpp").readText()

        assertTrue(bridge.contains("dlopen(variant, RTLD_NOW | RTLD_LOCAL)"))
        assertTrue(bridge.contains("dlsym(handle, \"ggml_backend_score\")"))
        assertTrue(bridge.contains("ggml_backend_load(best_variant)"))
        assertTrue(bridge.contains("ggml_backend_dev_count() > 0"))
        assertTrue(bridge.contains("if (!load_dynamic_cpu_backend()) return 0"))
    }

    @Test
    fun uncertainDecodeRetainsBoundedAccuracyFallbacks() {
        val bridge = File(root, "asr/src/main/cpp/whisper_jni.cpp").readText()

        assertTrue(bridge.contains("params.greedy.best_of = 2"))
        assertTrue(bridge.contains("params.temperature_inc = 0.4F"))
        assertTrue(bridge.contains("params.flash_attn = true"))
    }

    @Test
    fun speechRecognitionHasNoNetworkOrPlatformRecognizerPath() {
        val cmake = File(root, "asr/src/main/cpp/CMakeLists.txt").readText()
        val manifest = File(root, "asr/src/main/AndroidManifest.xml").readText()
        val kotlinSources = File(root, "asr/src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertTrue(cmake.contains("set(WHISPER_CURL           OFF"))
        assertTrue(!manifest.contains("android.permission.INTERNET"))
        assertTrue(!kotlinSources.contains("android.speech.SpeechRecognizer"))
        assertTrue(!kotlinSources.contains("java.net."))
    }

    @Test
    fun nativeDecodeUsesWhisperAbortCallback() {
        val bridge = File(root, "asr/src/main/cpp/whisper_jni.cpp").readText()

        assertTrue(bridge.contains("params.abort_callback = should_abort"))
        assertTrue(bridge.contains("std::atomic<bool> cancelled"))
    }

    @Test
    fun nativeBridgeContainsAllocationBoundariesAndPcmRaii() {
        val bridge = File(root, "asr/src/main/cpp/whisper_jni.cpp").readText()

        assertTrue(bridge.contains("new (std::nothrow) Session"))
        assertTrue(bridge.contains("catch (const std::bad_alloc &"))
        assertTrue(bridge.contains("class AudioElements"))
        assertTrue(bridge.contains("ReleaseFloatArrayElements(array_, data_, JNI_ABORT)"))
        assertTrue(bridge.contains("if (name.get() == nullptr)"))
        assertTrue(bridge.contains("env->ExceptionCheck()"))
        assertTrue(bridge.contains("env->ExceptionClear()"))
        assertTrue(bridge.contains("env->NewByteArray(length)"))
        assertTrue(bridge.contains("env->SetByteArrayRegion("))
        assertTrue("ordinary UTF-8 must never enter JNI's MUTF-8 API", !bridge.contains("NewStringUTF"))
    }

    @Test
    fun nativeBuildUsesTrackedWhisperProvenance() {
        val cmake = File(root, "asr/src/main/cpp/CMakeLists.txt").readText()
        val ggml = File(root, "third_party/whisper.cpp/ggml/CMakeLists.txt").readText()
        val vendored = File(root, "third_party/whisper.cpp/VENDORED_COMMIT").readText().trim()

        assertTrue(vendored.matches(Regex("[0-9a-f]{40}")))
        assertTrue(cmake.contains("file(READ \"\${WHISPER_ROOT}/VENDORED_COMMIT\""))
        assertTrue(cmake.contains("GGML_BUILD_COMMIT_OVERRIDE"))
        assertTrue(ggml.contains("if(GGML_BUILD_COMMIT_OVERRIDE)"))
    }

    @Test
    fun transcriberOwnsAndReleasesItsMutexOffMain() {
        val transcriber =
            File(root, "asr/src/main/kotlin/com/slide/asr/WhisperTranscriber.kt").readText()

        assertTrue(
            transcriber.contains(
                "suspend fun load(model: WhisperModel): Boolean = withContext(dispatcher)",
            ),
        )
        assertTrue(transcriber.contains("withContext(NonCancellable + dispatcher)"))
        assertTrue(transcriber.contains("mutex.withLock { releaseLocked() }"))
    }
}
