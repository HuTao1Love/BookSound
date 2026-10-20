import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The libtorrent session wrapper shared by the phone app and the server. The native libraries come
// from the platform-specific libtorrent4j artifacts each of them adds.
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
    api(project(":core"))
    api(libs.libtorrent4j)
}
