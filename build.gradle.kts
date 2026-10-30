import dev.ilyapple.ghc.sharedvariables.ProjectTarget
import dev.kikugie.stonecutter.StonecutterExperimentalAPI
import net.fabricmc.loom.task.ValidateAccessWidenerTask
import java.util.Properties
import java.io.File

plugins {
    java
    id("net.fabricmc.fabric-loom")
}

val target = ProjectTarget.entries.find { it.projectPath == project.path }!!
val minecraftVersion = target.minecraftVersion

java {
    toolchain.languageVersion.set(minecraftVersion.javaLanguageVersion)
}

// Your own Minecraft folder (the one with config/ in it), for the demo and the tests that replay your recordings:
// minecraftDir=/path/to/instance/minecraft in local.properties (never committed), or GHC_MINECRAFT_DIR. Without it
// those tests are skipped.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val minecraftDir = (System.getenv("GHC_MINECRAFT_DIR") ?: localProperties.getProperty("minecraftDir", ""))
    .let { if (it.startsWith("~")) System.getProperty("user.home") + it.substring(1) else it }
val recorder = if (minecraftDir.isEmpty()) "" else "$minecraftDir/config/greenhouse-stage-recorder"
val dropLogs = if (minecraftDir.isEmpty()) "" else "$minecraftDir/config/greenhouse-companion/drops"
val testFlow = rootProject.file("src/test/resources/flows/jellybean-shadevine.json").absolutePath

@OptIn(StonecutterExperimentalAPI::class)
loom {
    // Loom reads the access widener while configuring, before Stonecutter writes this version's source, so it gets its
    // own processed copy
    accessWidenerPath = sc.process(
        rootProject.file("src/main/resources/greenhouse-companion.accesswidener"),
        "build/greenhouse-companion.accesswidener",
    )

    runs {
        named("client") {
            appendProjectPathToDisplayName = true
            runDirectory = rootProject.file("versions/${target.projectName}/run")
        }
        // ./gradlew :26.1:runDemo: the window filled from a real recording, with screenshots in versions/<v>/run/screenshots (DevDemo)
        create("demo") {
            client()
            displayName = "Demo"
            appendProjectPathToDisplayName = true
            systemProperties.put("ghc.demo", System.getenv("GHF_RECORDINGS") ?: recorder)
            systemProperties.put("ghc.demo.flow", testFlow)
            systemProperties.put("ghc.demo.quit", "true")
            // GHC_DEBUG_STREAM=1 GHC_LINGER=60: the debug stream on, and the client up a minute after the screenshots
            if (System.getenv("GHC_DEBUG_STREAM") != null) systemProperties.put("ghc.debug.stream", "true")
            System.getenv("GHC_LINGER")?.let { systemProperties.put("ghc.demo.linger", it) }
            runDirectory = rootProject.file("versions/${target.projectName}/run")
        }
        removeIf { it.name == "server" }
    }
}

// ./gradlew :26.3:runClientGameTest: src/gametest/ draws every version-specific render path and clicks through the
// whole window in a real client
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "greenhouse-companion-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

// The game tests open no folders, and fill the window from your recordings when minecraftDir is set
loom.runs.matching { it.name == "clientGameTest" }.configureEach {
    systemProperties.put("ghc.test", "true")
    systemProperties.put("ghc.test.recordings", recorder)
    systemProperties.put("ghc.test.flow", testFlow)
    System.getenv("GHC_TEST_PAGES")?.let { systemProperties.put("ghc.test.pages", it) }
    if (System.getenv("GHC_TEST_PROBE") != null) systemProperties.put("ghc.test.probe", "true")
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion.gameVersion}")
    implementation("net.fabricmc:fabric-loader:${target.fabricLoaderVersion}")
    implementation("net.fabricmc.fabric-api:fabric-api:${target.fabricApiVersion}")
    // The Hypixel Mod API mod is optional at runtime: Location only touches it when it is installed
    compileOnly(libs.hypixel.mod.api)

    testImplementation("net.fabricmc:fabric-loader-junit:${target.fabricLoaderVersion}")
}

tasks.processResources {
    val props = mapOf(
        "version" to version.toString(),
        "minecraft_dependency" to minecraftVersion.fabricModJsonVersion,
        "fabric_api_dependency" to ">=${target.fabricApiVersion}",
        "loader_dependency" to ">=${target.fabricLoaderVersion}",
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.withType<ValidateAccessWidenerTask>().configureEach {
    dependsOn("stonecutterPrepare")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = minecraftVersion.javaVersion
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // The tests read the shared files (the bundled data, src/test/resources) from the root
    workingDir = rootProject.projectDir
    // The replay reads files outside the project, so never skip it as up to date
    outputs.upToDateWhen { false }
    // The replay tests read the recorder's real files (minecraftDir above); GHF_RECORDINGS points them elsewhere
    environment("GHF_RECORDINGS", System.getenv("GHF_RECORDINGS") ?: recorder)
    environment("GHC_DROP_LOGS", System.getenv("GHC_DROP_LOGS") ?: dropLogs)
    val replayReport = rootProject.file(System.getenv("GHF_REPORT")
        ?: layout.buildDirectory.file("reports/replay.md").get().asFile.absolutePath).absolutePath
    environment("GHF_REPORT", replayReport)
    doFirst { File(replayReport).parentFile.mkdirs() }
    environment("GHF_TRACE", System.getenv("GHF_TRACE") ?: "")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// greenhouse-companion-<version>-mc<minecraft>.jar, every version's in the root build/libs
tasks.withType<Jar>().configureEach {
    archiveBaseName = "greenhouse-companion"
    archiveVersion = "$version-mc${minecraftVersion.gameVersion}"
}
tasks.jar {
    destinationDirectory = rootProject.layout.buildDirectory.dir("libs")
}
