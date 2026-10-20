import com.github.gradle.node.npm.task.NpmTask

// The web app (React + Vite). Gradle downloads its own Node, so building needs no Node install;
// the built files end up in the server's jar.
plugins {
    base
    alias(libs.plugins.node.gradle)
}

node {
    version.set("22.23.3")
    download.set(true)
    // The Node.js repository is declared in settings.gradle.kts.
    distBaseUrl.set(null as String?)
    // `npm ci` once the lock file exists: exactly the locked versions.
    npmInstallCommand.set(if (file("package-lock.json").exists()) "ci" else "install")
}

val typecheck = tasks.register<NpmTask>("typecheck") {
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "typecheck"))
    inputs.dir("src")
    inputs.files("package.json", "tsconfig.json")
    outputs.upToDateWhen { true }
}

val bundle = tasks.register<NpmTask>("bundle") {
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "build"))
    inputs.dir("src")
    inputs.dir("public")
    inputs.files("package.json", "package-lock.json", "vite.config.ts", "tsconfig.json", "index.html")
    outputs.dir("dist")
}

tasks.named("build") { dependsOn(bundle) }
tasks.named("check") { dependsOn(typecheck) }
tasks.named<Delete>("clean") { delete("dist") }
