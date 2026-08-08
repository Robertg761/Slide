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
            // Every Android device Slide targets has been arm64 for years, and each extra ABI is
            // another full whisper.cpp compile and another copy of the .so in the APK.
            abiFilters += "arm64-v8a"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)

    // The speech path can only be tested where the native library and a real CPU are, so its
    // tests are instrumented rather than local.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
