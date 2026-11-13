plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

// The desktop app's own host (docs/implementation/desktop-app.md#this-computers-host): the
// shared host code for this computer's music, song cache and speakers, on 127.0.0.1 only. The
// app (desktop/src-tauri) starts it with a Java runtime of its own (desktop/scripts/bundle_host.py).
application {
    mainClass.set("dev.trashpanda.ytmp.desktop.DesktopHostKt")
    applicationDefaultJvmArgs = listOf("-XX:+UseSerialGC", "-Xss512k", "-XX:TieredStopAtLevel=1", "-Xmx256m")
}

dependencies {
    implementation(project(":host"))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.logback.classic)
    implementation(libs.jaudiotagger)
    // Finding your phones on the network (PhoneCopy).
    implementation(libs.jmdns)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
