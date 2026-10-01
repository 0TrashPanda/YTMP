plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ytmp"

include(":protocol", ":core", ":server")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
