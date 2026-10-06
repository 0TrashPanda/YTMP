plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.chaquopy)
}

// Releases (.github/workflows/release.yml) set the version from the git tag and sign with
// the release key. Without these, it builds as 0.1.0 and a release build is unsigned.
val versionNameFromEnv: String? = providers.environmentVariable("YTMP_VERSION").orNull
val releaseKeystore: String? = providers.environmentVariable("YTMP_KEYSTORE").orNull

android {
    namespace = "dev.trashpanda.ytmp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.trashpanda.ytmp"
        minSdk = 30
        targetSdk = 36
        versionName = versionNameFromEnv ?: "0.1.0"
        // 1.2.3 -> 10203, so every release counts higher than the one before.
        versionCode = versionName!!.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .let { (it + listOf(0, 0, 0)).take(3) }
            .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
            .coerceAtLeast(1)

        ndk {
            // Real phones, and the x86_64 emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("YTMP_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("YTMP_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("YTMP_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
// Release builds also lint the assets.
tasks.matching { (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("lint", ignoreCase = true) }.configureEach {
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
