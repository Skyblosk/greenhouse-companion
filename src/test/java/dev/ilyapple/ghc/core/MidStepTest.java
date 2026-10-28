package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.core.flow.FlowRunner;
import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.flow.LayoutMatch;
import dev.ilyapple.ghc.core.flow.MidStep;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

/** Joining the user's Jellybean + Shadevine flow part way through a step. */
class MidStepTest {
	private static final Plants PLANTS = Plants.defaults();

	private static Flows flows() throws IOException {
		return Flows.parse(Files.readString(Path.of("src/test/resources/flows/jellybean-shadevine.json")));
	}

	private static StageTracker.Tracked tracked(int cell, String id) {
		Plants.Plant plant = PLANTS.byId(id);
		return new StageTracker.Tracked(cell, plant, StageTracker.Origin.UNKNOWN, plant.allStages(), true, false, false, false, false, "k", 0);
	}

	/** Every placement of the layout in place, plus {@code extra} cells. */
	private static List<StageTracker.Tracked> planted(Schematic layout, Map<Integer, String> extra, List<Integer> missing) {
		List<StageTracker.Tracked> cells = new ArrayList<>();
		layout.placements().forEach((cell, crop) -> {
			SkyblockerCrops.Crop known = SkyblockerCrops.byId(crop);
			if (known != null && known.simId() != null && !missing.contains(cell)) cells.add(tracked(cell, known.simId()));
		});
		extra.forEach((cell, id) -> cells.add(tracked(cell, id)));
		return cells;
	}

	@Test
	void jellybeansAlreadyOnTheirSpotsCountAsSpawned() throws IOException {
		Flows.Plot plot = flows().plots().getFirst();
		Schematic step1 = plot.steps().getFirst().schematic();
		// Ten of the sixteen Jellybean spawn spots already hold a Jellybean
		Map<Integer, String> beans = new java.util.HashMap<>();
		step1.targets().forEach((cell, crop) -> {
			if (beans.size() < 10 && "magic_jellybean".equals(SkyblockerCrops.byId(crop).simId())) beans.put(cell, "magic_jellybean");
		});
		FlowRunner runner = new FlowRunner(plot);
		List<MidStep.Seeded> seeded = MidStep.seed(runner, Census.of(planted(step1, beans, List.of()), step1), 0);
		assertEquals(List.of(new MidStep.Seeded("spawned", "magic_jellybean", 10)), seeded);
		assertEquals(10, runner.spawned("magic_jellybean"));
	}

	@Test
	void missingLayoutJellybeansCountAsHarvested() throws IOException {
		Flows.Plot plot = flows().plots().getFirst();
		Schematic step2 = plot.steps().get(1).schematic();
		List<Integer> gone = step2.placements().entrySet().stream()
				.filter(entry -> "magic_jellybean".equals(SkyblockerCrops.byId(entry.getValue()).simId()))
				.map(Map.Entry::getKey).limit(4).toList();
		FlowRunner runner = new FlowRunner(plot);
		runner.enter(1);
		MidStep.seed(runner, Census.of(planted(step2, Map.of(), gone), step2), 0);
		assertEquals(4, runner.harvested("magic_jellybean"));
	}

	/** What went wrong in game: the greenhouse ran step 2 but was read before its plants were, and started at step 1. */
	@Test
	void aGreenhouseGrowingStepTwoIsRecognisedAsStepTwo() throws IOException {
		Flows flows = flows();
		Schematic step2 = flows.plots().getFirst().steps().get(1).schematic();
		Map<Integer, String> byCell = new java.util.HashMap<>();
		for (StageTracker.Tracked cell : planted(step2, Map.of(), List.of())) byCell.put(cell.cell(), cell.plant().id());
		LayoutMatch.Verdict verdict = LayoutMatch.identify(flows, byCell);
		assertTrue(verdict.confident());
		assertEquals(step2, flows.plots().get(verdict.best().plotId() - 1).steps().get(verdict.best().stepIndex()).schematic());
		// Read too early nothing is there, and nothing can be matched: identification must wait for the plants
		assertTrue(!LayoutMatch.identify(flows, Map.of()).confident());
	}
}
