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
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AirBridge"

include(":core:common")
include(":core:model")
include(":core:mvi")
include(":core:network")
include(":core:storage")
include(":core:data")
include(":core:service")
include(":domain")
include(":feature:dashboard")
include(":feature:filebrowser")
include(":feature:permissions")
include(":web")
include(":app")
