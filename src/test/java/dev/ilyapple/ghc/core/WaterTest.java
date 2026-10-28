package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.ilyapple.ghc.core.flow.Census;

class WaterTest {
	private static final Plants PLANTS = Plants.defaults();

	/** A 1x1 plant at (x, z) at {@code stage}, spawned (mutations) or planted (base crops). */
	private static Census.Plant plant(String id, int x, int z, int stage) {
		Plants.Plant kind = PLANTS.byId(id);
		StageTracker.Origin origin = kind.mutation() ? StageTracker.Origin.SPAWNED : StageTracker.Origin.PLANTED;
		return new Census.Plant(kind, List.of(x * 10 + z), StageSet.of(stage), origin, true, true);
	}

	private static double factorOf(Census.Plant target, Census.Plant... others) {
		List<Census.Plant> plants = new ArrayList<>(List.of(others));
		plants.add(target);
		return new Water().factors(plants).get(target);
	}

	@Test
	void effectsScaleTheLoss() {
		Census.Plant wheat = plant("wheat", 5, 5, 1);
		assertEquals(1.0, factorOf(wheat), 1e-9);
		assertEquals(1 / 1.5, factorOf(wheat, plant("melon", 4, 5, 11)), 1e-9);
		// Improved retain hides the plain one
		assertEquals(0.5, factorOf(wheat, plant("melon", 4, 5, 11), plant("cactus", 6, 5, 8)), 1e-9);
		assertEquals(1.3, factorOf(wheat, plant("red_mushroom", 5, 4, 6)), 1e-9);
		// Immunity (Potato) strips the drain
		assertEquals(1.0, factorOf(wheat, plant("red_mushroom", 5, 4, 6), plant("potato", 5, 6, 8)), 1e-9);
		// Diagonal neighbours give nothing
		assertEquals(1.0, factorOf(wheat, plant("cactus", 6, 6, 8)), 1e-9);
	}

	@Test
	void effectSpreadPassesWhatItHoldsOneStepFurther() {
		Census.Plant cactus = plant("cactus", 0, 0, 8);
		Census.Plant middle = plant("wheat", 1, 0, 8);
		Census.Plant far = plant("carrot", 2, 0, 1);
		assertEquals(1.0, factorOf(far, cactus, middle), 1e-9);
		// A Wild Rose beside the middle crop gives it Effect Spread: it passes the cactus's retain on
		assertEquals(0.5, factorOf(far, cactus, middle, plant("wild_rose", 1, 1, 15)), 1e-9);
	}

	@Test
	void waterFollowsTheStage() {
		Water water = new Water();
		Census.Plant young = plant("wheat", 0, 0, 1);
		water.appeared(0, young.kind(), young.origin(), true);
		// New at stage 1 with no water; 20 a stage after that, never below -100
		assertEquals(0.0, water.water(young, 1, 1.0));
		assertEquals(-60.0, water.water(young, 4, 1.0));
		assertEquals(-100.0, water.water(young, 8, 1.0));
		// Improved Water Retain halves it
		assertEquals(-30.0, water.water(young, 4, 0.5));
		// A Hydro Can bar or Crop Diagnostics resets it at the stage it was read
		water.read(young, 100, 4);
		assertEquals(60.0, water.water(young, 6, 1.0));
		// Not seen new: unknown
		water.appeared(1, young.kind(), young.origin(), false);
		assertNull(water.water(plant("wheat", 0, 1, 3), 3, 1.0));
	}

	@Test
	void needsWaterBeforeTheNextVisit() {
		assertEquals(List.of(14, 7, 5, 4), List.of(Water.stagesPerVisit(1), Water.stagesPerVisit(2), Water.stagesPerVisit(3), Water.stagesPerVisit(4)));
		// A Jellybean (120 stages) at 0 water lasts 4 stages: twice a day (7) it runs out, so it needs water
		assertTrue(Water.needsWater(0.0, 40, 120, 1.0, 7));
		// Full, it lasts 9 stages: once a day (14) it runs out; three times a day (5) it's fine
		assertTrue(Water.needsWater(100.0, 40, 120, 1.0, 14));
		assertFalse(Water.needsWater(100.0, 40, 120, 1.0, 5));
		// With Improved Water Retain (10 a stage) full lasts 19 stages
		assertFalse(Water.needsWater(100.0, 40, 120, 0.5, 14));
		// Enough to grow to full: Wheat at 5 of 8 with 0 water grows its last 3 stages before -100
		assertFalse(Water.needsWater(0.0, 5, 8, 1.0, 14));
		assertTrue(Water.needsWater(-60.0, 5, 8, 1.0, 14));
		// Fully grown needs none; unknown or dried out does
		assertFalse(Water.needsWater(-100.0, 8, 8, 1.0, 7));
		assertTrue(Water.needsWater(null, 3, 8, 1.0, 7));
		assertTrue(Water.needsWater(-100.0, 3, 8, 1.0, 7));
		// Yellow before you're back, red at -100, nothing once grown
		assertEquals(Water.Need.SOON, Water.need(0.0, 40, 120, 1.0, 7));
		assertEquals(Water.Need.SOON, Water.need(null, 3, 8, 1.0, 7));
		assertEquals(Water.Need.DRY, Water.need(-100.0, 3, 8, 1.0, 7));
		assertEquals(Water.Need.NONE, Water.need(-100.0, 8, 8, 1.0, 7));
		assertEquals(Water.Need.NONE, Water.need(100.0, 40, 120, 1.0, 4));
	}

	@Test
	void whoUsesWater() {
		assertTrue(Water.consumes(PLANTS.byId("wheat"), StageTracker.Origin.PLANTED));
		assertTrue(Water.consumes(PLANTS.byId("sugar_cane"), StageTracker.Origin.PLANTED));
		// In game these never need water, though the simulator waters every base crop
		for (String dry : List.of("cactus", "cocoa_beans", "nether_wart", "red_mushroom", "brown_mushroom")) {
			assertFalse(Water.consumes(PLANTS.byId(dry), StageTracker.Origin.PLANTED), dry);
		}
		// Growing mutations that need no water (the user's list, and the simulator's data)
		for (String dry : List.of("cindershade", "zombud", "chorus_fruit", "shellfruit", "phantomleaf", "all_in_aloe", "plantboy_advance")) {
			assertFalse(PLANTS.byId(dry).requiresWatering(), dry);
			assertFalse(Water.consumes(PLANTS.byId(dry), StageTracker.Origin.SPAWNED), dry);
		}
		assertTrue(Water.consumes(PLANTS.byId("duskbloom"), StageTracker.Origin.SPAWNED));
		assertTrue(Water.consumes(PLANTS.byId("magic_jellybean"), StageTracker.Origin.SPAWNED));
		assertFalse(Water.consumes(PLANTS.byId("magic_jellybean"), StageTracker.Origin.PLACED));
		assertFalse(Water.consumes(PLANTS.byId("shadevine"), StageTracker.Origin.SPAWNED));
		assertFalse(Water.consumes(PLANTS.byId("soggybud"), StageTracker.Origin.SPAWNED));
		Water water = new Water();
		water.appeared(5, PLANTS.byId("shadevine"), StageTracker.Origin.SPAWNED, true);
		assertNull(water.water(plant("shadevine", 0, 5, 1), 1, 1.0));
	}

	@Test
	void cropDiagnosticsStatesTheWater() {
		// The menu as read on the alpha, 27 Oct 2026
		Diagnosis reading = Diagnosis.parse("Magic Jellybean", List.of("Stage: 52/120", "Next Stage: 1h 11m 53s", "Water: -30/100",
				"Lasts for: 11h 44m 59s", "Status: Harvestable"));
		assertEquals(-30, reading.water());
		assertEquals(52, reading.stage());
		assertEquals(90, Diagnosis.parse("Magic Jellybean", List.of("Stage: 60/120", "Water: 90/100")).water());
		assertNull(Diagnosis.parse("Glasscorn", List.of("Stage: 6/8")).water());
	}

	@Test
	void waterSurvivesSaving() {
		Water water = new Water();
		Census.Plant wheat = plant("wheat", 0, 3, 3);
		water.read(wheat, 40, 3);
		Water back = new Water();
		back.load(water.toJson());
		assertEquals(0.0, back.water(wheat, 5, 1.0));
		// The old simulated ranges are dropped
		com.google.gson.JsonObject old = com.google.gson.JsonParser.parseString("{\"3\": [-20.0, 0.0, true]}").getAsJsonObject();
		back.load(old);
		assertNull(back.water(wheat, 5, 1.0));
	}
}
