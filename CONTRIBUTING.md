# Contributing

## Setup

You need **JDK 25**. Point `JAVA_HOME` at it, or set it as the project SDK in IntelliJ IDEA. Then open the folder as a
Gradle project.

The [Stonecutter Development](https://plugins.jetbrains.com/plugin/25044-stonecutter-dev) IntelliJ plugin is
optional. It highlights the version comments and switches the active version from the editor.

## Project layout

The build uses [Stonecutter](https://stonecutter.kikugie.dev/), the same layout as
[SkyHanni](https://github.com/SkyHanniStudios/SkyHanni). One source tree builds a jar for every supported Minecraft
version.

| Path | What it is |
|---|---|
| `src/main`, `src/test`, `src/gametest` | The shared source, written for the active version (26.1) |
| `versions/<version>/` | Each version's Gradle project, made by Stonecutter (only its `build/` and `run/` are there) |
| `build.gradle.kts` | The build each version runs |
| `stonecutter.gradle.kts` | The root build: the mod's version and the active Minecraft version |
| `settings.gradle.kts` | The list of Minecraft versions |
| `sharedVariables/` | `ProjectTarget` (Fabric Loader and Fabric API per version) and `MinecraftVersion` |
| `gradle/libs.versions.toml` | Loom and the other plugin and library versions |
| `buildTools/PROJECT_VERSION` | The mod's version |
| `README.md` | Features, mechanics and recent changes |

See the [README](README.md) for features, behavior and mechanics. Keep page-specific UI work in its page class.

## Version-specific code

Almost all the code is shared. Where Minecraft versions differ, Stonecutter comments choose the code. The code for
the other versions stays commented out:

```java
//? if >= 26.2 {
/*return Minecraft.getInstance().gui.screen();
*///?} else
return Minecraft.getInstance().screen;
```

Keep these differences in `dev.ilyapple.ghc.mod.compat` (`Mc`, `BoxPipelines`) when you can. The access widener
(`src/main/resources/greenhouse-companion.accesswidener`) uses the same comments with `#`.

To edit as another version, switch the active version with the plugin or with the Gradle task
`"Set active project to 26.3"`. Switch back with `"Reset active project"` before you commit, so the committed source
stays written for 26.1.

### Adding a Minecraft version

1. Add it to `MinecraftVersion` and `ProjectTarget` in `sharedVariables/src/` (with its Fabric Loader and Fabric API
   versions).
2. Add it to `versions(...)` in `settings.gradle.kts`.
3. Build it, and add `//? if` blocks for whatever doesn't compile.

## Building and testing

```bash
./gradlew build
```

This builds and unit-tests every version. On a resource-constrained laptop, use `./gradlew build --no-parallel`:
the recording replay already uses parallel workers, so running three versions of it at once can compete for resources. The jars are in `build/libs/`. For one version, run
`./gradlew :26.3:build`.

- **Unit tests:** `./gradlew :26.1:test`. The core is plain Java, and a test keeps Minecraft out of it. Some tests
  replay your own recordings and drop logs, and read them from your Minecraft folder. Put
  `minecraftDir=/path/to/instance/minecraft` in `local.properties` (never committed), or set `GHC_MINECRAFT_DIR`.
  Without it, those tests are skipped. Successful test stdout stays in the Gradle reports instead of flooding the terminal.
  Replay reports go to `versions/<version>/build/reports/replay.md` (`GHF_REPORT` overrides this).
- **Game tests:** `./gradlew :26.3:runClientGameTest` opens a client and makes a world.
    - The render test draws a depth-checked box, a see-through box, a name tag and the overlay's ghosts.
    - The GUI test clicks every element of the window and types into every text field. It writes what each one
      changed to `versions/<version>/build/run/clientGameTest/gui-report-<version>.txt`.
    - On macOS, run it under `caffeinate -d -i -u`; the client hangs if the display sleeps.
- **Dev client:** `./gradlew :26.1:runClient`. **Demo:** `./gradlew :26.1:runDemo` replays a recording into one
  greenhouse and saves a screenshot of every page to `versions/<version>/run/screenshots` (this needs `minecraftDir`).

## Releases

1. Set the version in `buildTools/PROJECT_VERSION` and update the recent changes in `README.md`.
2. Run `./gradlew build`.
3. Tag the commit `v<version>`, and attach the three jars from `build/libs/` to the GitHub release.
