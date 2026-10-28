package dev.ilyapple.ghc.mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.jspecify.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * Every option in the Settings page, saved to {@code config/greenhouse-companion/settings.json}. Fields keep their
 * defaults when a saved file is older and doesn't have them.
 */
public final class Settings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	public enum Accent {
		EMERALD(0xFF34D399), AMBER(0xFFFBBF24), SKY(0xFF38BDF8), VIOLET(0xFFA78BFA), ROSE(0xFFFB7185);

		public final int color;

		Accent(int color) {
			this.color = color;
		}
	}

	/** How the highlight outlines a cactus: its blocks (the stalk), or its armor stands' hitboxes. */
	public enum CactusOutline { BLOCK, HITBOX }

	/** The colours a highlight can be drawn in (swatches on the Settings page). */
	public static final int[] HIGHLIGHT_COLORS = {0xFF34D399, 0xFFFACC15, 0xFFEF4444, 0xFFFB923C, 0xFF22D3EE, 0xFF60A5FA, 0xFFA78BFA, 0xFFF472B6, 0xFFF8FAFC};

	/** Bumped when a default changes in a way saved settings should follow once. */
	public int settingsVersion = 0;
	/** The current {@link #settingsVersion}: a new install starts here, with nothing to carry over. */
	static final int VERSION = 5;

	// Main settings (the user's list, 27 Oct 2026); a sub-setting only matters while its main one is on

	/** Layout Overlay: boxes on every cell that needs something done, ghosts of missing plants. */
	public boolean layoutHologram = true;
	/** Layout Overlay › identify greenhouses automatically. */
	public boolean autoIdentify = true;
	/** Labels up Close: what to do at each box, written above it within {@link #overlayLabelRange}. */
	public boolean hologramLabels = true;
	public int overlayLabelRange = 4;
	/** Short Jellybeans. */
	public boolean shrinkJellybeans = true;
	/** Protect Growing Crops: left clicks on a crop that isn't fully grown do nothing. */
	public boolean protectGrowing = true;
	/** Protect Growing Crops › Magic Jellybeans only. */
	public boolean protectJellybeansOnly = false;
	/** Protect Right Clicks (the Plant Diagnostics Tool always works). */
	public boolean protectRightClicks = false;
	/** Stage Above Crops, within {@link #labelRange}. */
	public boolean stageLabels = true;
	/** Stage Above Crops › Magic Jellybeans only. */
	public boolean stageLabelsJellybeansOnly = false;
	public int labelRange = 24;
	/** Highlight Fully Grown Crops. */
	public boolean highlightHarvestable = true;
	/** Highlight Fully Grown Crops › through blocks (Athen's depthless boxes); the water highlight too. */
	public boolean highlightThroughBlocks = false;
	/** Highlight Fully Grown Crops › colour (green). */
	public int grownColor = 0xFF34D399;
	/**
	 * Harvest Base Crops. Off: they're left to grow, so nothing marks them for harvest (no highlight, not counted as
	 * ready, no stage label).
	 */
	public boolean harvestBaseCrops = false;
	/** Harvest Base Crops (off) › protect fully grown base crops too, so they can't be broken by accident. */
	public boolean protectGrownBaseCrops = true;
	/** Title when it's time to swap. */
	public boolean alertTitle = true;
	/** Title › chime. */
	public boolean alertSound = true;
	/** Title › chat line with an [Open] link. */
	public boolean alertChat = true;
	/** Chime on every growth tick. */
	public boolean tickChime = true;

	/** Profit tracker: every greenhouse harvest and what dropped from it. */
	public boolean recordDrops = true;
	/** Profit tracker › the icon on inventory and chat screens that opens the tracker's panel. */
	public boolean dropsIcon = true;
	public Corner dropsIconCorner = Corner.TOP_LEFT;

	/**
	 * Highlight Crops That Need Water: outlines a crop that would reach −100 water before you're back in
	 * {@link #needsWaterColor} (yellow), one already at −100 in {@link #driedColor} (red).
	 */
	public boolean highlightWater = true;
	/** Highlight Crops That Need Water › how often you come back, 1–4 times a day ({@link dev.ilyapple.ghc.core.Water#stagesPerVisit}). */
	public int waterVisitsPerDay = 2;
	public int needsWaterColor = 0xFFFACC15;
	public int driedColor = 0xFFEF4444;
	/** Water Above Crops: each crop's water written above it, within {@link #waterLabelRange}. */
	public boolean waterAboveCrops = false;
	public int waterLabelRange = 8;

	// HUD and look
	public boolean hud = true;
	public Corner hudCorner = Corner.TOP_LEFT;
	public int hudX = 6;
	public int hudY = 34;
	public boolean hudOnlyInGarden = true;
	public Accent accent = Accent.EMERALD;
	public int menuOpacity = 94;

	// Debug: everything else, on unless noted

	/** Boxes and ghost soil for the wrong ground. */
	public boolean hologramGround = true;
	/** Draw the overlay's boxes through blocks. */
	public boolean overlaySeeThrough = true;
	/** Devourer layouts: only ask for a Puffercloud/Zombud while a spot it touches is empty; fillers elsewhere. */
	public boolean devourerRingOnlyWhenNeeded = true;
	/** Also protect a Jellybean showing the stage-119 look (placed ones look like it). Off by default. */
	public boolean protectPlacedLook = false;
	/** Say above the hotbar why a click was blocked. */
	public boolean protectionMessage = true;
	/** How the highlight outlines a cactus. */
	public CactusOutline cactusOutline = CactusOutline.BLOCK;
	/** The local, read-only debug stream on 127.0.0.1. Off by default: it is for following a session from outside the game. */
	public boolean debugStream = false;
	public int debugPort = 47913;

	// Replaced on 27 Oct 2026, read once to carry the player's choice over
	@Deprecated private @Nullable Boolean protectJellybeans;
	@Deprecated private @Nullable Boolean protectOtherCrops;
	@Deprecated private @Nullable Boolean jellybeanStageLabels;
	@Deprecated private @Nullable Boolean allStageLabels;
	@Deprecated private @Nullable Boolean builtInOverlay;
	@Deprecated private @Nullable Boolean skyblockerAutoPaste;
	@Deprecated private @Nullable Boolean hideSkyblockerChat;
	// Replaced on 28 Oct 2026: Water Above Crops drew the boxes, and this wrote the values
	@Deprecated private @Nullable Boolean waterValues;

	private transient Path file;

	public static Settings load(Path configDir) {
		Path file = configDir.resolve("greenhouse-companion").resolve("settings.json");
		Settings settings = null;
		try {
			if (Files.exists(file)) settings = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
		} catch (IOException | JsonParseException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not read {}, using defaults", file, e);
		}
		if (settings == null) {
			settings = new Settings();
			settings.settingsVersion = VERSION;
		}
		if (settings.hudCorner == null) settings.hudCorner = Corner.TOP_LEFT;
		if (settings.accent == null) settings.accent = Accent.EMERALD;
		settings.labelRange = Math.max(4, Math.min(64, settings.labelRange));
		settings.overlayLabelRange = Math.max(2, Math.min(32, settings.overlayLabelRange));
		settings.labelRange = Math.max(4, Math.min(64, settings.labelRange));
		settings.menuOpacity = Math.max(40, Math.min(100, settings.menuOpacity));
		if (settings.debugPort < 1024 || settings.debugPort > 65535) settings.debugPort = 47913;
		if (settings.settingsVersion < 4) settings.migrateTo4();
		if (settings.cactusOutline == null) settings.cactusOutline = CactusOutline.BLOCK;
		if (settings.dropsIconCorner == null) settings.dropsIconCorner = Corner.TOP_LEFT;
		if (settings.settingsVersion < 5) settings.migrateTo5();
		settings.waterVisitsPerDay = Math.max(1, Math.min(4, settings.waterVisitsPerDay));
		settings.waterLabelRange = Math.max(2, Math.min(64, settings.waterLabelRange));
		settings.grownColor = opaque(settings.grownColor);
		settings.needsWaterColor = opaque(settings.needsWaterColor);
		settings.driedColor = opaque(settings.driedColor);
		settings.file = file;
		return settings;
	}

	/**
	 * The 27 Oct reorganisation: Protect Growing Crops and Stage Above Crops became one setting each with a "Magic
	 * Jellybeans only" sub-toggle, labels up close start at 4 blocks, the stage-119 look isn't protected, and the
	 * highlight no longer shows through blocks unless asked.
	 */
	@SuppressWarnings("deprecation")
	private void migrateTo4() {
		if (protectJellybeans != null || protectOtherCrops != null) {
			boolean beans = !Boolean.FALSE.equals(protectJellybeans);
			boolean others = !Boolean.FALSE.equals(protectOtherCrops);
			protectGrowing = beans || others;
			protectJellybeansOnly = beans && !others;
		}
		if (jellybeanStageLabels != null || allStageLabels != null) {
			boolean beans = !Boolean.FALSE.equals(jellybeanStageLabels);
			boolean all = Boolean.TRUE.equals(allStageLabels);
			stageLabels = beans || all;
			stageLabelsJellybeansOnly = !all;
		}
		overlayLabelRange = 4;
		protectPlacedLook = false;
		highlightThroughBlocks = false;
		layoutHologram = true;
		hologramLabels = true;
		protectJellybeans = protectOtherCrops = jellybeanStageLabels = allStageLabels = builtInOverlay = skyblockerAutoPaste = hideSkyblockerChat = null;
		settingsVersion = 4;
	}

	/**
	 * The 28 Oct water change: the boxes became the highlight (Highlight Crops That Need Water), and Water Above Crops is
	 * now the water values above the crops.
	 */
	@SuppressWarnings("deprecation")
	private void migrateTo5() {
		// Only a file from the version with the boxes has waterValues; an older one keeps the new defaults
		if (waterValues != null) {
			highlightWater = waterAboveCrops;
			waterAboveCrops = waterAboveCrops && waterValues;
		}
		waterValues = null;
		settingsVersion = 5;
	}

	/** A saved colour with no alpha drawn as opaque. */
	private static int opaque(int color) {
		return (color >>> 24) == 0 ? color | 0xFF000000 : color;
	}

	/** Hours between visits (the harvest interval). */
	public double waterIntervalHours() {
		return 24.0 / waterVisitsPerDay;
	}

	/** "2× a day (10 stages)". */
	public String waterIntervalLabel() {
		return waterVisitsPerDay + "× a day (" + dev.ilyapple.ghc.core.Water.stagesPerVisit(waterVisitsPerDay) + " stages)";
	}

	public void save() {
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not save {}", file, e);
		}
	}
}
