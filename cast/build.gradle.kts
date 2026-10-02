plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// A small Google Cast client (Cast v2 protocol over TLS), shared by the Linux server and the
// Android app, so neither needs Google's Cast SDK.
dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    environment("CAST_HOST", System.getenv("CAST_HOST") ?: "")
    for (name in listOf("CAST_URL", "CAST_TITLE", "CAST_ARTIST", "CAST_IMAGE")) environment(name, System.getenv(name) ?: "")
    testLogging { showStandardStreams = true }
    // Real-device runs depend on the network, so never take them from the cache.
    outputs.upToDateWhen { System.getenv("CAST_HOST").isNullOrEmpty() }
}
