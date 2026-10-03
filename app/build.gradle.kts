import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
}

// Optional Google Books API key for online cover search: put googleBooksApiKey=... into
// local.properties (not committed). Without a key, Google Books is skipped.
// `java.util.Properties` can't be written out here: `java` resolves to the project's `java`
// extension, not the package.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

/** An environment variable (CI) or, on your PC, a local.properties entry. */
fun secret(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProperties.getProperty(property)?.takeIf { it.isNotBlank() }

// Release signing. CI passes BOOKSOUND_KEYSTORE* variables (see .github/workflows/release.yml);
// locally put signing.storeFile/storePassword/keyAlias/keyPassword into local.properties.
// Without them the release APK is built unsigned (it can't be installed).
val releaseKeystore = secret("BOOKSOUND_KEYSTORE", "signing.storeFile")

// CI passes the release version and run number; local builds use appVersion (gradle.properties) and 1.
val appVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
val appVersionName = providers.gradleProperty("versionName").orNull
    ?: providers.gradleProperty("appVersion").orNull
    ?: "1.0"

android {
    namespace = "com.zyagodin.booksound"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zyagodin.booksound"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GOOGLE_BOOKS_API_KEY", "\"${secret("GOOGLE_BOOKS_API_KEY", "googleBooksApiKey").orEmpty().trim()}\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = secret("BOOKSOUND_KEYSTORE_PASSWORD", "signing.storePassword")
                keyAlias = secret("BOOKSOUND_KEY_ALIAS", "signing.keyAlias")
                keyPassword = secret("BOOKSOUND_KEY_PASSWORD", "signing.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.layout)
    implementation(libs.androidx.compose.adaptive.navigation3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    // BitTorrent engine (libtorrent via SWIG) with native libraries for ARM devices and x86_64 emulators.
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.android.arm)
    implementation(libs.libtorrent4j.android.arm64)
    implementation(libs.libtorrent4j.android.amd64)
    implementation(libs.okhttp)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    "ksp"(libs.androidx.room.compiler)
}
