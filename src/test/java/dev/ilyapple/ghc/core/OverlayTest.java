package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.overlay.Harvest;
import dev.ilyapple.ghc.core.overlay.PlotOverlay;
import dev.ilyapple.ghc.core.overlay.PlotOverlay.Action;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

/**
 * The rebuilt layout overlay, the harvest highlight's rule, the placed-by-default origin and Crop Diagnostics readings,
 * checked on the user's three greenhouses as the mod saved them on 27 Oct 2026 (Plots 4 and 9 planted half a turn
 * round, Plot 12 as drawn).
 */
class OverlayTest {
	private static final Plants PLANTS = Plants.defaults();
	private static final PlotOverlay.Options OPTIONS = PlotOverlay.Options.DEFAULT;

	private record Saved(Schematic layout, List<StageTracker.Tracked> tracked) {
		Census census() {
			return Census.of(tracked, layout);
		}
	}

	private static Saved saved(String siteKey) throws IOException {
		JsonObject state = JsonParser.parseString(Files.readString(Path.of("src/test/resources/saved/state-2026-10-27.json"))).getAsJsonObject();
		Flows flows = Flows.parse(Files.readString(Path.of("src/test/resources/flows/jellybean-shadevine.json")));
		JsonObject assignment = state.getAsJsonObject("assignments").getAsJsonObject(siteKey);
		int plotId = assignment.get("plot").getAsInt();
		int step = state.getAsJsonObject("runners").getAsJsonObject(siteKey).get("stepIndex").getAsInt();
		Flows.Plot plot = flows.plots().stream().filter(p -> p.id() == plotId).findFirst().orElseThrow();
		Schematic layout = plot.steps().get(step).schematic().rotated(assignment.get("rotation").getAsInt());
		List<StageTracker.Tracked> tracked = new ArrayList<>();
		for (JsonElement element : state.getAsJsonObject("trackers").getAsJsonObject(siteKey).getAsJsonArray("cells")) {
			JsonObject cell = element.getAsJsonObject();
			List<Integer> anchor = new ArrayList<>();
			cell.getAsJsonArray("anchor").forEach(stage -> anchor.add(stage.getAsInt()));
			Plants.Plant plant = PLANTS.byId(cell.get("plant").getAsString());
			tracked.add(new StageTracker.Tracked(cell.get("cell").getAsInt(), plant, StageTracker.Origin.valueOf(cell.get("origin").getAsString()),
					StageSet.of(anchor), true, anchor.size() == 1, false, false, cell.get("blockOnly").getAsBoolean(), "", 0));
		}
		return new Saved(layout, tracked);
	}

	private static Map<Action, Long> counts(List<PlotOverlay.Mark> marks) {
		return marks.stream().collect(Collectors.groupingBy(PlotOverlay.Mark::action, Collectors.counting()));
	}

	@Test
	void plot4PlantedHalfWayRoundHasNoFalseRedBoxes() throws IOException {
		Saved plot4 = saved("alpha.hypixel.net|-5,91");
		List<PlotOverlay.Mark> marks = PlotOverlay.plan(plot4.layout(), plot4.census(), PLANTS, cell -> null, Set.of(), OPTIONS);
		Map<Action, Long> counts = counts(marks);
		// Every Shadevine growing on a spot is fine; only the one missing Sugar Cane is asked for
		assertNull(counts.get(Action.REMOVE), marks.toString());
		assertNull(counts.get(Action.CLEAR), marks.toString());
		assertEquals(1L, counts.get(Action.PLACE));
		assertEquals("+ Sugar Cane", marks.stream().filter(m -> m.action() == Action.PLACE).findFirst().orElseThrow().label());
		assertTrue(counts.get(Action.SPOT) > 0);
		marks.stream().filter(m -> m.action() == Action.SPOT).forEach(m -> assertFalse(m.action().problem()));
	}

	@Test
	void plot9IsTheHybridDuskbloomPlotAtHalfATurn() throws IOException {
		// The user kept Plot 9's planted 24 Oct Duskbloom half and took the new Jellybean half (27 Oct)
		String text = Files.readString(Path.of("src/main/resources/assets/greenhouse-companion/flows/hybrid-shadevine.json"));
		for (String old : List.of("Rco7CoAwEEXRDT3ENFr72cib", "XYsxCoAwEAQ_tIUp1Pr0JXe5", "XcoxDoAgEETRC02hjdYCFxlW", "TcoxDoAwDEPRC3mAAZgNJ0kaQ")) {
			assertFalse(text.contains(old), "the bundled flow must not hold a superseded code, or it is upgraded on every start");
		}
		Flows flows = Flows.parse(text);
		Saved plot9 = saved("alpha.hypixel.net|-5,-197");
		Map<Integer, String> byCell = new HashMap<>();
		for (StageTracker.Tracked t : plot9.tracked()) byCell.put(t.cell(), t.plant().id());
		dev.ilyapple.ghc.core.flow.LayoutMatch.Verdict verdict = dev.ilyapple.ghc.core.flow.LayoutMatch.identify(flows, byCell);
		assertTrue(verdict.confident(), verdict.toString());
		assertEquals(3, verdict.best().plotId());
		assertEquals(1, verdict.best().stepIndex());
		assertEquals(2, verdict.best().rotation());
	}

	@Test
	void plot12FlagsOnlyTheShadevineInAJellybeanCell() throws IOException {
		Saved plot12 = saved("alpha.hypixel.net|-5,187");
		List<PlotOverlay.Mark> marks = PlotOverlay.plan(plot12.layout(), plot12.census(), PLANTS, cell -> null, Set.of(), OPTIONS);
		List<PlotOverlay.Mark> problems = marks.stream().filter(m -> m.action().problem()).toList();
		assertEquals(1, problems.size(), problems.toString());
		assertEquals(Action.REMOVE, problems.getFirst().action());
		assertEquals(Schematic.cell(5, 3), problems.getFirst().anchor());
		assertEquals("✘ Shadevine → Magic Jellybean", problems.getFirst().label());
	}

	@Test
	void wrongSoilIsBoxedOnlyWhereThePlantCantGrow() throws IOException {
		Saved plot12 = saved("alpha.hypixel.net|-5,187");
		// Every cell sand: the Shadevine spots and Jellybeans are fine, the farmland crops (Wild Rose...) are not
		List<PlotOverlay.Mark> marks = PlotOverlay.plan(plot12.layout(), plot12.census(), PLANTS, cell -> "sand", Set.of(), OPTIONS);
		Set<Integer> soil = marks.stream().filter(m -> m.action() == Action.SOIL).map(PlotOverlay.Mark::anchor).collect(Collectors.toSet());
		for (int spot : plot12.layout().targets().keySet()) assertFalse(soil.contains(spot), "Shadevine spot " + spot);
		int wildRose = SkyblockerCrops.bySimId("wild_rose").id();
		plot12.layout().placements().forEach((cell, crop) -> {
			if (crop == wildRose) assertTrue(soil.contains(cell), "Wild Rose at " + cell);
		});
		PlotOverlay.Mark any = marks.stream().filter(m -> m.action() == Action.SOIL).findFirst().orElseThrow();
		assertTrue(any.label().startsWith("▼ Soil: "), any.label());
	}

	@Test
	void fullyGrownMutationsOffTheSpotsArePlacedUnlessSeenOtherwise() throws IOException {
		Saved plot12 = saved("alpha.hypixel.net|-5,187");
		Census census = plot12.census();
		for (Census.Plant plant : census.plants()) {
			if (plant.id().equals("zombud") || plant.id().equals("cindershade")) {
				assertEquals(StageTracker.Origin.PLACED, plant.origin(), plant.id());
				assertFalse(Harvest.ready(plant, plot12.layout()), plant.id() + " is placed, not for harvest");
			}
		}
		// The same Zombud with no layout at all: fully grown, so placed by default
		StageTracker.Tracked zombud = plot12.tracked().stream().filter(t -> t.plant().id().equals("zombud")).findFirst().orElseThrow();
		assertEquals(StageTracker.Origin.PLACED, Census.of(List.of(zombud), null).plants().getFirst().origin());
		// Growing (not fully grown), it can't have been placed: spawned
		StageTracker.Tracked young = new StageTracker.Tracked(zombud.cell(), zombud.plant(), StageTracker.Origin.UNKNOWN, StageSet.of(4), true, true,
				false, false, false, "", 0);
		assertEquals(StageTracker.Origin.SPAWNED, Census.of(List.of(young), null).plants().getFirst().origin());
	}

	@Test
	void growingMutationsSpawnedAndCropsArePlanted() throws IOException {
		// The user's Plot 4 on 27 Oct: 15 Jellybeans at 39-45 standing where the layout keeps Jellybeans. Placed ones
		// come out fully grown, so these spawned (and their harvests count for "16 x Magic Jellybean harvested")
		Saved plot4 = saved("alpha.hypixel.net|-5,91");
		for (Census.Plant plant : plot4.census().plants()) {
			if (plant.id().equals("magic_jellybean") && plant.stages().hi() < 119) assertEquals(StageTracker.Origin.SPAWNED, plant.origin());
			if (!plant.kind().mutation() && !plant.dead()) assertEquals(StageTracker.Origin.PLANTED, plant.origin(), plant.id());
		}
		assertEquals(StageTracker.Origin.PLACED, StageTracker.settle(PLANTS.byId("magic_jellybean"), StageTracker.Origin.PLACED, StageSet.of(40)));
		assertEquals(StageTracker.Origin.UNKNOWN, StageTracker.settle(PLANTS.byId("magic_jellybean"), StageTracker.Origin.UNKNOWN, StageSet.of(119)));
		assertEquals(StageTracker.Origin.PLANTED, StageTracker.settle(PLANTS.byId("wheat"), StageTracker.Origin.SPAWNED, StageSet.of(3)));
	}

	@Test
	void commonsAndGrownCropsAreReadyToHarvest() throws IOException {
		Saved plot12 = saved("alpha.hypixel.net|-5,187");
		Census census = plot12.census();
		Census.Plant shadevine = census.plants().stream().filter(p -> p.id().equals("shadevine")
				&& Census.onTarget(p.cells().getFirst(), plot12.layout())).findFirst().orElseThrow();
		assertTrue(Harvest.ready(shadevine, plot12.layout()), "a Shadevine is grown as it spawns");
		Census.Plant rose = census.plants().stream().filter(p -> p.id().equals("wild_rose") && p.stages().lo() >= 15).findFirst().orElseThrow();
		assertTrue(Harvest.ready(rose, plot12.layout()), "a fully grown Wild Rose");
		Census.Plant seedling = census.plants().stream().filter(p -> p.id().equals("wild_rose") && p.stages().hi() < 15).findFirst().orElseThrow();
		assertFalse(Harvest.ready(seedling, plot12.layout()));
		// "Harvest base crops" off: base crops are left to grow, mutations still count
		assertFalse(Harvest.ready(rose, plot12.layout(), false), "a grown Wild Rose is left to grow");
		assertTrue(Harvest.ready(shadevine, plot12.layout(), false), "the Shadevine is still for harvest");
	}

	@Test
	void devourerRingIsOnlyAskedForWhileASpotIsEmpty() {
		int devourer = SkyblockerCrops.bySimId("devourer").id();
		int puff = SkyblockerCrops.bySimId("puffercloud").id();
		int zombud = SkyblockerCrops.bySimId("zombud").id();
		int[][] grid = new int[10][10];
		for (int[] column : grid) java.util.Arrays.fill(column, -1);
		Map<Integer, Integer> placements = new HashMap<>();
		// Spot (1,1) with 4 Pufferclouds on its sides and 4 Zombuds on its corners
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
			if (dx == 0 && dz == 0) continue;
			int id = dx == 0 || dz == 0 ? puff : zombud;
			placements.put(Schematic.cell(1 + dx, 1 + dz), id);
			grid[1 + dx][1 + dz] = id;
		}
		grid[1][1] = devourer;
		Schematic layout = new Schematic(grid, Map.of(Schematic.cell(1, 1), devourer), placements, "", Schematic.Source.SKY_SHARDS);
		java.util.function.IntFunction<String> soil = cell -> cell % 2 == 0 ? "farmland" : "soul_sand";

		// Empty spot, ring destroyed: every input is asked for
		List<PlotOverlay.Mark> empty = PlotOverlay.plan(layout, Census.of(List.of(), layout), PLANTS, soil, Set.of(), OPTIONS);
		assertEquals(8, empty.stream().filter(m -> m.action() == Action.PLACE && !m.filler() && placements.containsKey(m.anchor())).count());

		// A Devourer growing there: the destroyed ring cells get fillers instead, by soil
		StageTracker.Tracked growing = new StageTracker.Tracked(Schematic.cell(1, 1), PLANTS.byId("devourer"), StageTracker.Origin.SPAWNED, StageSet.of(5),
				true, true, false, false, false, "", 0);
		List<PlotOverlay.Mark> busy = PlotOverlay.plan(layout, Census.of(List.of(growing), layout), PLANTS, soil, Set.of(), OPTIONS);
		List<PlotOverlay.Mark> ring = busy.stream().filter(m -> m.action() == Action.PLACE && placements.containsKey(m.anchor())).toList();
		assertEquals(8, ring.size());
		for (PlotOverlay.Mark mark : ring) {
			assertTrue(mark.filler(), mark.toString());
			assertEquals(PlotOverlay.FILLER.get(soil.apply(mark.anchor())), mark.want());
		}
		// A root on the plot is to be broken
		List<PlotOverlay.Mark> rooted = PlotOverlay.plan(layout, Census.of(List.of(growing), layout), PLANTS, soil, Set.of(Schematic.cell(5, 5)), OPTIONS);
		assertTrue(rooted.stream().anyMatch(m -> m.action() == Action.ROOT && m.label().equals("✂ Devourer Root")));
	}

	@Test
	void cropDiagnosticsReadingsOverrideTheTracker() {
		Diagnosis placed = Diagnosis.parse("Chorus Fruit", List.of("Age: 3d 19h 11m 17s", "Stage: FULLY GROWN", "Next Stage: FULLY GROWN",
				"Times Mutated: 2", "", "Decay Info", "Combined Mutates Remaining: 26", "Status: Uncollectable"));
		assertNotNull(placed);
		assertTrue(placed.fullyGrown());
		assertEquals(StageTracker.Origin.PLACED, placed.origin(true, true));

		Diagnosis growing = Diagnosis.parse("Glasscorn", List.of("Stage: 6/8", "Next Stage: 1h 36m 2s", "Status: Wilting"));
		assertNotNull(growing);
		assertEquals(6, growing.stage());
		assertTrue(growing.stalled());
		assertEquals(StageTracker.Origin.SPAWNED, growing.origin(true, false));
		assertNull(Diagnosis.parse("Glasscorn", List.of("Next Stage: 1h 36m 2s", "Age: 3d")), "a countdown or age is never a stage");

		// A Zombud the tracker took for spawned at 14-16 is placed and fully grown per the menu
		StageTracker tracker = new StageTracker(LookTable.of(PLANTS, List.of()));
		tracker.observe(39, TestPlants.look("zombud", -0.75), 0);
		StageTracker.Update update = tracker.diagnose(39, Diagnosis.parse("Zombud", List.of("Stage: FULLY GROWN", "Status: Uncollectable")), 1);
		assertNotNull(update);
		assertEquals(StageTracker.Origin.PLACED, update.after().origin());
		assertEquals(StageSet.of(16), update.after().stages());
		// A fully grown plant reading "Harvestable" keeps its origin: the user's placed Jellybean at (5,3) read that way
		StageTracker beans = new StageTracker(LookTable.of(PLANTS, List.of()));
		beans.use(53, "Magic Jellybean", 0);
		beans.observe(53, TestPlants.look("magicjellybean0", 0.5), 1);
		assertEquals(StageTracker.Origin.PLACED, beans.get(53).origin());
		StageTracker.Update bean = beans.diagnose(53, Diagnosis.parse("Magic Jellybean", List.of("Stage: FULLY GROWN", "Status: Harvestable")), 2);
		assertEquals(StageTracker.Origin.PLACED, bean.after().origin());
		assertEquals(StageSet.of(120), bean.after().stages());

		// Then a reading of a stage pins it exactly
		update = tracker.diagnose(39, Diagnosis.parse("Zombud", List.of("Stage: 12/16", "Status: Growing")), 2);
		assertEquals(StageSet.of(12), update.after().stages());
		assertEquals(StageTracker.Origin.SPAWNED, update.after().origin());
		assertTrue(update.after().exact());
	}
}
