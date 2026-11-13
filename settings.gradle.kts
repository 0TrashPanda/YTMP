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
// The desktop app's own host (docs/implementation/desktop-app.md); not in the server Docker build either.
if (file("desktop/host").isDirectory) {
    include(":desktop-host")
    project(":desktop-host").projectDir = file("desktop/host")
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
