import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The server's HTTP API: request and response bodies shared by the server, the phone and the watch,
// and the TypeScript declarations of the same types for the web app.
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

val tsOutput = rootProject.layout.projectDirectory.file("web/src/api/types.gen.ts")

/** Writes the API types for the web app. CI runs it and fails when the committed file differs. */
val generateTs by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates web/src/api/types.gen.ts from the API types."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.zyagodin.booksound.api.ts.TsGeneratorKt")
    args(tsOutput.asFile.absolutePath)
    outputs.file(tsOutput)
}
