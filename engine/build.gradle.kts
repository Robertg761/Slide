plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.slide.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The models are already tightly packed; letting the build recompress them only costs startup
    // time, since they are copied out of the APK for memory-mapped inference.
    androidResources {
        noCompress += listOf("bin", "pte")
    }

    testOptions {
        // The lexicon, the bigram model and the held-out sentences are read straight off disk by
        // the tests rather than through resources, so Gradle cannot see that they are inputs.
        // Without this, regenerating an asset leaves every test result cached and green, which is
        // the most misleading possible outcome for a change whose whole point is to move them.
        unitTests.all {
            it.inputs.dir(layout.projectDirectory.dir("src/main/assets"))
            it.inputs.dir(layout.projectDirectory.dir("src/test/resources"))
            listOf(
                "slide.realSwipeDataset",
                "slide.realSwipeLimit",
                "slide.typingQualityOutput",
            ).forEach { property ->
                System.getProperty(property)?.let { value -> it.systemProperty(property, value) }
            }
        }

        // The learned-words store logs when it finds a file it cannot read, and that path is
        // exactly what its tests exercise. Without this, android.util.Log throws under JUnit and
        // the test fails for a reason that has nothing to do with what it is testing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(files(rootProject.layout.projectDirectory.file("third_party/executorch/executorch-android-1.2.0-slide.aar")))
    runtimeOnly(libs.fbjni)
    runtimeOnly(libs.nativeloader)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
