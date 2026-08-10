plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.slide.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.slide"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.3.1"
    }

    buildTypes {
        release {
            // Release signing is deliberately outside Gradle. CI builds this unsigned artifact in
            // an unprivileged job; a separate approval-gated job signs it without checking out or
            // executing repository code while the keystore is available.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources {
        // Library noCompress declarations do not propagate into the final application APK.
        // Whisper maps its model directly, while swipe inference copies already-packed models to
        // mmap-friendly storage; the application therefore owns both packaging rules.
        noCompress += listOf("bin", "pte")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(project(":ime"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
