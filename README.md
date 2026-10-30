# Greenhouse Companion

This is still in testing, there may be bugs with it!

A Fabric client mod for **Hypixel SkyBlock's Garden Greenhouse** (Minecraft 26.1.2, 26.2 and 26.3). It follows the layouts and flows
you build with [SkyShards' greenhouse tools](https://greenhouse.skyshards.com) in game: it works out which flow plot and
step each of your greenhouses runs, shows what to place, remove or fix, tracks every plant's stage from how it looks,
tells you when to swap layouts, highlights what's ready to harvest and what needs water, ranks every mutation on a
simulated leaderboard, and records what your harvests drop.

It only reads what your client already sees. No accounts, no API keys, no webhooks, no telemetry
([Privacy and security](docs/USER_GUIDE.md#privacy-and-security)).

> Not affiliated with Hypixel, Mojang or SkyShards. Check Hypixel's rules on allowed modifications before using any
> client mod on their server.

## Install

1. **Fabric Loader 0.19.3+** and **Java 25**, with the jar and Fabric API for your Minecraft version:

   | Minecraft | Jar | Fabric API |
   |---|---|---|
   | 26.1.2 | `greenhouse-companion-<version>-mc26.1.2.jar` | 0.155.2+ |
   | 26.2 | `greenhouse-companion-<version>-mc26.2.jar` | 0.161.0+ |
   | 26.3 | `greenhouse-companion-<version>-mc26.3.jar` | 0.165.0+ |

2. Put that jar in your `mods` folder. Optional: the
   [Hypixel Mod API](https://github.com/HypixelDev/ModAPI) mod, for exact location (the sidebar is used without it).
3. Don't install it alongside the older `greenhouse-flow` mod (they conflict; this one replaces it).
4. In game, `/ghc` opens the window. Options → Controls → Greenhouse Companion has a key for it.

## Using it

1. **Import.** In the SkyShards Simulator press **Export** (nothing ticked: flows only) and run `/ghc import`, or paste
   it on the **Flows** page and press **Import**. Or choose **Plot links** there and paste a Designer link per plot.
   The **Checks** list says what can't be followed in game (plot scripts, "something decays within", unknown plants).
   On first start the library holds a sample flow (Hybrid Shadevine); nothing follows it until you press **Use** on it.
2. **Walk into each greenhouse.** Once its Carpenter NPC is seen and every cell's look has settled (a few seconds), the
   mod picks the flow plot whose step matches what's planted, and counts what that step has already done, so a
   greenhouse joined half way through isn't sent back to zero. An empty greenhouse gets the first free flow plot. The
   chat line has a **[Change]** link; **Follow** on the Flow page or `/ghc follow <plot>` picks by hand.
3. **Swap when told.** When an exit holds, the mod moves to the next step and the boxes switch to the new layout. The
   HUD lists what's left ("To do: +3 place · ✘1 remove · ▼2 soil") until every plant is in place, or press
   **Swapped ✔**.

## Documentation

- [User guide](docs/USER_GUIDE.md): features, window, commands, settings, privacy and local debug stream.
- [Contributing](CONTRIBUTING.md): JDK 25, builds, tests and Minecraft versions.
- [Code map](docs/ARCHITECTURE.md): where to make a change and which tests cover it.
- [Mechanics](docs/MECHANICS.md): stages, water, spawns and implementation constraints.
- [Working with Claude Code](docs/CLAUDE_WORKFLOW.md): focused tasks and lower context overhead.
- [Changelog](docs/CHANGELOG.md).

## Building from source

```sh
./gradlew build
```

Use JDK 25. This builds and unit-tests all supported Minecraft versions; jars go in `build/libs/`.
See [CONTRIBUTING.md](CONTRIBUTING.md) for targeted checks and optional recording-based tests.

## Credits and licences

Greenhouse Companion is licensed under the **GNU GPL v3.0 only** ([LICENSE](LICENSE)).

- **SkyShards** (MIT, [LICENSE-skyshards.txt](LICENSE-skyshards.txt)): plant data, crop and ground pictures, exit
  wording, and the Simulator engine the leaderboard is computed with (downloaded, not bundled).
- **MagicAddons** (GPL-3.0): the stage models in `models/magicaddons-stages.json`.
- **Skyblocker** (LGPL-3.0): greenhouse geometry, the Carpenter check, and its paste preview as a reference for the
  overlay. Own code; Skyblocker isn't needed at runtime.
- **Parallax** (BSD-3-Clause, © Starred, [LICENSE-parallax.txt](LICENSE-parallax.txt)), Athen's 3D renderer: the world
  boxes (`render/Boxes`: its render queue and box renderer) and their depth-checked and see-through pipelines for each
  Minecraft version (`compat/BoxPipelines`: its pipeline builder, depth modes and render types), ported to Java.
- **Athen:** the virtual-canvas idea (`Resolute`) behind the window's scaling.
