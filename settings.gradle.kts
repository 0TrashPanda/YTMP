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

include(":protocol", ":core", ":host", ":cast", ":server")
// Not in the server Docker build, which copies only what the server needs.
if (file("android").isDirectory) include(":android")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
