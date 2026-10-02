import java.io.File
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing.
//
// The keystore and its passwords are deliberately kept out of the repository:
// this project is public, and committing a signing key would let anyone publish
// updates to it. They live in local.properties (gitignored), and a fresh clone
// falls back to the standard Android debug key so `./gradlew assembleRelease`
// still works for anyone who just wants to build.
//
// Regenerate with:
//   keytool -genkeypair -v -keystore zeus-release.jks -alias zeus \
//           -keyalg RSA -keysize 4096 -validity 10000
val signingPropsFile = rootProject.file("local.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) signingPropsFile.inputStream().use { load(it) }
}
fun signingValue(key: String, fallback: String): String =
    signingProps.getProperty(key)
        ?: providers.gradleProperty(key).orNull
        ?: fallback

android {
    namespace = "com.xorbi.zeus"
    compileSdk = 36

    signingConfigs {
        create("release") {
            storeFile = file(signingValue("zeus.storeFile", "../debug.keystore"))
            storePassword = signingValue("zeus.storePassword", "android")
            keyAlias = signingValue("zeus.keyAlias", "androiddebugkey")
            keyPassword = signingValue("zeus.keyPassword", "android")
        }
    }

    defaultConfig {
        applicationId = "com.xorbi.zeus"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Every shipping TV is arm64. x86_64 exists only so the app can be run on an
        // emulator when one is available.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    // ggml defaults GGML_OPENMP to ON; there is no libomp on Android.
                    "-DGGML_OPENMP=OFF",
                    // Cross-compilation safety: never emit -march=native.
                    "-DGGML_NATIVE=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        debug {
            // An unoptimised ggml is unusably slow and looks like a logic bug.
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.version")
        }
    }

    ndkVersion = "28.2.13676358"
}

// ---------------------------------------------------------------------------
// Vendored whisper.cpp
//
// The source tree is not committed (it is ~30 MB and moves often). It is fetched
// once at a pinned commit by :app:fetchWhisperCpp and wired as a dependency of
// the CMake tasks so it is guaranteed to exist before CMake configures.
// ---------------------------------------------------------------------------

val whisperPin = file("src/main/cpp/whisper.cpp.pin").readText().trim()
val whisperDir = layout.projectDirectory.dir("src/main/cpp/whisper.cpp")
val whisperRepo = "https://github.com/ggml-org/whisper.cpp.git"

val fetchWhisperCpp by tasks.registering {
    group = "setup"
    description = "Clones whisper.cpp at the pinned commit into src/main/cpp/whisper.cpp"
    onlyIf { !whisperDir.asFile.resolve("CMakeLists.txt").exists() }
    doLast {
        val target = whisperDir.asFile
        val parent = target.parentFile
        parent.mkdirs()
        logger.lifecycle("Fetching whisper.cpp ${whisperPin.take(12)} -> $target")
        target.deleteRecursively()

        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git", "-C", target.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) {
                "git ${args.joinToString(" ")} failed:\n$output"
            }
        }

        git("init", "-q", ".")
        git("remote", "add", "origin", whisperRepo)
        git("fetch", "-q", "--depth", "1", "origin", whisperPin)
        git("checkout", "-q", "FETCH_HEAD")

        // Not needed to build the engine and it is the heaviest part of the repo.
        target.resolve("examples/whisper.android").takeIf { it.exists() }?.deleteRecursively()
    }
}

// ---------------------------------------------------------------------------
// Bundled model
//
// tiny.en q5_1 lives in assets so the app works offline the moment it is
// installed. It is fetched at build time rather than committed.
// ---------------------------------------------------------------------------

data class ModelSpec(
    val assetName: String,
    val url: String,
    val bytes: Long,
    val label: String,
)

val bundledModel = ModelSpec(
    assetName = "ggml-tiny.en-q5_1.bin",
    url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin",
    bytes = 32_166_155L,
    label = "tiny.en (q5_1, 31 MB)",
)

val fetchModel by tasks.registering {
    group = "setup"
    description = "Downloads ${bundledModel.label} into app/src/main/assets/models"
    val outFile = layout.projectDirectory.file("src/main/assets/models/${bundledModel.assetName}")
    val spec = bundledModel
    onlyIf { !outFile.asFile.exists() || outFile.asFile.length() != spec.bytes }
    outputs.file(outFile)
    doLast {
        outFile.asFile.parentFile.mkdirs()
        logger.lifecycle("Downloading ${spec.label} ...")

        val url = URI(spec.url).toURL()
        val partial = File(outFile.asFile.parentFile, "${outFile.asFile.name}.part")

        url.openStream().use { input ->
            partial.outputStream().buffered().use { output ->
                input.copyTo(output, 1 shl 16)
            }
        }
        if (partial.length() != spec.bytes) {
            partial.delete()
            error("Size mismatch for ${spec.assetName}: got ${partial.length()}, want ${spec.bytes}")
        }
        if (!partial.renameTo(outFile.asFile)) {
            error("Could not move ${spec.assetName} into place")
        }
    }
}

// CMake must not configure before whisper.cpp and the model are in place.
tasks.matching { it.name.contains("CMake", ignoreCase = true) }.configureEach {
    dependsOn(fetchWhisperCpp, fetchModel)
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
}