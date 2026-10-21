pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Node.js for building the web app (see web/build.gradle.kts).
        ivy {
            name = "Node.js"
            setUrl("https://nodejs.org/dist/")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
    }
}

rootProject.name = "BookSound"
include(":core")
include(":api")
include(":torrent")
include(":server")
include(":web")

// `-PserverOnly=true` builds just the server and the web app, without the Android SDK (LXC and Docker
// builds from source).
if (providers.gradleProperty("serverOnly").orNull != "true") {
    include(":app")
    include(":shared")
    include(":wear")
}
