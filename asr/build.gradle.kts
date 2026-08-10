plugins {
    alias(libs.plugins.android.library)
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
                arguments += listOf("-DANDROID_STL=c++_static")

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
