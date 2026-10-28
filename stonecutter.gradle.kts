plugins {
    alias(libs.plugins.loom) apply false
    id("dev.kikugie.stonecutter")
}

allprojects {
    group = "dev.ilyapple"

    /** The mod's version, for every Minecraft version's jar: buildTools/PROJECT_VERSION. */
    version = providers.fileContents(rootProject.layout.projectDirectory.file("buildTools/PROJECT_VERSION")).asText.map { it.trim() }.get()

    repositories {
        mavenCentral()

        // Hypixel Mod API (compile only; the Location class uses it when the mod is installed)
        exclusiveContent {
            forRepository {
                maven("https://repo.hypixel.net/repository/Hypixel/")
            }
            filter {
                includeGroup("net.hypixel")
            }
        }
    }
}

// The version the source is written for; "Set active project to 26.3" (Stonecutter) switches it for the IDE
stonecutter active "26.1"
