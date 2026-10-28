package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.flow.LayoutMatch;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

class RotationAndGroundTest {
	private static final Plants PLANTS = Plants.defaults();

	private static Flows flows() throws IOException {
		return Flows.parse(Files.readString(Path.of("src/test/resources/flows/jellybean-shadevine.json")));
	}

	private static Map<Integer, String> planted(Schematic layout) {
		Map<Integer, String> cells = new HashMap<>();
		layout.placements().forEach((cell, crop) -> cells.put(cell, SkyblockerCrops.byId(crop).simId()));
		return cells;
	}

	@Test
	void fourQuarterTurnsAreTheLayoutAgain() throws IOException {
		Schematic step2 = flows().plots().getFirst().steps().get(1).schematic();
		assertTrue(step2.rotated(4).sameAs(step2));
		assertTrue(step2.rotated(1).rotated(3).sameAs(step2));
		assertFalse(step2.rotated(1).sameAs(step2));
		assertEquals(3, step2.rotated(3).rotation());
	}

	@Test
	void aBigPlantKeepsItsTopLeftAnchorWhenTurned() {
		int plantboy = SkyblockerCrops.bySimId("plantboy_advance").id();
		int[][] grid = new int[10][10];
		for (int[] column : grid) Arrays.fill(column, -1);
		for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) grid[1 + dx][2 + dz] = plantboy;
		Schematic layout = new Schematic(grid, Map.of(), Map.of(Schematic.cell(1, 2), plantboy), "", Schematic.Source.SKY_SHARDS);
		Schematic turned = layout.rotated(1);
		int anchor = turned.placements().keySet().iterator().next();
		// Cells x 1-2, z 2-3 turn to x 6-7, z 1-2: the anchor is the new top-left corner, and the grid agrees
		assertEquals(Schematic.cell(6, 1), anchor);
		for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) assertEquals(plantboy, turned.at(6 + dx, 1 + dz));
	}

	@Test
	void aGreenhousePlantedTurnedIsRecognisedWithItsTurn() throws IOException {
		Flows flows = flows();
		Schematic step2 = flows.plots().getFirst().steps().get(1).schematic();
		for (int turn = 1; turn < 4; turn++) {
			LayoutMatch.Verdict verdict = LayoutMatch.identify(flows, planted(step2.rotated(turn)));
			assertTrue(verdict.confident(), "turn " + turn);
			assertEquals(1, verdict.best().stepIndex(), "turn " + turn);
			assertEquals(turn, verdict.best().rotation(), "turn " + turn);
		}
	}

	@Test
	void shadevineGrowsOnSandOrFarmland() throws IOException {
		assertEquals(Set.of("farmland", "sand"), Grounds.accepted(PLANTS.byId("shadevine")));
		assertEquals(Set.of("sand"), Grounds.accepted(PLANTS.byId("magic_jellybean")));
		assertTrue(Grounds.accepts(Grounds.accepted(PLANTS.byId("shadevine")), "minecraft:sand"));
		assertFalse(Grounds.accepts(Grounds.accepted(PLANTS.byId("shadevine")), "minecraft:soul_sand"));
		// The step-2 layout's Shadevine spots take either soil; its Jellybeans need sand
		Schematic step2 = flows().plots().getFirst().steps().get(1).schematic();
		int spot = step2.targets().keySet().iterator().next();
		assertEquals(Set.of("farmland", "sand"), Grounds.required(step2, spot, PLANTS));
	}
}
