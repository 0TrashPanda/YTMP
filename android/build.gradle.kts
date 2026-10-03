plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "dev.trashpanda.ytmp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.trashpanda.ytmp"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Real phones, and the x86_64 emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    androidResources {
        // The default also skips folders starting with "_", which drops the web app's _app/.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

// The on-device YTM module: the same Python code as ytm-module/ (minus its HTTP layer),
// run with Chaquopy. See docs/implementation/ytm-module.md.
val copyYtmModule by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("ytm-module/ytmp_ytm")) {
        include("*.py")
        exclude("http.py")
    }
    into(layout.buildDirectory.dir("generated/python/ytmp_ytm"))
}

// The web app is bundled in the APK, so the phone can serve it when it hosts.
val webAppAssets = layout.buildDirectory.dir("generated/webapp")
val copyWebApp by tasks.registering(Sync::class) {
    val build = rootProject.layout.projectDirectory.dir("frontend/build")
    doFirst {
        check(build.file("index.html").asFile.exists()) { "Build the web app first: cd frontend && pnpm build" }
    }
    from(build)
    into(webAppAssets.map { it.dir("web") })
}
android.sourceSets.getByName("main").assets.directories.add(webAppAssets.get().asFile.path)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyWebApp)
}

chaquopy {
    defaultConfig {
        version = "3.14"
        buildPython(providers.environmentVariable("YTMP_BUILD_PYTHON").getOrElse("python3.14"))
        pip {
            install("ytmusicapi")
            install("yt-dlp")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir(layout.buildDirectory.dir("generated/python"))
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("PythonSources") }.configureEach {
    dependsOn(copyYtmModule)
}

dependencies {
    implementation(project(":host"))
    implementation(libs.ktor.server.cio)
    // The player's own room connection (RoomFollower).
    implementation(libs.ktor.client.cio)
    // Sends the shared code's slf4j logs (host, Cast driver) to logcat.
    implementation(libs.logback.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit"))
}
