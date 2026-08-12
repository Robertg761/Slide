import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.security.MessageDigest

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
        targetSdk = 37
        versionCode = 11
        versionName = "0.3.2"
    }

    buildTypes {
        release {
            // Release signing is deliberately outside Gradle. CI builds this unsigned artifact in
            // an unprivileged job; a separate approval-gated job signs it without checking out or
            // executing repository code while the keystore is available.
            isMinifyEnabled = true
            isShrinkResources = true
            ndk.debugSymbolLevel = "SYMBOL_TABLE"
            // The tagged release workflow attests the source revision externally. AGP's embedded
            // VCS record differs between a Git checkout and an otherwise identical source export
            // (`NO_SUPPORTED_VCS_FOUND`), defeating the independent byte-for-byte rebuild.
            vcsInfo {
                include = false
            }
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

tasks.register("writeReleaseRuntimeArtifacts") {
    group = "verification"
    description = "Writes the exact external artifacts selected for the app release runtime"

    val runtimeClasspath = configurations.named("releaseRuntimeClasspath")
    val externalArtifacts = runtimeClasspath.get().incoming.artifactView {
        componentFilter { component -> component !is ProjectComponentIdentifier }
    }.artifacts
    val outputFile = layout.buildDirectory.file("reports/release-runtime-artifacts.tsv")
    inputs.files(externalArtifacts.artifactFiles).withPropertyName("releaseRuntimeClasspath")
    outputs.file(outputFile)

    doLast {
        val repositoryRoot = rootProject.projectDir.toPath().toAbsolutePath().normalize()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        fun requireSafeField(value: String): String {
            require('\t' !in value && '\r' !in value && '\n' !in value) {
                "Runtime artifact field contains a TSV control character: $value"
            }
            return value
        }

        val rows = externalArtifacts.artifacts.mapNotNull { artifact ->
            val component = artifact.id.componentIdentifier
            val file = artifact.file
            when (component) {
                is ProjectComponentIdentifier -> null
                is ModuleComponentIdentifier -> {
                    require(file.isFile) { "Resolved runtime artifact is not a file: $file" }
                    listOf(
                        "maven",
                        component.group,
                        component.module,
                        component.version,
                        file.absolutePath,
                        sha256(file),
                    ).joinToString("\t", transform = ::requireSafeField)
                }
                else -> {
                    require(file.isFile) { "Resolved local runtime artifact is not a file: $file" }
                    val absolute = file.toPath().toAbsolutePath().normalize()
                    require(absolute.startsWith(repositoryRoot)) {
                        "Local runtime artifact must be inside the repository: $file"
                    }
                    listOf(
                        "file",
                        "-",
                        "-",
                        "-",
                        repositoryRoot.relativize(absolute).toString().replace(File.separatorChar, '/'),
                        sha256(file),
                    ).joinToString("\t", transform = ::requireSafeField)
                }
            }
        }.distinct().sorted()
        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(
            (listOf("kind\tgroup\tname\tversion\tpath\tsha256") + rows).joinToString(
                separator = "\n",
                postfix = "\n",
            ),
            Charsets.UTF_8,
        )
        logger.lifecycle("Wrote ${rows.size} resolved release runtime artifacts to $destination")
    }
}
