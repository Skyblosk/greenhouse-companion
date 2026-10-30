package dev.ilyapple.ghc.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The 27 Oct settings reorganisation carries the player's old choices over. */
class SettingsTest {
	@Test
	void oldChoicesCarryOver(@TempDir Path config) throws Exception {
		Path file = config.resolve("greenhouse-companion").resolve("settings.json");
		Files.createDirectories(file.getParent());
		// The user's settings.json before the reorganisation
		Files.writeString(file, """
				{"settingsVersion": 3, "protectJellybeans": true, "protectOtherCrops": true, "jellybeanStageLabels": true,
				 "allStageLabels": true, "protectPlacedLook": true, "overlayLabelRange": 6, "tickChime": false, "hud": true}""");
		Settings settings = Settings.load(config);
		assertTrue(settings.protectGrowing);
		assertFalse(settings.protectJellybeansOnly);
		assertTrue(settings.stageLabels);
		assertFalse(settings.stageLabelsJellybeansOnly, "every plant had stage labels");
		assertFalse(settings.protectPlacedLook, "the stage-119 look isn't protected by default any more");
		assertEquals(4, settings.overlayLabelRange);
		assertFalse(settings.highlightThroughBlocks);
		assertFalse(settings.tickChime, "a setting that didn't change keeps the player's choice");
		assertEquals(Settings.HighlightOutline.BLOCK, settings.highlightOutline("cactus"));
		settings.save();
		String saved = Files.readString(file);
		assertFalse(saved.contains("protectOtherCrops"), saved);
		assertEquals(Settings.VERSION, settings.settingsVersion);
	}

	@Test
	void cactusChoiceMigratesAndEachPlantsOutlinePersistsIndependently(@TempDir Path config) throws Exception {
		Path file = config.resolve("greenhouse-companion/settings.json");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"settingsVersion\":5,\"cactusOutline\":\"HITBOX\"}");
		Settings settings = Settings.load(config);
		assertEquals(Settings.HighlightOutline.ARMOR_STAND, settings.highlightOutline("cactus"));
		assertEquals(Settings.HighlightOutline.BLOCK, settings.highlightOutline("sugar_cane"));
		assertEquals(Settings.HighlightOutline.ARMOR_STAND, settings.highlightOutline("shadevine"));
		settings.toggleHighlightOutline("shadevine");
		settings.toggleHighlightOutline("magic_jellybean");
		Settings restored = Settings.load(config);
		assertEquals(Settings.HighlightOutline.BLOCK, restored.highlightOutline("shadevine"));
		assertEquals(Settings.HighlightOutline.BLOCK, restored.highlightOutline("magic_jellybean"));
		assertEquals(Settings.HighlightOutline.ARMOR_STAND, restored.highlightOutline("cactus"));
		assertEquals(Settings.HighlightOutline.ARMOR_STAND, restored.highlightOutline("duskbloom"));
		assertFalse(Files.readString(file).contains("cactusOutline"));
	}

	@Test
	void invalidOutlineEntriesUseTheOriginalDefaults(@TempDir Path config) throws Exception {
		Path file = config.resolve("greenhouse-companion/settings.json");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"settingsVersion\":6,\"highlightOutlines\":{\"cactus\":null,\"shadevine\":\"FUTURE_VALUE\"}}");
		Settings settings = Settings.load(config);
		assertEquals(Settings.HighlightOutline.BLOCK, settings.highlightOutline("cactus"));
		assertEquals(Settings.HighlightOutline.ARMOR_STAND, settings.highlightOutline("shadevine"));
	}

	@Test
	void jellybeansOnlyWhenOtherCropsWereOff(@TempDir Path config) throws Exception {
		Path file = config.resolve("greenhouse-companion").resolve("settings.json");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"settingsVersion\": 3, \"protectJellybeans\": true, \"protectOtherCrops\": false, \"allStageLabels\": false}");
		Settings settings = Settings.load(config);
		assertTrue(settings.protectGrowing);
		assertTrue(settings.protectJellybeansOnly);
		assertTrue(settings.stageLabelsJellybeansOnly);
	}

	@Test
	void aNewInstallStartsWithTheAuthorsSettings(@TempDir Path config) {
		Settings settings = Settings.load(config);
		assertEquals(Settings.VERSION, settings.settingsVersion);
		assertTrue(settings.highlightWater, "the water highlight is on");
		assertFalse(settings.waterAboveCrops, "the water values are off");
		assertFalse(settings.harvestBaseCrops);
		assertTrue(settings.protectGrownBaseCrops);
		assertTrue(settings.tickChime);
		assertEquals(Settings.Corner.TOP_LEFT, settings.dropsIconCorner);
		assertEquals(2, settings.waterVisitsPerDay);
		assertFalse(settings.debugStream, "the debug stream stays off for others");
	}

	@Test
	void theWaterBoxesBecomeTheHighlight(@TempDir Path config) throws Exception {
		Path file = config.resolve("greenhouse-companion").resolve("settings.json");
		Files.createDirectories(file.getParent());
		// 27 Oct: Water Above Crops drew the cyan boxes, Show water values the numbers
		Files.writeString(file, "{\"settingsVersion\": 4, \"waterAboveCrops\": true, \"waterValues\": false, \"waterVisitsPerDay\": 3}");
		Settings settings = Settings.load(config);
		assertTrue(settings.highlightWater);
		assertFalse(settings.waterAboveCrops, "the values were off");
		assertEquals(3, settings.waterVisitsPerDay);
		assertEquals(0xFF34D399, settings.grownColor);
		Files.writeString(file, "{\"settingsVersion\": 4, \"waterAboveCrops\": false, \"waterValues\": true}");
		settings = Settings.load(config);
		assertFalse(settings.highlightWater);
		assertFalse(settings.waterAboveCrops);
		settings.save();
		assertFalse(Files.readString(file).contains("waterValues"));
	}
}
