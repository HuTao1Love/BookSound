import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent audiobook logic: metadata parsing, MP4 tagging, library organization
// and sync contracts. Must not depend on Android so a future backend can reuse it.
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    `java-test-fixtures`
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
    testImplementation(libs.junit)
}

// The demo library of the README's screenshots (tools/screenshots): silent books of real lengths, tagged by
// the app's own writer. ./gradlew :core:demoLibrary -PdemoOut=<dir> -PdemoCovers=<dir of covers.py's covers>
tasks.register<JavaExec>("demoLibrary") {
    group = "documentation"
    description = "Writes the screenshots' demo library."
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("com.zyagodin.booksound.core.DemoLibraryKt")
    args(
        providers.gradleProperty("demoOut").getOrElse(layout.buildDirectory.dir("demo-library").get().asFile.path),
        providers.gradleProperty("demoCovers").getOrElse("build/demo-covers"),
    )
}
