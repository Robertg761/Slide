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
        val cmake = File(root, "asr/src/main/cpp/CMakeLists.txt").readText()
        val configuredArch = Regex("""set\(GGML_CPU_ARM_ARCH\s+\"([^\"]*)\"""")
            .find(cmake)
            ?.groupValues
            ?.get(1)

        assertEquals("", configuredArch)
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
