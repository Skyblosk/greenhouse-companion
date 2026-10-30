package dev.ilyapple.ghc.core;

import static dev.ilyapple.ghc.core.TestPlants.PLANTS;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.core.flow.LiveLayout;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

class LiveLayoutTest {
	private StageTracker.Tracked cell(String id, int x, int z, StageSet stages, boolean known) {
		return new StageTracker.Tracked(Schematic.cell(x, z), PLANTS.byId(id), StageTracker.Origin.UNKNOWN,
				stages, known, stages.size() == 1, true, false, false, "test", 0);
	}

	@Test void everyBaseCropIsPlacedAtEveryStage() {
		for (var crop : SkyblockerCrops.ALL) {
			var plant = crop.simId() == null ? null : PLANTS.byId(crop.simId());
			if (plant == null || plant.mutation()) continue;
			for (int stage : plant.allStages().toList()) {
				var layout = LiveLayout.copy(Census.of(List.of(cell(plant.id(), 2, 7, StageSet.of(stage), true)), null));
				assertEquals(Map.of(plant.id(), 1), layout.inputCounts(), plant.id() + " stage " + stage);
				assertTrue(layout.targets().isEmpty());
				assertEquals(crop.id(), layout.at(2, 7));
			}
		}
	}

	@Test void matureMutationsAreInputsAndGrowingOnesAreTargets() {
		var bean = PLANTS.byId("magic_jellybean");
		var layout = LiveLayout.copy(Census.of(List.of(
				cell(bean.id(), 1, 2, StageSet.of(bean.readyStage()), true),
				cell(bean.id(), 3, 4, StageSet.range(39, 45), true),
				cell(bean.id(), 5, 6, StageSet.range(bean.readyStage() - 1, bean.readyStage()), true),
				cell(bean.id(), 7, 8, bean.allStages(), false)), null));
		assertEquals(Map.of(bean.id(), 1), layout.inputCounts());
		assertEquals(Map.of(bean.id(), 1), layout.targetCounts());
		assertEquals(0, layout.at(3, 4));
		assertEquals(-1, layout.at(5, 6));
		assertEquals(-1, layout.at(7, 8));
		assertEquals(layout, Schematic.fromLink(layout.code()));
	}

	@Test void adjacentLargePlantsKeepSeparateAnchorsAndRotation() {
		List<StageTracker.Tracked> cells = new ArrayList<>();
		var plant = PLANTS.byId("noctilume");
		for (int x = 2; x < 6; x++) for (int z = 4; z < 6; z++)
			cells.add(cell(plant.id(), x, z, StageSet.of(plant.readyStage()), true));
		var layout = LiveLayout.copy(Census.of(cells, null));
		assertEquals(Map.of(plant.id(), 2), layout.inputCounts());
		assertEquals(Map.of(24, 22, 44, 22), layout.placements());
		assertEquals(layout, layout.rotated(1).rotated(3));
		assertEquals(layout, Schematic.fromLink(layout.code()));
	}

	@Test void fullPaletteRoundTripsAndEmptySnapshotsAreRejected() {
		Map<Integer, Integer> anchors = new java.util.LinkedHashMap<>();
		for (int i = 0; i < 40; i++) anchors.put(i, i + 1);
		// Use one-cell crops so the palette test does not overlap big footprints.
		anchors.entrySet().removeIf(entry -> SkyblockerCrops.sizeOf(entry.getValue()) > 1);
		var layout = Schematic.fromAnchors(anchors, Map.of());
		assertEquals(anchors, layout.placements());
		assertEquals(layout, Schematic.fromLink(layout.code()));
		assertThrows(IllegalArgumentException.class, () -> LiveLayout.copy(Census.of(List.of(), null)));
	}
}
