plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.mirutime.imageloader"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

// Coil only: no OkHttp engine, no Compose, no app/core/data dependency. The
// network fetcher is supplied by the caller through ImageFetchPort.
dependencies {
    implementation(libs.coil)
    implementation(libs.coil.network.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
