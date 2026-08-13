import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.library)
}

abstract class PackageArm64SpeechBackends : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun packageBackends() {
        val expected = listOf(
            "libggml-cpu-android_armv8.0_1.so",
            "libggml-cpu-android_armv8.2_1.so",
            "libggml-cpu-android_armv8.2_2.so",
            "libggml-cpu-android_armv8.6_1.so",
            "libggml-cpu-android_armv9.0_1.so",
            "libggml-cpu-android_armv9.2_1.so",
            "libggml-cpu-android_armv9.2_2.so",
        )
        val source = inputDirectory.get().asFile
        val actual = source.listFiles()?.filter(File::isFile)?.map(File::getName)?.sorted().orEmpty()
        require(actual == expected.sorted()) {
            "ARM64 speech backend stage is incomplete: expected ${expected.sorted()}, got $actual"
        }

        val destination = outputDirectory.get().asFile
        if (destination.exists()) {
            require(destination.deleteRecursively()) { "Could not clean $destination" }
        }
        val abiDirectory = destination.resolve("arm64-v8a")
        require(abiDirectory.mkdirs()) { "Could not create $abiDirectory" }
        expected.forEach { name -> source.resolve(name).copyTo(abiDirectory.resolve(name)) }
    }
}

android {
    namespace = "com.slide.asr"
    compileSdk = 37
    // Pin the native toolchain both for reproducible releases and cache invalidation.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Voice input is part of the app, not an optional dynamic feature. Package a native
            // implementation for every ABI Android supports at this minSdk.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // ARM64 runtime-selected ggml kernels cross shared-library ownership boundaries.
                // One shared C++ runtime keeps allocation, exceptions, and standard-library state
                // coherent across libslide_asr, whisper, ggml, and the selected CPU backend.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSLIDE_CPU_BACKEND_STAGE_DIR=${layout.buildDirectory.get().asFile}/generated/cmakeCpuBackends",
                )
                // Build the JNI entry point and its dependencies only. whisper.cpp also declares
                // an unrelated Parakeet target; asking CMake for its default `all` target would
                // package that unused recognizer whenever ARM64 runtime dispatch uses shared libs.
                targets += "slide_asr"

                // Both, not just cppFlags. Every hot kernel in ggml -- the quantised dot products,
                // the matmuls, the mel spectrogram -- is C, and the NDK's debug configuration
                // passes no -O at all, which leaves clang at its -O0 default. Optimising only the
                // C++ half made a debug build decode slower than real time by a wide margin.
                cFlags += "-O3"
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        // The model is mapped straight out of the APK by AAsset_getBuffer, which only works if the
        // packager leaves it uncompressed. Compressed, it would have to be unpacked to disk first,
        // costing a second copy of a very large file.
        noCompress += "bin"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Recorder JVM tests inject a fake backend; harmless android.util.Log calls should retain
        // their normal no-op host behavior rather than throwing from the mockable android.jar.
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val capitalized = variant.name.replaceFirstChar(Char::uppercaseChar)
        val cmakeConfiguration = if (variant.buildType == "release") "RelWithDebInfo" else "Debug"
        val packageBackends = tasks.register<PackageArm64SpeechBackends>(
            "package${capitalized}Arm64SpeechBackends",
        ) {
            dependsOn("externalNativeBuild$capitalized")
            inputDirectory.set(
                layout.buildDirectory.dir(
                    "generated/cmakeCpuBackends/$cmakeConfiguration/arm64-v8a",
                ),
            )
            outputDirectory.set(layout.buildDirectory.dir("generated/packagedJni/${variant.name}"))
        }
        requireNotNull(variant.sources.jniLibs).addGeneratedSourceDirectory(packageBackends) {
            it.outputDirectory
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)

    // Model inference itself needs a device, but recorder/session ownership is covered on the JVM
    // through injected fakes and the real native bridge retains its instrumentation suite.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
