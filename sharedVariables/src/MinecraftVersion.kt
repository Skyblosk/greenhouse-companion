package dev.ilyapple.ghc.sharedvariables

import org.gradle.jvm.toolchain.JavaLanguageVersion

/**
 * A Minecraft version the mod is built for.
 * @param versionName the Stonecutter project name and the version predicates' version (`//? if >= 26.2`)
 * @param versionNameOverride the exact game version when it differs (26.1 builds against 26.1.2)
 */
enum class MinecraftVersion(
    val versionName: String,
    val javaVersion: Int,
    val versionNameOverride: String? = null,
) {
    MC26100("26.1", 25, versionNameOverride = "26.1.2"),
    MC26200("26.2", 25),
    MC26300("26.3", 25),
    ;

    val javaLanguageVersion: JavaLanguageVersion = JavaLanguageVersion.of(javaVersion)

    /** The game version that is downloaded, built against and put in the jar's name. */
    val gameVersion: String get() = versionNameOverride ?: versionName

    /** fabric.mod.json's "minecraft" range: this version and its patch releases. */
    val fabricModJsonVersion: String get() = "~$gameVersion"
}
