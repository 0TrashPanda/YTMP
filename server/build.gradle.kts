plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    kotlin("plugin.serialization") version "2.2.0"
    application
}

group = "dev.trashpanda.ytmp"
version = "1.0.0"
application {
    mainClass.set("dev.trashpanda.ytmp.ApplicationKt")
    
    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    implementation(projects.shared)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.3.1")
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}