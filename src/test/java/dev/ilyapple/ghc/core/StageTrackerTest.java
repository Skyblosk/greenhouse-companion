package dev.ilyapple.ghc.core;

import static dev.ilyapple.ghc.core.TestPlants.PLANTS;
import static dev.ilyapple.ghc.core.TestPlants.look;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class StageTrackerTest {
	/** Thornshade (8 stages) read at: A 1–2, B 3, C 5–6, D 7, E 8. Stage 4 has never been read. */
	private static LookTable thornshade() {
		return LookTable.of(PLANTS, TestPlants.readings("thornshade",
				new String[] {"thornshade0", "thornshade1", "thornshade2", "thornshade3", "thornshade4"},
				new int[][] {{1, 2}, {3}, {5, 6}, {7}, {8}}));
	}

	private static final int CELL = 34;

	@Test
	void aSpawnStartsAtStageOne() {
		StageTracker tracker = new StageTracker(thornshade());
		tracker.observe(CELL, TestPlants.empty(), 0);
		tracker.tick(1);
		StageTracker.Update update = tracker.observe(CELL, look("thornshade0", 0.0), 1);
		assertTrue(update.appeared());
		assertEquals(StageTracker.Origin.SPAWNED, update.after().origin());
		assertEquals(StageSet.of(1), update.after().stages());
	}

	@Test
	void aPlantBackAtItsFirstLookWasHarvestedAndReplaced() {
		// Seen fully grown, then at its first look one scan later: harvested and a new one in its place (it counts
		// as a harvest), not a plant that went backwards. A growing mutation seen first is a spawn, never placed.
		StageTracker tracker = new StageTracker(thornshade());
		assertEquals(StageTracker.Origin.UNKNOWN, tracker.observe(CELL, look("thornshade4", 0.0), 0).after().origin());
		StageTracker.Update update = tracker.observe(CELL, look("thornshade0", 0.0), 1);
		assertTrue(update.removed() && update.appeared());
		assertEquals(StageSet.of(8), update.before().stages());
		// New since the last scan, so at its very first stage
		assertEquals(StageSet.of(1), update.after().stages());
		assertEquals(StageTracker.Origin.SPAWNED, update.after().origin());
	}

	@Test
	void aSpawnAfterSeveralTicksCouldHaveComeOnAnyOfThem() {
		StageTracker tracker = new StageTracker(thornshade());
		tracker.observe(CELL, TestPlants.empty(), 0);
		tracker.tick(2);
		assertEquals(StageSet.range(1, 2), tracker.observe(CELL, look("thornshade0", 0.0), 1).after().stages());
	}

	@Test
	void ticksMoveTheStageAndALookChangePinsIt() {
		StageTracker tracker = new StageTracker(thornshade());
		// First sight with no history: the look alone says 1–2
		assertEquals(StageSet.range(1, 2), tracker.observe(CELL, look("thornshade0", 0.0), 0).after().stages());
		tracker.tick(1);
		// Still look A after a tick: it must have been at 1 and is now at 2
		assertEquals(StageSet.of(2), tracker.observe(CELL, look("thornshade0", 0.0), 1).after().stages());
		tracker.tick(1);
		StageTracker.Update update = tracker.observe(CELL, look("thornshade1", 0.0), 2);
		assertTrue(update.lookChanged());
		assertEquals(StageSet.of(3), update.after().stages());
		assertTrue(update.after().exact());
	}

	@Test
	void anUnreadStageIsReachedByTheClock() {
		StageTracker tracker = new StageTracker(thornshade());
		tracker.observe(CELL, look("thornshade0", 0.0), 0);
		tracker.tick(1);
		tracker.observe(CELL, look("thornshade0", 0.0), 1);
		tracker.tick(1);
		tracker.observe(CELL, look("thornshade1", 0.0), 2);
		tracker.tick(1);
		// Stage 4 was never read; B is the nearest look below it, so B may cover it
		StageTracker.Update stillB = tracker.observe(CELL, look("thornshade1", 0.0), 3);
		assertEquals(StageSet.of(4), stillB.after().stages());
		assertFalse(stillB.contradiction());
	}

	@Test
	void anUnknownLookIsTakenToBeAnUnreadStage() {
		StageTracker tracker = new StageTracker(thornshade());
		tracker.observe(CELL, look("thornshade1", 0.0), 0);
		tracker.tick(1);
		// A look that isn't in the table, one tick after B (3): 4 is the only stage nobody has read
		StageTracker.Update update = tracker.observe(CELL, look("thornshadeX", 0.0), 1);
		assertFalse(update.after().lookKnown());
		assertEquals(StageSet.of(4), update.after().stages());
	}

	@Test
	void aPlantThatMissesATickWidensUpwardsOnly() {
		StageTracker tracker = new StageTracker(thornshade());
		tracker.observe(CELL, look("thornshade1", 0.0), 0);
		tracker.tick(1);
		tracker.observe(CELL, look("thornshade1", 0.0), 1);
		tracker.tick(1);
		// Two ticks after 3 it should be 5 (look C), but it still shows B: it missed a tick
		StageTracker.Update update = tracker.observe(CELL, look("thornshade1", 0.0), 2);
		assertTrue(update.stalled());
		assertTrue(update.after().mayStall());
		assertEquals(StageSet.of(4), update.after().stages());
		tracker.tick(1);
		// From now on a tick keeps the low end and moves the high end
		assertEquals(StageSet.range(4, 5), tracker.get(CELL).stages());
	}

	@Test
	void glasscornGoesBackToOneAfterEight() {
		Plants.Plant glasscorn = PLANTS.byId("glasscorn");
		assertEquals(StageSet.of(1), Growth.next(glasscorn, 8));
		assertEquals(StageSet.range(7, 8), Growth.fullyGrownStages(glasscorn));
		assertEquals(7, glasscorn.readyStage());
	}

	@Test
	void aloeReadsItsHologramAndMayReset() {
		StageTracker tracker = new StageTracker(LookTable.of(PLANTS, List.of()));
		assertEquals(StageSet.of(6), tracker.observe(CELL, TestPlants.aloe(6), 0).after().stages());
		tracker.tick(1);
		// Without a hologram the next stage is 7, or 1 after a reset
		assertEquals(StageSet.of(1, 7), tracker.get(CELL).stages());
		StageTracker.Update reset = tracker.observe(CELL, TestPlants.aloe(1), 1);
		assertEquals(StageSet.of(1), reset.after().stages());
		assertFalse(reset.contradiction());
		assertTrue(reset.after().exact());
	}

	@Test
	void aPlacedMutationIsFullyGrownAndAPlantedCropStartsAtOne() {
		StageTracker tracker = new StageTracker(LookTable.of(PLANTS, List.of()));
		tracker.use(1, "Puffercloud", 1000);
		StageTracker.Tracked placed = tracker.observe(1, look("puffercloud4", -0.4375, "minecraft:wheat[age=5]"), 1500).after();
		assertEquals(StageTracker.Origin.PLACED, placed.origin());
		assertEquals(StageSet.of(14), placed.stages());

		tracker.use(2, "Wheat Seeds", 2000);
		JsonObject wheat = TestPlants.empty();
		JsonArray blocks = new JsonArray();
		blocks.add("minecraft:wheat[age=0]");
		wheat.add("blocks", blocks);
		StageTracker.Tracked planted = tracker.observe(2, wheat, 2200).after();
		assertEquals(StageTracker.Origin.PLANTED, planted.origin());
		assertEquals(StageSet.of(1), planted.stages());
	}

	@Test
	void anEmptyCellRemovesThePlant() {
		StageTracker tracker = new StageTracker(thornshade());
		tracker.observe(CELL, look("thornshade4", 0.0), 0);
		StageTracker.Update update = tracker.observe(CELL, TestPlants.empty(), 1);
		assertTrue(update.removed());
		assertEquals(StageSet.of(8), update.before().stages());
		assertEquals(null, tracker.get(CELL));
	}

	@Test
	void stateSurvivesARelog() {
		StageTracker tracker = new StageTracker(thornshade());
		tracker.observe(CELL, TestPlants.empty(), 0);
		tracker.tick(1);
		tracker.observe(CELL, look("thornshade0", 0.0), 1);
		tracker.tick(2);
		StageTracker loaded = new StageTracker(thornshade());
		loaded.load(tracker.toJson());
		assertEquals(tracker.get(CELL).stages(), loaded.get(CELL).stages());
		assertEquals(StageTracker.Origin.SPAWNED, loaded.get(CELL).origin());
		assertEquals(StageSet.of(3), loaded.observe(CELL, look("thornshade1", 0.0), 2).after().stages());
	}

	/** Magic Jellybean: N sugar canes under N full segments, plus a top that repeats every 12 stages. */
	private static JsonObject jellybean(int canes, double topDy, boolean extraSegment, int melonAge) {
		JsonArray entities = new JsonArray();
		for (int i = 0; i < canes + (extraSegment ? 1 : 0); i++) entities.add(stand("magicjellybean1", -0.1875 + i, false));
		if (topDy > 0) entities.add(stand("magicjellybean0", canes + topDy, true));
		JsonArray blocks = new JsonArray();
		for (int i = 0; i < canes; i++) blocks.add("minecraft:sugar_cane[age=0]");
		if (melonAge >= 0) blocks.add("minecraft:melon_stem[age=" + melonAge + "]");
		JsonObject fingerprint = new JsonObject();
		fingerprint.add("entities", entities);
		fingerprint.add("blocks", blocks);
		return fingerprint;
	}

	private static JsonObject stand(String head, double dy, boolean small) {
		JsonObject stand = new JsonObject();
		stand.addProperty("dy", dy);
		stand.addProperty("kind", "armor_stand");
		stand.addProperty("head", head);
		stand.addProperty("tex", head.equals("magicjellybean1") ? "c526" : "e3f2");
		stand.addProperty("small", small);
		stand.addProperty("invisible", true);
		return stand;
	}

	@Test
	void jellybeanLooksRepeatOneBlockHigherEveryTwelveStages() {
		List<LookTable.Observation> observations = new ArrayList<>();
		// Read at 57 and 58 (4 canes, phase 9–10) and at 60 (5 canes, phase 0)
		JsonObject phase9 = jellybean(4, 0.8125, false, 6);
		JsonObject phase0 = jellybean(5, 0, false, 3);
		for (int stage : new int[] {57, 58}) observations.add(new LookTable.Observation("magic_jellybean", LookKeys.keysOf(phase9).key(), phase9, stage, false, null, null, null));
		observations.add(new LookTable.Observation("magic_jellybean", LookKeys.keysOf(phase0).key(), phase0, 60, false, null, null, null));
		LookTable table = LookTable.of(PLANTS, observations);
		Plants.Plant jellybean = PLANTS.byId("magic_jellybean");

		// The same top on 7 canes is 7 * 12 + 9..10, never read itself
		LookTable.Match match = table.match(jellybean, jellybean(7, 0.8125, false, 6));
		assertTrue(match.known());
		assertTrue(match.stages().contains(93) && match.stages().contains(94), match.stages().toString());
		assertTrue(match.stages().lo() >= 84 && match.stages().hi() <= 95, match.stages().toString());
		// And the 0–2 look on 9 canes is 108 or later
		assertTrue(table.match(jellybean, jellybean(9, 0, false, 3)).stages().contains(108));
	}
}
