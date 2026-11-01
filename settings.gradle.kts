pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "MiruTime"
include(":app")
include(":ui-kit")
include(":core-reader-model")
include(":mirutime-core")
include(":mirutime-data")
include(":translator")
include(":core-log")
include(":image-loader")
include(":feature-tts")
include(":feature-reader")
