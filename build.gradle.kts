// Top-level build file for the throwaway spike. No shared logic.
// NOTE: AGP 9 ships built-in Kotlin support (kotl.in/gradle/agp-built-in-kotlin):
// no org.jetbrains.kotlin.* plugin is applied in this project.
// AGP 9.4 defaults to KGP 2.2.x, but mirutime-core (in-repo module) builds
// with Kotlin 2.4.20 (metadata 2.4.0) — bump the built-in compiler to match
// (per AGP 9.0 release notes: buildscript classpath on kotlin-gradle-plugin).
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    // Library modules apply these; registered here so the version is known to
    // the classpath (the buildscript bump above puts the same KGP jar there).
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room3) apply false
}
