pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Scriber"

// core:  plain Kotlin rules (catalogue, hashes, resume maths, allowed hosts). No Android, no network.
// vault: encrypted storage on the phone.
// fetch: the ONLY module allowed to touch the network, and only to download models.
// app:   screens, engines (speech and language models) and the lab.
include(":core", ":vault", ":fetch", ":app")
