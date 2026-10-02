plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.trashpanda.ytmp.server.MainKt")
}

dependencies {
    implementation(project(":host"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktoml.core)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform()
}
