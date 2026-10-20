// Android code used by both the phone app and the watch app: the audiobook player wrapper, the
// Wear OS Data Layer plumbing they talk to each other with, and the client of the optional server.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.zyagodin.booksound.shared"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core"))
    api(project(":api"))
    api(libs.okhttp)
    api(libs.androidx.media3.common)
    api(libs.play.services.wearable)
    api(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
