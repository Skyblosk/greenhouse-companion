# Greenhouse Companion - Change Log

## Version 0.4.1

### New Features

+ Added Minecraft 26.2 and 26.3 support. There is one jar for each version: 26.1.2, 26.2 and 26.3.

### Improvements

+ The layout overlay, crop highlights and see-through boxes are drawn with Parallax's renderer on every version.
    + This is Athen's 3D renderer, ported to Java, with and without the depth check.
    + It replaces the old see-through boxes, which crashed 26.3.
+ Mouse buttons and Shift/Ctrl come from Minecraft's own key constants, which 26.3 renumbered.
    + Right-clicks (Move ▸, the follow cycler and inventory counts) and the Shift ×10 / Ctrl ×100 steps work on every
      version.

### Technical Details

+ Moved the build to Stonecutter, the same layout as SkyHanni.
    + One source tree, with `//? if >= 26.2` comments where versions differ; `versions/<version>/` holds each build.
    + All the jars build into `build/libs` as `greenhouse-companion-<version>-mc<minecraft>.jar`.
+ Added client game tests (`src/gametest/`). They draw every render path and click through the whole window.

## Version 0.4.0

+ First public release, for Minecraft 26.1.2. See the [README](../README.md) for its features.
