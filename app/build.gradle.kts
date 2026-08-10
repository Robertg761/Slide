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
        versionCode = 7
        versionName = "0.2.0"
    }

    val signingStoreFile = providers.environmentVariable("SLIDE_SIGNING_STORE_FILE").orNull
    val signingStorePassword = providers.environmentVariable("SLIDE_SIGNING_STORE_PASSWORD").orNull
    val signingKeyAlias = providers.environmentVariable("SLIDE_SIGNING_KEY_ALIAS").orNull
    val signingKeyPassword = providers.environmentVariable("SLIDE_SIGNING_KEY_PASSWORD").orNull
    val signingStoreType = providers.environmentVariable("SLIDE_SIGNING_STORE_TYPE").orNull
    val hasReleaseSigning = listOf(
        signingStoreFile,
        signingStorePassword,
        signingKeyAlias,
        signingKeyPassword,
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                if (!signingStoreType.isNullOrBlank()) {
                    storeType = signingStoreType
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
