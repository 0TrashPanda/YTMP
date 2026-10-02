pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ytmp"

include(":protocol", ":core", ":host", ":cast", ":server", ":android")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
