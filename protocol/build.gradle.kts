plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

// Generates the TypeScript types for the frontend from the Kotlin message classes.
val generateTs by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates frontend/src/lib/protocol.gen.ts from the protocol classes"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.trashpanda.ytmp.protocol.tsgen.TsGeneratorKt")
    val out = rootProject.layout.projectDirectory.file("frontend/src/lib/protocol.gen.ts")
    args(out.asFile.absolutePath)
    outputs.file(out)
}

tasks.test {
    useJUnitPlatform()
}
