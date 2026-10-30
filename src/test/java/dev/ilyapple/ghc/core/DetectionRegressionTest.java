package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import dev.ilyapple.ghc.core.overlay.Harvest;

/** Regressions from the Aether 26 debug recordings on 29 October 2026. */
class DetectionRegressionTest {
	@Test
	void decayInfoDoesNotTurnAGrowingDuskbloomIntoAPlacedInput() {
		LookTable table = LookTable.of(TestPlants.PLANTS, TestPlants.readings("duskbloom",
				new String[] {"duskbloom0", "duskbloom1"}, new int[][] {{2}, {8}}));
		Greenhouse greenhouse = new Greenhouse(table, new Inventory());
		greenhouse.observe(36, TestPlants.look("duskbloom0", 0), 0);
		Diagnosis reading = Diagnosis.parse("Duskbloom", List.of("Stage: 2/8", "Times Mutated: 0",
				"Decay Info", "Mutates Remaining: 8", "Water: 100/100", "Status: Growing"));
		greenhouse.diagnose(36, reading, 1);
		assertEquals(StageTracker.Origin.SPAWNED, greenhouse.tracker().get(36).origin());
		greenhouse.observe(36, TestPlants.look("duskbloom1", 0), 2);
		greenhouse.diagnose(36, Diagnosis.parse("Duskbloom", List.of("Stage: FULLY GROWN", "Decay Info", "Status: Harvestable")), 3);
		assertTrue(Harvest.ready(greenhouse.context().census().plants().getFirst(), null));
	}

	@Test
	void decimalWaterInSugarCaneDiagnosticsIsReadExactly() {
		Diagnosis reading = Diagnosis.parse("Sugar Cane", List.of("Stage: 7/8", "Water: -88.3/100", "Status: Wilting"));
		assertNotNull(reading.water());
		assertEquals(-88.3, reading.water().doubleValue(), 1e-9);
		assertEquals(56.7, Diagnosis.parse("Sugar Cane", List.of("Stage: FULLY GROWN", "Water: 56.7/100")).water().doubleValue(), 1e-9);
	}

	@Test
	void unchangedFirstStageLookAfterATickIsStallingNotReplanting() {
		LookTable table = LookTable.of(TestPlants.PLANTS, TestPlants.readings("duskbloom",
				new String[] {"duskbloom0", "duskbloom1"}, new int[][] {{1}, {2}}));
		StageTracker tracker = new StageTracker(table);
		tracker.observe(17, TestPlants.look("duskbloom0", 0), 0);
		tracker.tick(1);
		StageTracker.Update update = tracker.observe(17, TestPlants.look("duskbloom0", 0), 1);
		assertFalse(update.appeared());
		assertFalse(update.removed());
		assertTrue(update.stalled());
	}
	@Test
	void harvestableDuskbloomRepairsAnOldPlacedClassificationButJellybeanKeepsItsOrigin() {
		StageTracker tracker = new StageTracker(LookTable.of(TestPlants.PLANTS, List.of()));
		tracker.use(35, "Duskbloom", 0);
		tracker.observe(35, TestPlants.look("duskbloom0", 0), 1);
		assertEquals(StageTracker.Origin.PLACED, tracker.get(35).origin());
		tracker.diagnose(35, Diagnosis.parse("Duskbloom", List.of("Stage: FULLY GROWN", "Decay Info", "Status: Harvestable")), 2);
		assertEquals(StageTracker.Origin.SPAWNED, tracker.get(35).origin());
		assertNull(Diagnosis.parse("Magic Jellybean", List.of("Stage: FULLY GROWN", "Status: Harvestable")).origin(true, true, true));
		assertEquals(StageTracker.Origin.PLACED, Diagnosis.parse("Duskbloom", List.of("Stage: FULLY GROWN", "Status: Uncollectable")).origin(true, true, false));
	}

	@Test
	void stableDrySugarCaneNeedsAnotherObservationAfterAClockTickAndReload() {
		com.google.gson.JsonObject print = TestPlants.empty();
		com.google.gson.JsonArray blocks = print.getAsJsonArray("blocks");
		for (int i = 0; i < 3; i++) blocks.add("minecraft:sugar_cane[age=0]");
		blocks.add("minecraft:wheat[age=3]");
		com.google.gson.JsonObject grown = print.deepCopy();
		grown.getAsJsonArray("blocks").add("minecraft:sugar_cane[age=0]");
		LookTable table = LookTable.of(TestPlants.PLANTS, List.of(
				new LookTable.Observation("sugar_cane", LookKeys.keysOf(print).key(), print, 7, false, null, null, null),
				new LookTable.Observation("sugar_cane", LookKeys.keysOf(grown).key(), grown, 8, true, null, null, null)));
		Greenhouse greenhouse = new Greenhouse(table, new Inventory());
		greenhouse.observe(61, print, 0);
		greenhouse.diagnose(61, Diagnosis.parse("Sugar Cane", List.of("Stage: 7/8", "Status: Stopped")), 1);
		var cane = greenhouse.context().census().plants().getFirst();
		greenhouse.water().read(cane, -100, 7);
		Greenhouse restored = new Greenhouse(table, new Inventory());
		restored.loadState(greenhouse.saveState());
		assertFalse(restored.tracker().hasPendingTicks(61));
		restored.tick(1, 1);
		assertTrue(restored.tracker().hasPendingTicks(61));
		// The watcher must apply this even though the raw fingerprint did not change.
		restored.observe(61, print, 1);
		assertFalse(restored.tracker().hasPendingTicks(61));
		var after = restored.context().census().plants().getFirst();
		assertEquals(StageSet.of(7), after.stages());
		assertEquals(Water.Need.DRY, Water.need(restored.water().water(after, 7, 1), 7, 8, 1, 7));
	}

}
