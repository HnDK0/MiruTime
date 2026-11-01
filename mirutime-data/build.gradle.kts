import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
}

group = providers.gradleProperty("group").getOrElse("dev.mirutime.data")
version = providers.gradleProperty("version").getOrElse("0.1.0")

kotlin {
    android {
        namespace = "dev.mirutime.data"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // LibraryDatabase exposes RoomDatabase/Builder in its public API,
            // so consumers (the app) need the runtime on their compile classpath.
            api(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // The pure chapter matcher lives in :core-reader-model. It is a JVM
        // project, so only the JVM/Android platform actuals may reference it;
        // commonMain goes through the expect declaration.
        jvmMain.dependencies {
            implementation(project(":core-reader-model"))
        }
        androidMain.dependencies {
            implementation(project(":core-reader-model"))
        }
    }
}

dependencies {
    // KSP is per-target in KMP: a shared ksp(...) dependency does not generate
    // the constructor/DAO code for both jvm and android.
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}
