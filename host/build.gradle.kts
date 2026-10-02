plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

// The host's HTTP API, room WebSocket and web app hosting, shared by the Linux server
// (Netty) and the Android app (CIO). No engine here: each side picks its own.
dependencies {
    api(project(":core"))
    api(libs.ktor.server.core)
    api(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

tasks.test {
    useJUnitPlatform()
}
