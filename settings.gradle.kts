pluginManagement {
    includeBuild("sharedVariables")
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        // Stonecutter
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.ilyapple.ghc.shared-variables")
    id("dev.kikugie.stonecutter") version "0.9.7"
}

rootProject.name = "greenhouse-companion"
// build.gradle.kts is each Minecraft version's build; the root's is stonecutter.gradle.kts
rootProject.buildFileName = "root.gradle.kts"

// One jar per Minecraft version (versions/<version>); the code says what differs with //? if >= 26.2 comments.
// vcsVersion is the one the committed source is written for.
stonecutter {
    create(rootProject) {
        versions("26.1", "26.2", "26.3")
        vcsVersion = "26.1"
    }
}
