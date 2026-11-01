import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

group = providers.gradleProperty("group").getOrElse("dev.mirutime.translator")
version = providers.gradleProperty("version").getOrElse("0.1.0")

kotlin {
    android {
        namespace = "dev.mirutime.translator"
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
            // Root monorepo has :mirutime-core as a project. A standalone
            // translator build resolves the sibling included build by module
            // coordinates, so no version is hard-coded anywhere.
            val coreDependency: Any = if (project.findProject(":mirutime-core") != null) {
                project(":mirutime-core")
            } else {
                "dev.mirutime:mirutime-core"
            }
            api(coreDependency)
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            api(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
