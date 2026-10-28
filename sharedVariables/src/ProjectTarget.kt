package dev.ilyapple.ghc.sharedvariables

/** One jar: a Minecraft version and the Fabric versions it is built with (a Stonecutter project, versions/<name>). */
enum class ProjectTarget(
    val projectName: String,
    val minecraftVersion: MinecraftVersion,
    val fabricLoaderVersion: String,
    val fabricApiVersion: String,
) {
    MODERN_26100(
        "26.1",
        MinecraftVersion.MC26100,
        fabricLoaderVersion = "0.19.3",
        fabricApiVersion = "0.155.2+26.1.2",
    ),
    MODERN_26200(
        "26.2",
        MinecraftVersion.MC26200,
        fabricLoaderVersion = "0.19.3",
        fabricApiVersion = "0.161.0+26.2",
    ),
    MODERN_26300(
        "26.3",
        MinecraftVersion.MC26300,
        fabricLoaderVersion = "0.19.3",
        fabricApiVersion = "0.165.0+26.3",
    ),
    ;

    val projectPath: String get() = ":$projectName"
}
