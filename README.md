# Greenhouse Companion

This is still in testing, there may be bugs with it!

A Fabric client mod for **Hypixel SkyBlock's Garden Greenhouse** (Minecraft 26.1.2, 26.2 and 26.3). It follows the layouts and flows
you build with [SkyShards' greenhouse tools](https://greenhouse.skyshards.com) in game: it works out which flow plot and
step each of your greenhouses runs, shows what to place, remove or fix, tracks every plant's stage from how it looks,
tells you when to swap layouts, highlights what's ready to harvest and what needs water, ranks every mutation on a
simulated leaderboard, and records what your harvests drop.

It only reads what your client already sees. No accounts, no API keys, no webhooks, no telemetry
([Privacy and security](#privacy-and-security)).

> Not affiliated with Hypixel, Mojang or SkyShards. Check Hypixel's rules on allowed modifications before using any
> client mod on their server.

## Contents

- [Greenhouse Companion](#greenhouse-companion)
  - [Contents](#contents)
  - [SkyShards and this mod](#skyshards-and-this-mod)
  - [Install](#install)
  - [Features](#features)
    - [The leaderboard](#the-leaderboard)
    - [The profit tracker](#the-profit-tracker)
  - [Using it](#using-it)
  - [The window, commands and settings](#the-window-commands-and-settings)
  - [Privacy and security](#privacy-and-security)
    - [Local debug stream](#local-debug-stream)
  - [Greenhouse mechanics the mod relies on](#greenhouse-mechanics-the-mod-relies-on)
    - [Time](#time)
    - [Spawning](#spawning)
    - [Water](#water)
    - [Harvests and rare crops](#harvests-and-rare-crops)
  - [How it works](#how-it-works)
  - [Building from source](#building-from-source)
  - [Recent changes](#recent-changes)
  - [Credits and licences](#credits-and-licences)

## SkyShards and this mod

[SkyShards](https://greenhouse.skyshards.com) is a set of web tools for planning the Greenhouse:

| Tool | What it does | How this mod uses it |
|---|---|---|
| **Designer** | Lays out a 10×10 plot and makes a share link (`/designer?layout=<code>`) | Paste a Designer or share link per plot on the **Flows** page; every leaderboard entry has **Copy link** |
| **Calculator** | Finds the most spawn spots for a mutation (a CP-SAT solver) | The leaderboard's layouts started from it |
| **Simulator** | Runs plots over time: layouts, flows (steps with exits, policies, plot scripts), offline sessions, harvest intervals | Import its **Export** (flows only, `skyshards-greenhouse-flows` v1/v2) and the mod follows it step by step; its engine computes the leaderboard |
| **Flows leaderboard** | Community flows ranked from an empty start | The mod's own **Leaderboard** page ranks every mutation the same way, locally |
| **Guide** | How mutations spawn, grow and decay | The overlay's rules (spawn spots, soils, multi-cell plants) follow it |

The plant data and pictures in `assets/greenhouse-companion/skyshards/` come from SkyShards (MIT,
[LICENSE-skyshards.txt](LICENSE-skyshards.txt)).

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

## Features

- **Flows from SkyShards.** Import a Simulator export, or a Designer/share link for each plot. Every import is saved
  as its own file in `config/greenhouse-companion/flows/` (files you drop there are picked up at start).
- **Profiles.** A profile is a set of greenhouses following one flows file; each SkyBlock profile gets its own (from
  Hypixel's "Profile ID" chat line), and every profile is tracked at once.
- **Identifies each greenhouse.** Walk in and the mod matches what's planted against every step of every flow plot.
  Nothing to set up per greenhouse.
- **Layout overlay.** The current step's layout, turned the way the greenhouse is planted. Each cell that needs
  something gets a box in its action's colour, missing plants a ghost of themselves, and a label when you come close:

  | Label | Means | Colour |
  |---|---|---|
  | `+ Wild Rose` | place it here | blue |
  | `✘ Cactus → Wild Rose` | remove what's here (the layout wants the plant after the arrow) | red |
  | `⊘ Cactus → Shadevine spot` | clear a spawn spot | orange |
  | `▼ Soil: Sand or Farmland` | the ground is wrong | brown |
  | `◇ Shadevine` | an empty spawn spot (fine, no box) | yellow |
  | `✂ Devourer Root` | break it | purple |

- **Highlights.** Fully grown plants ready to harvest are outlined in green; growing crops that will run out of water
  before you're back in yellow; crops already at −100 water (they've stopped growing) in red. Each colour is a
  setting, and one toggle shows them through blocks.
- **Stages, exits and alerts.** Each plant's stage comes from its look and the shared growth clock. Every exit is
  checked live; a title, chime and chat line tell you when to swap.
- **Magic Jellybeans.** The stage floats above each one (`57 / 120`), they can be drawn one block tall, and a left click
  on one that isn't fully grown is blocked (hold **Left Alt** to break anyway).
- **Water.** Each crop's water is kept like its stage (see [mechanics](#water)). Pick how often you play (1–4 times a
  day) and crops that would dry out before then are highlighted; **Water Above Crops** writes `☔ 40` above each one.
- **Leaderboard.** Every mutation's plot ranked at harvest intervals 1–14 (see [the board](#the-leaderboard)).
- **Profit tracker.** Every greenhouse harvest and everything it dropped, with an estimated value at Bazaar or NPC
  prices, and the rare-crop rate per rarity (see [the tracker](#the-profit-tracker)). Opens from a Helianthus icon in a
  corner of your inventory, chests and chat (in the Garden).
- **Plant Diagnostics Tool (optional).** Opening Crop Diagnostics on a plant corrects what the mod worked out for it
  (exact stage, water, placed or spawned). The mod never needs it.

### The leaderboard

Every mutation's dedicated plot, simulated locally in the SkyShards engine with wiki-max stats (Farming Fortune 3,059,
Overbloom 186, Crop Growth 210) and shipped with the mod. For 3 plots (default) or 1, at any harvest interval 1–14
(online every N growth stages), sorted by coins, items or uptime:

- **Normal:** the layout on every plot full time, its inputs in stock. Profit a day = NPC coins **+ the mutations it
  makes at the Bazaar** (its own, and any others its filled cells grow beyond what it uses) **− the input mutations it
  uses at the Bazaar**, since they come out of your stock. Bazaar prices are instant-sell.
- **Ironman ▸ Profit share:** the same plot once the plots growing its inputs are counted. Each input plot j gets
  r_j = (j used per day by the plots above it) ÷ (j made per day on its own plot), stacked through nested inputs; with
  s = 1 + Σ r_j, the main plot's uptime is 1/s. Items and coins are per plot-day of the whole chain.
- **Ironman ▸ Simulated flow:** a 3-plot bootstrap flow for players starting with nothing: each plot first makes the
  inputs, deepest first, then the main step, going back when an input runs low. It's run in the engine from an empty
  inventory for 9,000 cycles (about 650 days). Each row shows its uptime beside the profit share and how much of it the
  flow loses. **Add Ironman flow** imports it.

The board uses the updated producer layouts, including their common spawners and multi-step harvests. Common
mutations use SkyShards' armor rare-crop rolls, including Fermento and Helianthus.

**Main / Alpha NPC prices.** Main is the default. The buttons on the leaderboard switch base-crop NPC prices,
reranking the entries and updating the detail values. Alpha shows the changed prices pending on Main. Both views use
the same harvest quantities, mutation inputs, rare-drop prices and Bazaar prices.

**Jellybean + Shadevine (Default).** The Jellybean icon identifies the flow bundled with a fresh installation.
It is ranked from simulations of its exact three-plot setup: two Jellybean/Shadevine plots and a third that also
produces Duskbloom. It keeps the original 16 / 16 / 8 Jellybean phase-switch rules. Normal uses stocked inputs;
Ironman starts without mutation stock. Its 1-plot numbers are one third of the complete bundle, not a standalone
layout. Jellybean output is net of the flow's own use. This mixed flow has no separate dedicated-plot profit-share
formula. **Add default flow** imports the complete setup; **Copy link** copies the first layout's public Designer link.

**What contributes to the money.** The simulations harvest fully grown base crops, so their drops and the crops
and rare items produced by mutations contribute to NPC revenue. Base-crop planting and replacement are treated as
free. Mutation items have zero NPC sale value; Normal additionally values net mutation output and inputs at Bazaar
instant-sell prices. Stocked production uses three seeds, 300 warm-up and 2,000 measured cycles. Ironman flows run for
9,000 cycles (about 656 days) at every interval, and use their second-half output for ranking.

### The profit tracker

A break counts as a harvest only once something drops from it, so placed items you take back and crops broken too
early aren't counted. Drops come from "RARE CROP!" chat lines, inventory gains (in base units: Enchanted Wheat = 160
Wheat) and `[Sacks]` reports, and each is put down to the harvests that came with it:

- a chat or inventory drop to the harvest seen from 4 client ticks before it to 8 after (the break shows a few ticks
  after its chat line), among the plants that can drop it;
- a sack report to the harvests of the 35 seconds before it, split by each plant's loot table (a Shadevine drops 78
  Cactus and 104 Sugar Cane, a Cactus 132 Cactus, so base crops harvested between Shadevines keep their own);
- an armor rare crop to any mutation, including commons, independently of its ordinary crop types. Base crops
  retain their crop-specific armor rare drops; Ethereal Vine belongs only to mutations.

The estimated value is the drops at Bazaar (instant sell) or NPC prices, minus the input mutations those harvests used
up (from the leaderboard's Normal numbers). Books are kept per session and all time
(`config/greenhouse-companion/drops/drops.json`), and every harvest and drop is logged to `drops/log-<date>.jsonl`.

## Using it

1. **Import.** In the SkyShards Simulator press **Export** (nothing ticked: flows only) and run `/ghc import`, or paste
   it on the **Flows** page and press **Import**. Or choose **Plot links** there and paste a Designer link per plot.
   The **Checks** list says what can't be followed in game (plot scripts, "something decays within", unknown plants).
   On first start the library holds **Jellybean + Shadevine**, also marked **(Default)** on the leaderboard; nothing follows it until you press **Use** on it.
2. **Walk into each greenhouse.** Once its Carpenter NPC is seen and every cell's look has settled (a few seconds), the
   mod picks the flow plot whose step matches what's planted, and counts what that step has already done, so a
   greenhouse joined half way through isn't sent back to zero. An empty greenhouse gets the first free flow plot. The
   chat line has a **[Change]** link; **Follow** on the Flow page or `/ghc follow <plot>` picks by hand.
3. **Swap when told.** When an exit holds, the mod moves to the next step and the boxes switch to the new layout. The
   HUD lists what's left ("To do: +3 place · ✘1 remove · ▼2 soil") until every plant is in place, or press
   **Swapped ✔**.

## The window, commands and settings

`/ghc` opens a window with a sidebar of pages. It's drawn on a canvas of at least 760×480 GUI units, so at a large GUI
scale the whole window scales down instead of squeezing.

| Page | What it shows |
|---|---|
| **Profile** (sidebar dropdown) | Which profile the window shows; **+ New profile**, **Rename**, **Delete** |
| **Overview** | The profile's flows, the growth clock, and a card per greenhouse (flow plot, step, time left or "swap now", Jellybeans); **Forget**, **Forget inactive**, **Move ▸** |
| **Flow** | One greenhouse: its steps, the selected step's layout or what grows now, and the step's exits ticked off live. Every amount in an exit can be typed or stepped; **Start here**, **Swapped ✔** |
| **Leaderboard** | [The board](#the-leaderboard): Ironman / Normal, Main / Alpha NPC prices, 3 plots or 1, interval 1–14, sort; **Add Ironman flow**, **Add normal flow**, **Add default flow**, **Copy link** |
| **Profit** | [The tracker](#the-profit-tracker): session / all time, by plant / all drops, Bazaar / NPC; **Reset** (asks twice) |
| **Flows** | **Flows JSON** or **Plot links**, the paste box, the library of imported files (**Use**, **Rename**, **Copy**, **Delete**), **Open folder**, the checks |
| **Inventory** | Sack counts (read when you open your sacks) plus your adjustments; click a count to type it (1.5k, 2m work) |
| **Stages** | Where stages come from and, per plant, which stages are still unknown |
| **Settings** | Every feature on or off (below) |

| Command | What it does |
|---|---|
| `/ghc` | Opens the window |
| `/ghc import [name]` | Imports flows or layout links from the clipboard and uses them |
| `/ghc identify` (or `/ghc follow auto`) | Works out again which flow plot and step this greenhouse runs |
| `/ghc follow <plot>` / `/ghc unfollow` | Picks the flow plot by hand, or stops following |
| `/ghc swapped` | Says the new layout is in place |
| `/ghc status` | Plants, flow plot, step and next tick for this greenhouse |
| `/ghc missing` | Stages no source has a look for yet |
| `/ghc flows`, `/ghc leaderboard`, `/ghc profit`, `/ghc settings`, `/ghc hud` | Open those pages; turn the HUD on or off |
| `/ghc debug stream on\|off`, `/ghc debug status` | The local debug stream ([below](#local-debug-stream)) |

Keys: **Open** (unbound) and **Break protected plants anyway** (Left Alt).

Settings (a sub-setting, shown indented, only applies while its main one is on):

| Section | Setting | Default |
|---|---|---|
| Layouts | **Layout Overlay** › identify greenhouses automatically | on › on |
| | **Labels up Close** › range | on › 4 blocks |
| Crops | **Short Jellybeans** | on |
| | **Protect Growing Crops** › Magic Jellybeans only | on › off |
| | **Protect Right Clicks** (the Plant Diagnostics Tool always works) | off |
| | **Stage Above Crops** › Magic Jellybeans only › range | on › off › 24 blocks |
| | **Water Above Crops** › range | off › 8 blocks |
| | **Highlight Fully Grown Crops** › colour | on › green |
| | **Highlight Crops That Need Water** › harvest interval › needs water › dried out | on › 2× a day (7 stages) › yellow › red |
| | **Highlights Through Blocks** (both highlights) | off |
| | **Harvest Base Crops** › (while off) protect fully grown base crops | off › on |
| Alerts | **Title when it's time to swap** › chime › chat line | on › on › on |
| | **Chime on growth tick** | on |
| Profit tracker | **Track harvests** › icon on inventory and chat screens › corner | on › on › top left |
| HUD and look | **HUD** › only in the Garden › corner; window opacity; accent | on › on › top left; 94%; emerald |

**Debug** (folded away): check the soil; overlay boxes through blocks; Devourer ring only while needed; cactus outline;
protect the stage-119 Jellybean look (off); say why a click was blocked; local debug stream (off).

## Privacy and security

- **Network:** the only requests the mod makes are to Hypixel's public, keyless endpoints
  `https://api.hypixel.net/v2/skyblock/bazaar` and `https://api.hypixel.net/v2/resources/skyblock/items`, for prices,
  at most every 15 minutes and only while the Profit page, its inventory panel or the Leaderboard is open. Nothing
  about you is sent with them. Prices are cached in `config/greenhouse-companion/prices.json`.
- **No secrets:** no API keys, tokens, webhooks, Discord or analytics anywhere in the code or the jar.
- **Reads only:** it reads blocks, entities, your inventory, sacks menus and chat that your client already has. The
  only thing it changes is your own input: Protect Growing Crops and Protect Right Clicks cancel your click on a plant
  before it's sent.
- **Files:** everything stays in `config/greenhouse-companion/`: `settings.json`, `state.json` (greenhouses, profiles,
  stages), `flows/`, `drops/`, `prices.json` and, while the debug stream is on, `debug/`. Flows file names are made
  from the flow's name with unsafe characters replaced, and a name read back from `state.json` must be a plain file
  name in `flows/`, so a shared or edited config can't point the mod at files outside that folder.
- **Imports:** SkyShards links are decoded with a size limit; a flows file is data (JSON), never run. Plot scripts in a
  flow are listed as unsupported, not executed.

### Local debug stream

Off by default. `/ghc debug stream on` starts a read-only stream of what the mod sees and decides, for following a
session from outside the game:

- it listens on `127.0.0.1:47913` only (nothing outside your machine can reach it), answers only `GET`, refuses
  requests whose `Host` isn't localhost (so web pages can't read it through DNS rebinding), and takes at most 4
  listeners;
- `GET /events`: one JSON object per line as things happen (`location`, `greenhouse`, `scan`, `look`, `tick`,
  `identify`, `diagnosed`, `blocked`, `swap`, `sacks`, `waterBar`…), a `ping` every 15 s; `?recent=N` replays the last N;
- `GET /state`: the current state; `?prints=1` adds every cell's raw fingerprint;
- every event is also written to `config/greenhouse-companion/debug/events-<time>.jsonl`.

The events include your SkyBlock profile ID and server, so keep the stream off unless you're using it.

## Greenhouse mechanics the mod relies on

From the SkyShards engine (read and run, October 2026) and measurements on Hypixel's alpha server. Hypixel changes the
Greenhouse often; these may change too.

### Time

- 3 greenhouse plots, each 10×10 cells; 2×2 and 3×3 plants are anchored at their top-left cell.
- Everything grows on one shared clock. One growth tick is a **cycle** or **stage**:
  14,400 s ÷ (1 + 0.025 × unique crops (max 10) + 0.0025 × Crop Growth + 0.001 × Speed + upgrade bonus). At wiki-max
  stats that's about 6,302 s (1.75 h), roughly 13.7 a day.
- "Harvest every N stages" means being online every N cycles; a grown plant waits in place until then.

### Spawning

- Every empty cell rolls once per tick for every mutation whose requirements it meets, not just its target, so occupy
  a cell to stop spawns there. Chance = weight ÷ max(100, Σ weights there) × (1 + mutation chance bonus).
- Requirements count the 8-cell ring around the spot (around the whole footprint for big plants). A 1×1 mutation whose
  requirements add up to 8 fills its ring: it can't sit on the edge, touch another spot of its kind, or have more than
  16 spots.
- Placed inputs decay only after their timer and their minimum mutations; Magic Jellybean, Fleshtrap and All-in Aloe
  never decay.

### Water

Measured on the alpha (27 Oct 2026) with Crop Diagnostics, which states it exactly (`Water: -30/100`):

- A crop loses a flat **20 water per stage** it grows (Water Retain ÷1.5, Improved Water Retain ÷2, Water Drain ×1.3;
  immunity cancels Drain). A skipped stage uses no water.
- A stage that would take it to −100 doesn't grow: at −100 it has stopped. About 1 in 3 plants skip one stage near
  −20 to −40, never twice.
- A Hydro Can waters a 3×3 area and shows a 16-notch bar above each crop (blue above zero, red below), which the mod
  reads while one is held.
- Cactus, Cocoa Beans, Nether Wart and Mushrooms need no water, nor do Cindershade, Zombud, Chorus Fruit, Shellfruit,
  Phantomleaf, All-in Aloe, PlantBoy Advance, Thunderling, Turtlellini and Jerryflower.
- The mod's harvest interval looks ahead 14 stages for once a day, 7 for twice, 5 for three times, 4 for four.

### Harvests and rare crops

- Armor rare crops can drop from common mutations as well as higher rarities, independently of the mutation's
  ordinary crop types. Recorded examples include Helianthus from Dustgrain and Fermento from Lonelily. The
  leaderboard uses SkyShards' mutation rare-crop rolls. Base crops retain their crop-specific armor drops.
- Spawned mutations can drop **Ethereal Vine**: 15% a harvest for a common up to 40% for a legendary (× Overbloom).

## How it works

- **Location:** the Hypixel Mod API's location packet when installed (`map` = Garden), else the sidebar.
- **Greenhouse geometry:** 96×96 plots on a 5×5 grid, the greenhouse 43 blocks in from the plot corner at y = 73,
  plots named with Hypixel's numbers. A plot counts as a greenhouse once its Carpenter NPC is seen.
- **Profiles and keys:** a greenhouse is `server#profileId|x,z` once the "Profile ID" line names the SkyBlock profile.
- **Identifying:** every step of every flow plot is scored by placements in place minus half the wrong plants; a match
  needs 60% of the layout and must beat every other plot by 0.15.
- **Overlay:** `core/overlay/PlotOverlay` (plain Java, tested) gives each layout cell a verdict; it knows the
  greenhouse's quirks (soils each plant accepts, big plants' hidden click blocks, spawns standing in as layout inputs,
  the Devourer ring rule). `mod/render/LayoutOverlay` draws it with depthless boxes.
- **Stage looks**, in order: a calibration table recorded in game (bundled), Magic Jellybean's shape (sugar cane count
  is the tier, the melon stem and head give the phase), and a model book converted from MagicAddons' crop definitions
  (59 plants, 572 stage models, big plants split per cell and per plot turn). Every stage of every plant has a look;
  range-uncertain exits are decided by their most restrictive end.
- **Water:** each crop's water is stored like its stage (a value at a stage): 0 when it spawns or is planted, set from a
  Hydro Can's bar or Crop Diagnostics, and 20 less (times its effects) for every stage it has grown since.
- **Short Jellybeans:** the sugar cane and melon stem block models are wrapped through Fabric's model loading API and
  draw nothing at hidden positions (works with Sodium); the hidden parts can't be aimed at, the blocks' hitboxes stay.
- **Protection:** a `MultiPlayerGameMode` mixin stops the dig, use and attack packets for protected plants.

## Building from source

```bash
./gradlew build
```

Needs JDK 25 (`JAVA_HOME=/path/to/jdk-25`). It builds and unit-tests every Minecraft version. The jars are
`build/libs/greenhouse-companion-<version>-mc<minecraft>.jar`. For one version, run `./gradlew :26.3:build`.
[CONTRIBUTING.md](CONTRIBUTING.md) has the build details. User-facing features, settings and mechanics are documented in this README.

- **Versions:** the build uses [Stonecutter](https://stonecutter.kikugie.dev/), laid out like
  [SkyHanni](https://github.com/SkyHanniStudios/SkyHanni). One source tree is written for 26.1, and
  `//? if >= 26.2` comments pick the code that differs between versions. Most of that code is in
  `dev.ilyapple.ghc.mod.compat`: the current screen, HUD messages, see-through render types and key types. Each
  version builds in `versions/<version>/`, with its Fabric Loader and Fabric API in `sharedVariables/`.
- **Tests:** `./gradlew :26.1:test`. The core is plain Java (a test keeps Minecraft out of it). Tests that replay your
  own recordings and drop logs read your Minecraft folder: put `minecraftDir=/path/to/instance/minecraft` in
  `local.properties` (not committed) or set `GHC_MINECRAFT_DIR`. Without it they're skipped.
- **Game tests:** `./gradlew :26.3:runClientGameTest` opens a client and makes a world (`src/gametest/`).
    - The render test draws a depth-checked box, a see-through box, a name tag and the overlay's ghosts through the
      mod's own code.
    - The GUI test opens every page of the window and clicks every element (left and right) with real input. It
      types into every text field, and checks Shift ×10 / Ctrl ×100, the profile menu, Escape and the inventory
      screen's Profit panel.
    - It writes what each element changed to `versions/<version>/build/run/clientGameTest/gui-report-<version>.txt`.
      A crash fails the run. With `minecraftDir` set, the pages are filled from your recordings.
- **Demo:** `./gradlew :26.1:runDemo` opens a dev client with a recording replayed into one greenhouse. It saves a
  screenshot of every page to `versions/<version>/run/screenshots` (it needs the recordings above).
- **Bundled data:** the leaderboard (`leaderboard/leaderboard.json`), the stage model book and the calibration table
  were made offline, the leaderboard with the SkyShards Simulator engine, and ship as JSON in
  `src/main/resources/assets/greenhouse-companion/`. The tools that made them aren't part of this repository.

## Recent changes

- **0.4.2:** refreshed producer layouts, input-chain profit shares, switch targets and Ironman simulations;
  restored common-mutation rare crops; added Main / Alpha base-crop NPC prices. Jellybean + Shadevine is the named
  default and is ranked with a Jellybean icon. Its exact mixed flow
  is simulated across all harvest intervals and both NPC-price views. Existing sample-flow names migrate while
  keeping edits and profile assignments.
  Added separate box fill and outline settings and per-plant highlight shapes; improved stage tracking,
  Crop Diagnostics water readings, and profit-tracker harvest and rare-drop matching.
- **0.4.1:** one source tree and separate jars for Minecraft 26.1.2, 26.2 and 26.3, with the shared renderer and
  version-specific input fixes.

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
