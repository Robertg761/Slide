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

    // The lexicon and the bigram model are already tightly packed; letting the build recompress
    // them would only cost startup time, since both are read straight out of the APK.
    androidResources {
        noCompress += "bin"
    }

    testOptions {
        // The learned-words store logs when it finds a file it cannot read, and that path is
        // exactly what its tests exercise. Without this, android.util.Log throws under JUnit and
        // the test fails for a reason that has nothing to do with what it is testing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
