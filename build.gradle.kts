plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

allprojects {
    // The version catalogue pins direct dependencies. Locking also pins every resolved transitive
    // module; Gradle dependency verification separately authenticates the downloaded bytes.
    dependencyLocking {
        lockAllConfigurations()
    }
}
