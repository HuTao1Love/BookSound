import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The optional self-hosted BookSound server: shared library, torrents converted to M4B, sync of
// listening positions between phones, watches and the web app, which it also serves.
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("com.zyagodin.booksound.server.MainKt")
    applicationName = "booksound-server"
    applicationDefaultJvmArgs = listOf("-XX:+UseSerialGC", "-XX:MaxRAMPercentage=50", "--enable-native-access=ALL-UNNAMED")
}

// Book titles are seldom ASCII, and Java names files in the locale's charset: a service started without a
// locale (systemd, a bare container) gets ASCII and can't create "Толстой/Война и мир.m4b".
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val script = unixScript
        script.writeText(
            script.readText().replaceFirst(
                "\n",
                "\ncase \"${'$'}{LC_ALL:-${'$'}{LC_CTYPE:-${'$'}LANG}}\" in *[Uu][Tt][Ff]-8*|*[Uu][Tt][Ff]8*) ;; *) export LC_ALL=C.UTF-8 ;; esac\n",
            ),
        )
    }
}

val versionName = providers.gradleProperty("versionName").orElse(providers.gradleProperty("appVersion").map { "$it-dev" })

dependencies {
    implementation(project(":core"))
    implementation(project(":api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.sse)
    implementation(libs.sqlite.jdbc)
    implementation(libs.bouncycastle.prov)
    implementation(libs.twelvemonkeys.webp)
    implementation(libs.logback.classic)
    implementation(project(":torrent"))
    // Native libtorrent for the Linux images (x86_64; there is no arm64 build) and for running on Windows.
    runtimeOnly(libs.libtorrent4j.linux)
    runtimeOnly(libs.libtorrent4j.windows)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}

/** The built web app, served from the jar's `web/` resources. */
val webDist = rootProject.layout.projectDirectory.dir("web/dist")

tasks.processResources {
    val version = versionName
    inputs.property("version", version)
    filesMatching("booksound-version.txt") { expand("version" to version.get()) }
    from(webDist) { into("web") }
    if (providers.gradleProperty("skipWeb").orNull != "true") dependsOn(":web:build")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    environment("BOOKSOUND_DATA", rootProject.layout.projectDirectory.dir("server/run-data").asFile.absolutePath)
    environment("BOOKSOUND_PORT", providers.gradleProperty("port").getOrElse("8080"))
}

// Jars come from the Gradle cache with its permissions (0600 on CI runners): in the Docker image the
// server's user couldn't read them. Everything is readable; the start scripts keep their 0755.
distributions.named("main") {
    contents {
        filePermissions { unix("rw-r--r--") }
        dirPermissions { unix("rwxr-xr-x") }
    }
}

// The release archive for LXC/bare-metal installs: bin/booksound-server, lib/*.jar.
tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
    archiveFileName.set("booksound-server.tar.gz")
}
tasks.named<Zip>("distZip") { enabled = false }
