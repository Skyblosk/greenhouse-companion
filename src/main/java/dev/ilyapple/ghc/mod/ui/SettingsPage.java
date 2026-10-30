package dev.ilyapple.ghc.mod.ui;

import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import dev.ilyapple.ghc.mod.GreenhouseCompanion;
import dev.ilyapple.ghc.mod.Settings;

/** Page content and its local view state; the screen owns input and shared controls. */
final class SettingsPage {
	private final CompanionScreen screen;
	private int settingsScroll;
	private boolean debugOpen;

	SettingsPage(CompanionScreen screen) {
		this.screen = screen;
	}

	void scroll(int delta) {
		settingsScroll = Math.max(0, settingsScroll + delta * 20);
	}

	// Settings

	private record Flag(java.util.function.BooleanSupplier read, Consumer<Boolean> write) {
		boolean get() { return read.getAsBoolean(); }
		void set(boolean value) { write.accept(value); }
	}

	void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Settings s = screen.settings();
		int x = screen.contentX();
		int w = Math.min(screen.contentW(), 460);
		int top = screen.contentTop();
		int bottom = screen.height - Theme.PAD;
		int y = top - settingsScroll;
		g.enableScissor(x, top, x + screen.contentW(), bottom);
		String bypass = GreenhouseCompanion.bypassKey().getTranslatedKeyMessage().getString();

		y = group(g, "Layouts", x, y, w);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Layout Overlay", "A box in its action's colour on every cell that needs something done, ghosts of missing plants, turned the way you planted",
				flag(() -> s.layoutHologram, value -> s.layoutHologram = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, true, "Identify greenhouses automatically", "The flow plot, step and turn from what grows in a greenhouse you walk into",
				flag(() -> s.autoIdentify, value -> s.autoIdentify = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Labels up Close", "+ place, ✘ remove, ⊘ clear a spot, ▼ soil, ◇ spawn spot, ✂ root, each in its own colour",
				flag(() -> s.hologramLabels, value -> s.hologramLabels = value));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.hologramLabels, "Range", s.overlayLabelRange + " blocks",
				() -> s.overlayLabelRange = Math.max(2, s.overlayLabelRange - 1), () -> s.overlayLabelRange = Math.min(32, s.overlayLabelRange + 1));

		y = group(g, "Crops", x, y + 6, w);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Short Jellybeans", "Draws each Jellybean as its bottom block; hitboxes stay as they are",
				flag(() -> s.shrinkJellybeans, value -> s.shrinkJellybeans = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Protect Growing Crops", "Left clicks on a crop that isn't fully grown do nothing; hold " + bypass + " to break anyway",
				flag(() -> s.protectGrowing, value -> s.protectGrowing = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.protectGrowing, "Magic Jellybeans only", "",
				flag(() -> s.protectJellybeansOnly, value -> s.protectJellybeansOnly = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Protect Right Clicks", "Right clicks too; the Plant Diagnostics Tool always works",
				flag(() -> s.protectRightClicks, value -> s.protectRightClicks = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Stage Above Crops", "12 / 15: white exact, amber a range, red a look not on record, ✔ fully grown",
				flag(() -> s.stageLabels, value -> s.stageLabels = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.stageLabels, "Magic Jellybeans only", "",
				flag(() -> s.stageLabelsJellybeansOnly, value -> s.stageLabelsJellybeansOnly = value));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.stageLabels, "Range", s.labelRange + " blocks",
				() -> s.labelRange = Math.max(4, s.labelRange - 4), () -> s.labelRange = Math.min(64, s.labelRange + 4));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Water Above Crops", "☔ 40 above each growing crop that uses water, in its highlight colour; ? until a Hydro Can or Crop Diagnostics reads it",
				flag(() -> s.waterAboveCrops, value -> {
					s.waterAboveCrops = value;
					dev.ilyapple.ghc.mod.WaterHelper.refresh();
				}));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.waterAboveCrops, "Range", s.waterLabelRange + " blocks",
				() -> s.waterLabelRange = Math.max(2, s.waterLabelRange - 2), () -> s.waterLabelRange = Math.min(64, s.waterLabelRange + 2));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Highlight Fully Grown Crops", "What's ready to harvest, outlined from the soil up",
				flag(() -> s.highlightHarvestable, value -> s.highlightHarvestable = value));
		y = subColorRow(g, mouseX, mouseY, x, y, w, s.highlightHarvestable, "Colour", () -> s.grownColor, color -> s.grownColor = color);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Highlight Crops That Need Water", "Outlines a crop that would reach −100 water before you're back, and one already at −100 (it stops growing)",
				flag(() -> s.highlightWater, value -> {
					s.highlightWater = value;
					dev.ilyapple.ghc.mod.WaterHelper.refresh();
				}));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.highlightWater, "Harvest interval", s.waterIntervalLabel(),
				() -> {
					s.waterVisitsPerDay = s.waterVisitsPerDay <= 1 ? 4 : s.waterVisitsPerDay - 1;
					dev.ilyapple.ghc.mod.WaterHelper.refresh();
				},
				() -> {
					s.waterVisitsPerDay = s.waterVisitsPerDay >= 4 ? 1 : s.waterVisitsPerDay + 1;
					dev.ilyapple.ghc.mod.WaterHelper.refresh();
				});
		y = subColorRow(g, mouseX, mouseY, x, y, w, s.highlightWater, "Needs water", () -> s.needsWaterColor, color -> {
			s.needsWaterColor = color;
			dev.ilyapple.ghc.mod.WaterHelper.refresh();
		});
		y = subColorRow(g, mouseX, mouseY, x, y, w, s.highlightWater, "Dried out (−100)", () -> s.driedColor, color -> {
			s.driedColor = color;
			dev.ilyapple.ghc.mod.WaterHelper.refresh();
		});
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Box Fill", "Filled faces for layout, harvest and water boxes",
				flag(() -> s.boxFill, value -> s.boxFill = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Box Outline", "Edges for layout, harvest and water boxes; both off hides the boxes",
				flag(() -> s.boxOutline, value -> s.boxOutline = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Highlights Through Blocks", "Both highlights seen through cactus, canes and walls",
				flag(() -> s.highlightThroughBlocks, value -> s.highlightThroughBlocks = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Harvest Base Crops", "Off: Wheat, Cactus, Wild Rose and the other base crops are left to grow: no highlight, not counted as ready, no stage label",
				flag(() -> s.harvestBaseCrops, value -> s.harvestBaseCrops = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, !s.harvestBaseCrops, "Protect fully grown base crops", "While base crops are left to grow, they can't be broken by accident either",
				flag(() -> s.protectGrownBaseCrops, value -> s.protectGrownBaseCrops = value));

		y = group(g, "Alerts", x, y + 6, w);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Title when it's time to swap", "", flag(() -> s.alertTitle, value -> s.alertTitle = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.alertTitle, "Chime", "", flag(() -> s.alertSound, value -> s.alertSound = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.alertTitle, "Chat line", "With an [Open] link", flag(() -> s.alertChat, value -> s.alertChat = value));
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Chime on growth tick", "", flag(() -> s.tickChime, value -> s.tickChime = value));

		y = group(g, "Profit tracker", x, y + 6, w);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "Track harvests", "Every greenhouse harvest that dropped something, and what it dropped, on the Profit page",
				flag(() -> s.recordDrops, value -> s.recordDrops = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.recordDrops, "Icon on inventory and chat screens", "A Helianthus in the corner opens the tracker; Garden only",
				flag(() -> s.dropsIcon, value -> s.dropsIcon = value));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.recordDrops && s.dropsIcon, "Corner", s.dropsIconCorner.name().toLowerCase(Locale.ROOT).replace('_', ' '),
				() -> s.dropsIconCorner = Settings.Corner.values()[Math.floorMod(s.dropsIconCorner.ordinal() - 1, Settings.Corner.values().length)],
				() -> s.dropsIconCorner = Settings.Corner.values()[Math.floorMod(s.dropsIconCorner.ordinal() + 1, Settings.Corner.values().length)]);

		y = group(g, "HUD and look", x, y + 6, w);
		y = toggleRow(g, mouseX, mouseY, x, y, w, "HUD", "Greenhouse, step, what to do and the next tick", flag(() -> s.hud, value -> s.hud = value));
		y = subToggleRow(g, mouseX, mouseY, x, y, w, s.hud, "Only in the Garden", "", flag(() -> s.hudOnlyInGarden, value -> s.hudOnlyInGarden = value));
		y = subStepperRow(g, mouseX, mouseY, x, y, w, s.hud, "Corner", s.hudCorner.name().toLowerCase(Locale.ROOT).replace('_', ' '),
				() -> s.hudCorner = Settings.Corner.values()[Math.floorMod(s.hudCorner.ordinal() - 1, Settings.Corner.values().length)],
				() -> s.hudCorner = Settings.Corner.values()[Math.floorMod(s.hudCorner.ordinal() + 1, Settings.Corner.values().length)]);
		y = stepperRow(g, mouseX, mouseY, x, y, w, "Window opacity", s.menuOpacity + "%", () -> s.menuOpacity = Math.max(40, s.menuOpacity - 10), () -> s.menuOpacity = Math.min(100, s.menuOpacity + 10));
		// Accent swatches
		g.text(screen.font(), "Accent", x + 8, y + 6, Theme.TEXT, false);
		int sx = x + w - 8;
		Settings.Accent[] accents = Settings.Accent.values();
		for (int i = accents.length - 1; i >= 0; i--) {
			Settings.Accent accent = accents[i];
			sx -= 16;
			boolean selected = s.accent == accent;
			Theme.rounded(g, sx, y + 4, 12, 12, accent.color);
			if (selected) Theme.border(g, sx - 2, y + 2, 16, 16, Theme.TEXT_BRIGHT);
			screen.addHotspot(new CompanionScreen.Hotspot(sx, y + 4, 12, 12, event -> {
				s.accent = accent;
				s.save();
			}));
		}
		y += Theme.ROW_H + 4;

		// Debug: everything else, folded away
		int headY = y + 6;
		Theme.heading(g, screen.font(), (debugOpen ? "▾ " : "▸ ") + "Debug", x, headY + 4, w);
		if (headY + 14 > top) screen.addHotspot(new CompanionScreen.Hotspot(x, Math.max(headY, top), w, 14, event -> debugOpen = !debugOpen));
		y = headY + 14;
		if (debugOpen) {
			y = toggleRow(g, mouseX, mouseY, x, y, w, "Check the soil", "A box and ghost soil where the ground isn't one the plant grows on (Shadevine: sand or farmland)",
					flag(() -> s.hologramGround, value -> s.hologramGround = value));
			y = toggleRow(g, mouseX, mouseY, x, y, w, "Overlay boxes through blocks", "The layout overlay's boxes drawn through cactus and canes",
					flag(() -> s.overlaySeeThrough, value -> s.overlaySeeThrough = value));
			y = toggleRow(g, mouseX, mouseY, x, y, w, "Devourer: ring only while needed", "Only asks for a Puffercloud/Zombud while a spot it touches is empty; other cells get Nether Wart (soul sand) or Cocoa Beans (farmland)",
					flag(() -> s.devourerRingOnlyWhenNeeded, value -> s.devourerRingOnlyWhenNeeded = value));

			y = toggleRow(g, mouseX, mouseY, x, y, w, "Protect the stage-119 Jellybean look", "Placed Jellybeans look like 119; ones the mod saw you place are never protected",
					flag(() -> s.protectPlacedLook, value -> s.protectPlacedLook = value));
			y = toggleRow(g, mouseX, mouseY, x, y, w, "Say why a click was blocked", "A line above the hotbar",
					flag(() -> s.protectionMessage, value -> s.protectionMessage = value));
			y = toggleRow(g, mouseX, mouseY, x, y, w, "Local debug stream",
					"Read-only, this machine only: http://127.0.0.1:" + s.debugPort + "/events and /state, for following the mod live",
					flag(() -> s.debugStream, value -> {
						s.debugStream = value;
						dev.ilyapple.ghc.mod.DebugStream.apply(s);
					}));
		}
		y = screen.wrap(g, "Keys (Options → Controls → Greenhouse Companion): open this window " + GreenhouseCompanion.openKey().getTranslatedKeyMessage().getString()
				+ ", break anyway " + bypass + ". Or type /ghc.", x + 8, y + 4, w - 16, Theme.MUTED);
		g.disableScissor();
		int total = y + settingsScroll - top;
		settingsScroll = Math.max(0, Math.min(settingsScroll, Math.max(0, total - (bottom - top) + 8)));
	}

	/** A setting under the one above it, indented; greyed out and inactive while {@code enabled} is false. */
	private int subToggleRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, boolean enabled, String label, String description, Flag flag) {
		int indent = 16;
		int h = description.isEmpty() ? 18 : 26;
		boolean over = enabled && mouseX >= x + indent && mouseX < x + w && mouseY >= y && mouseY < y + h && mouseY >= screen.contentTop();
		if (over) Theme.rounded(g, x + indent, y, w - indent, h, Theme.HOVER);
		g.fill(x + 10, y, x + 11, y + h / 2 + 1, Theme.BORDER_STRONG);
		g.fill(x + 10, y + h / 2, x + indent, y + h / 2 + 1, Theme.BORDER_STRONG);
		g.text(screen.font(), Theme.fit(screen.font(), label, w - indent - 50), x + indent + 6, y + 5, enabled ? Theme.TEXT : Theme.FAINT, false);
		if (!description.isEmpty()) Theme.small(g, screen.font(), Theme.fit(screen.font(), description, (w - indent - 50) * 2), x + indent + 6, y + 15, enabled ? Theme.MUTED : Theme.FAINT);
		Theme.toggle(g, x + w - 28, y + (h - 10) / 2, enabled && flag.get(), over);
		if (enabled && y + h > screen.contentTop()) screen.addHotspot(new CompanionScreen.Hotspot(x + indent, Math.max(y, screen.contentTop()), w - indent, h, event -> {
			flag.set(!flag.get());
			screen.settings().save();
		}));
		return y + h + 2;
	}

	private int subStepperRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, boolean enabled, String label, String value, Runnable down, Runnable up) {
		int indent = 16;
		g.fill(x + 10, y, x + 11, y + 11, Theme.BORDER_STRONG);
		g.fill(x + 10, y + 10, x + indent, y + 11, Theme.BORDER_STRONG);
		g.text(screen.font(), label, x + indent + 6, y + 6, enabled ? Theme.TEXT : Theme.FAINT, false);
		int vx = x + w - 8 - 16;
		if (enabled) screen.stepper(g, mouseX, mouseY, "+", vx, y + 3, event -> {
			up.run();
			screen.settings().save();
		});
		vx -= screen.font().width(value) + 8;
		g.text(screen.font(), value, vx + 4, y + 6, enabled ? Theme.accent() : Theme.FAINT, false);
		if (enabled) screen.stepper(g, mouseX, mouseY, "−", vx - 16, y + 3, event -> {
			down.run();
			screen.settings().save();
		});
		return y + 22;
	}

	/** A colour setting under the one above it: one swatch per {@link Settings#HIGHLIGHT_COLORS}, the chosen one ringed. */
	private int subColorRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, boolean enabled, String label,
			java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
		int indent = 16;
		g.fill(x + 10, y, x + 11, y + 11, Theme.BORDER_STRONG);
		g.fill(x + 10, y + 10, x + indent, y + 11, Theme.BORDER_STRONG);
		g.text(screen.font(), label, x + indent + 6, y + 6, enabled ? Theme.TEXT : Theme.FAINT, false);
		int sx = x + w - 8;
		int[] colors = Settings.HIGHLIGHT_COLORS;
		for (int i = colors.length - 1; i >= 0; i--) {
			int color = colors[i];
			sx -= 16;
			boolean selected = get.getAsInt() == color;
			Theme.rounded(g, sx, y + 4, 12, 12, enabled ? color : Theme.alpha(color, 0x55));
			if (selected) Theme.border(g, sx - 2, y + 2, 16, 16, enabled ? Theme.TEXT_BRIGHT : Theme.FAINT);
			if (enabled && y + 16 > screen.contentTop()) screen.addHotspot(new CompanionScreen.Hotspot(sx, Math.max(y + 4, screen.contentTop()), 12, 12, event -> {
				set.accept(color);
				screen.settings().save();
			}));
		}
		return y + 22;
	}

	private static Flag flag(java.util.function.BooleanSupplier get, Consumer<Boolean> set) {
		return new Flag(get, set);
	}

	private int group(GuiGraphicsExtractor g, String title, int x, int y, int w) {
		Theme.heading(g, screen.font(), title, x, y + 4, w);
		return y + 14;
	}

	private int toggleRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, String label, String description, Flag flag) {
		int h = description.isEmpty() ? 20 : 28;
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h && mouseY >= screen.contentTop();
		if (over) Theme.rounded(g, x, y, w, h, Theme.HOVER);
		g.text(screen.font(), Theme.fit(screen.font(), label, w - 50), x + 8, y + 6, Theme.TEXT, false);
		if (!description.isEmpty()) Theme.small(g, screen.font(), Theme.fit(screen.font(), description, (w - 50) * 2), x + 8, y + 17, Theme.MUTED);
		Theme.toggle(g, x + w - 28, y + (h - 10) / 2, flag.get(), over);
		if (y + h > screen.contentTop()) screen.addHotspot(new CompanionScreen.Hotspot(x, Math.max(y, screen.contentTop()), w, h, event -> {
			flag.set(!flag.get());
			screen.settings().save();
		}));
		return y + h + 2;
	}

	private int stepperRow(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, String label, String value, Runnable down, Runnable up) {
		g.text(screen.font(), label, x + 8, y + 6, Theme.TEXT, false);
		int vx = x + w - 8 - 16;
		screen.stepper(g, mouseX, mouseY, "+", vx, y + 3, event -> {
			up.run();
			screen.settings().save();
		});
		vx -= screen.font().width(value) + 8;
		g.text(screen.font(), value, vx + 4, y + 6, Theme.accent(), false);
		screen.stepper(g, mouseX, mouseY, "−", vx - 16, y + 3, event -> {
			down.run();
			screen.settings().save();
		});
		return y + 22;
	}

}
