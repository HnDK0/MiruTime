import java.util.Properties

plugins {
    // AGP 9.4 has built-in Kotlin support: org.jetbrains.kotlin.android must
    // NOT be applied (it errors). Kotlin version still mirrored from core via
    // the version catalog for the compiler used under the hood.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}


// Release signing is supplied by CI/localPropertiesFilePath. Plain local
// builds stay unsigned rather than silently inventing a key.
val signingProperties = Properties().apply {
    val path = providers.gradleProperty("localPropertiesFilePath").orNull
    if (path != null) {
        val file = file(path)
        if (file.isFile) file.inputStream().use(::load)
    }
}

// The only number to bump on a release: MAJOR.MINOR.PATCH, optionally with a
// suffix ("1.0.0-rc1"). versionCode is derived from it, so the two can never
// disagree; a prerelease keeps the code of its final build, which only matters
// for sideloaded builds (GitHub releases, never Play).
val appVersionName = "1.0.0"

// MAJOR*10000 + MINOR*100 + PATCH: 1.0.0 -> 10000, 1.2.3 -> 10203.
val appVersionCode = appVersionName.substringBefore('-').split('.').let { parts ->
    fun part(index: Int) = parts.getOrNull(index).orEmpty().takeWhile(Char::isDigit).toIntOrNull() ?: 0
    part(0) * 10_000 + part(1) * 100 + part(2)
}

android {
    // Mirrored from MiruTime-Core android block (namespace differs: app).
    namespace = "dev.mirutime.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mirutime.app"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Short commit + build type are shown in About so a tester can name
        // the exact build. Release CI has a clean checkout; local builds too.
        val gitCommit = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim().ifBlank { "dev" }
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
    }

    buildFeatures {
        compose = true
        // Debug app labels differ from release; build-type resValue needs this.
        resValues = true
        // The real update check compares the GitHub tag with this app's
        // version; the About screen uses the same value at runtime.
        buildConfig = true
        // Isolated PDF renderer: AIDL is off by default in AGP 9.
        aidl = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        val store = signingProperties.getProperty("storeFile")
        if (!store.isNullOrBlank()) {
            create("release") {
                storeFile = file(store)
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Two builds coexist on one device: the debug package and the
            // release package must not replace each other.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "MiruTime Debug")
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // R8 path MUST be exercised: consumer-rules.pro ships in the AAR.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Per-ABI splits are opt-in through -PabiSplits=true. The flag is
    // variant-global: it applies to every variant in that invocation. CI
    // passes it only on the release build and ships the arm64 split plus the
    // universal APK (all four ABIs, for x86_64 and 32-bit-only targets).
    // Plain local and debug builds stay universal (one 4-ABI APK).
    splits {
        abi {
            isEnable = providers.gradleProperty("abiSplits")
                .map(String::toBoolean)
                .getOrElse(false)
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    // Per-app language must work after install: a Play language split would
    // otherwise strip the Russian resources on an English device.
    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }}

dependencies {
    implementation(project(":ui-kit"))
    implementation(project(":core-log"))
    implementation(project(":image-loader"))
    implementation(project(":feature-tts"))
    implementation(project(":feature-reader"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.sqlite.bundled)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    // ProcessLifecycleOwner: the TTS overlay reacts to the whole app leaving the
    // foreground, not to one Activity stopping.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.palette)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // App unit tests exercise the real bundled SQLite driver; the Android
    // variant shipped by the app cannot load its native library on the JVM.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.sqlite.bundled)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Monorepo project dependencies (was JitPack until the migration).
    implementation(project(":core-reader-model"))
    implementation(project(":mirutime-core"))
    implementation(project(":mirutime-data"))
    implementation(project(":translator"))
}
