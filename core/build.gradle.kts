import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent audiobook logic: metadata parsing, MP4 tagging, library organization
// and sync contracts. Must not depend on Android so a future backend can reuse it.
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
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
