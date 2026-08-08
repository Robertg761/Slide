plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.slide.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The lexicon is already tightly packed; letting the build recompress it would only cost
    // startup time, since it is read straight out of the APK.
    androidResources {
        noCompress += "bin"
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
