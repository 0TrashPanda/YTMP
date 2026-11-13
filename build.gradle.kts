plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.chaquopy) apply false
}

// Test classes run side by side, each in its own JVM (one per two cores): the speaker tests
// (Chromecast, Sonos, MPD, zones) wait in real time, ~2 min one after another.
subprojects {
    tasks.withType<Test>().configureEach {
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    }
}
