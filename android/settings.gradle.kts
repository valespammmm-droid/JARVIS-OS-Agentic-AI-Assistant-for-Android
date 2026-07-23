pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // TensorFlow Maven repository for TFLite dependencies
        maven("https://storage.googleapis.com/tensorflow.org/maven/")
    }
}

rootProject.name = "JARVIS OS"
include(":app")
include(":core:network")
include(":core:database")
include(":core:speech")
include(":core:background")
include(":core:ui")
include(":feature:assistant")
include(":feature:skills")
