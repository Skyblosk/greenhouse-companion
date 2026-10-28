package dev.ilyapple.ghc.core;

import static dev.ilyapple.ghc.core.TestPlants.PLANTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.core.flow.Estimator;
import dev.ilyapple.ghc.core.flow.ExitEvaluator;
import dev.ilyapple.ghc.core.flow.FlowRunner;
import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

class FlowTest {
	private static Flows flows(String name) throws IOException {
		try (InputStream in = FlowTest.class.getResourceAsStream("/flows/" + name)) {
			return Flows.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static StageTracker.Tracked plant(String id, int x, int z, StageSet stages, StageTracker.Origin origin) {
		return new StageTracker.Tracked(Schematic.cell(x, z), PLANTS.byId(id), origin, stages, true, stages.size() == 1, true, false, false, "test", 0);
	}

	@Test
	void skyShardsCodesDecodeLikeSkyblocker() throws IOException {
		Flows flows = flows("hybrid-aloe-flow.json");
		Flows.Plot plot = flows.plots().getFirst();
		assertEquals(3, plot.steps().size());
		assertTrue(plot.loop());

		Schematic hybridize = plot.steps().get(0).schematic();
		assertNotNull(hybridize);
		assertEquals(Map.of("magic_jellybean", 14), hybridize.targetCounts());
		// Row 0 is ".aabaa....": column 1 is sugar cane (Skyblocker 54), column 3 Duskbloom (14)
		assertEquals(-1, hybridize.at(0, 0));
		assertEquals(54, hybridize.at(1, 0));
		assertEquals(14, hybridize.at(3, 0));
		// Row 1 col 2 is a Jellybean target: Skyblocker shows targets as 0 (an empty spot)
		assertEquals(0, hybridize.at(2, 1));

		Schematic aloe = plot.steps().get(1).schematic();
		assertEquals(Map.of("all_in_aloe", 16), aloe.targetCounts());
		// The PlantBoy anchored at row 0 col 8 fills its 2x2 in Skyblocker's grid
		assertEquals(26, aloe.at(8, 0));
		assertEquals(26, aloe.at(9, 1));
		assertEquals(26, aloe.placements().get(Schematic.cell(8, 0)));
		assertNull(aloe.placements().get(Schematic.cell(9, 1)));
	}

	@Test
	void linksAreReadLikeSkyblockersPaste() throws IOException {
		String code = flows("hybrid-aloe-flow.json").plots().getFirst().steps().get(1).layoutCode();
		Schematic bare = Schematic.fromLink(code);
		Schematic designer = Schematic.fromLink("https://greenhouse.skyshards.com/designer?layout=" + code + "&x=1");
		Schematic share = Schematic.fromLink("https://greenhouse.skyshards.com/share/" + code);
		assertNotNull(bare);
		assertEquals(bare, designer);
		assertEquals(bare, share);
		assertNull(Schematic.fromLink("not a layout"));
	}

	@Test
	void skyMutationsLinksUseLzString() {
		// [[0,0,"Wheat Seeds",1],[1,0,"Magic Jellybean",1],[2,0,"Magic Jellybean",0],[4,4,"Glasscorn",1],[9,9,"Sugar Cane",1]], made with lz-string 1.5.0
		Schematic schematic = Schematic.fromLink("https://skymutations.example/?layout="
				+ "NrAMBoIIgdQCwKYEMAuACAyghATAzlOAIwC64wRk4UAskgOYCWAxmgFIIA2nAngEbIAdoVLkATFVoMW7LrwFJhkMsAAs4dVADinJHjzMA9gCclo4AE5wVqBgCu9JMbQBhRQhEkSQA");
		assertNotNull(schematic);
		assertEquals(Schematic.Source.SKY_MUTATIONS, schematic.source());
		// SkyMutations counts x from the other side
		assertEquals(50, schematic.at(9, 0));
		assertEquals(21, schematic.at(8, 0));
		assertEquals(0, schematic.at(7, 0));
		assertEquals(21, schematic.targets().get(Schematic.cell(7, 0)));
		assertEquals(36, schematic.at(5, 4));
		assertEquals(54, schematic.at(0, 9));
	}

	@Test
	void realFlowsParse() throws IOException {
		Flows buffs = flows("jellybean-buffs-flow.json");
		assertEquals(3, buffs.plots().size());
		Flows.Step aloe = buffs.plots().getFirst().steps().get(5);
		assertEquals("Aloe", aloe.label());
		assertEquals("plantDecayed", aloe.exits().get(1).when().getFirst().kind());
		assertTrue(aloe.exits().get(1).any());
		assertTrue(buffs.plots().getFirst().steps().get(1).exits().getFirst().checkOnEntry());

		Flows chorus = flows("chorus-flow.json");
		assertEquals(10000, chorus.startingInventory().get("chloronite"));
		// A pasted link is a one-step plot
		String code = flows("hybrid-aloe-flow.json").plots().getFirst().steps().get(0).layoutCode();
		Flows pasted = Flows.parse("https://greenhouse.skyshards.com/designer?layout=" + code + "\n" + code);
		assertEquals(2, pasted.plots().size());
		assertNotNull(pasted.plots().get(1).steps().getFirst().schematic());
	}

	/** The rule the player asked for: a Jellybean that is 107 or 108 counts as 108 for "highest stage". */
	@Test
	void stageConditionsTakeTheEndTheyAreAbout() {
		ExitEvaluator evaluator = new ExitEvaluator(PLANTS);
		Census census = Census.of(List.of(plant("magic_jellybean", 0, 0, StageSet.range(107, 108), StageTracker.Origin.SPAWNED)), null);
		ExitEvaluator.Context context = new ExitEvaluator.Context(census, null, new FlowRunner(new Flows.Plot(1, List.of(), false, 0)), new Inventory(), PLANTS);

		ExitEvaluator.Verdict highest = evaluator.condition(condition("highestStageAtLeast", "magic_jellybean", 108), context);
		assertTrue(highest.value());
		assertTrue(highest.assumed());
		assertEquals("highest 108 (107–108)", highest.detail());

		assertFalse(evaluator.condition(condition("highestStageBelow", "magic_jellybean", 108), context).value());
		ExitEvaluator.Verdict lowest = evaluator.condition(condition("lowestStageAtLeast", "magic_jellybean", 108), context);
		assertFalse(lowest.value());
		assertTrue(lowest.assumed());
		assertTrue(evaluator.condition(condition("lowestStageBelow", "magic_jellybean", 108), context).value());
		// Certain either way: no assumption flagged
		assertFalse(evaluator.condition(condition("highestStageAtLeast", "magic_jellybean", 100), context).assumed());
	}

	@Test
	void fullyGrownCountsOnlySpawnedPlants() {
		ExitEvaluator evaluator = new ExitEvaluator(PLANTS);
		Census census = Census.of(List.of(
				plant("chorus_fruit", 0, 0, StageSet.of(12), StageTracker.Origin.SPAWNED),
				plant("chorus_fruit", 0, 1, StageSet.of(12), StageTracker.Origin.PLACED),
				plant("chorus_fruit", 0, 2, StageSet.range(11, 12), StageTracker.Origin.SPAWNED)), null);
		ExitEvaluator.Context context = new ExitEvaluator.Context(census, null, new FlowRunner(new Flows.Plot(1, List.of(), false, 0)), new Inventory(), PLANTS);
		ExitEvaluator.Verdict two = evaluator.condition(condition("fullyGrown", "chorus_fruit", 0, 2), context);
		assertTrue(two.value());
		assertTrue(two.assumed());
		assertFalse(evaluator.condition(condition("fullyGrown", "chorus_fruit", 0, 3), context).value());
		// "Everything fully grown" takes the low end, so the 11–12 plant holds it back
		assertFalse(evaluator.condition(condition("allFullyGrown", "", 0), context).value());
	}

	@Test
	void theAloeStepEndsWhenEveryTargetIsFilled() throws IOException {
		Flows.Plot plot = flows("hybrid-aloe-flow.json").plots().getFirst();
		FlowRunner runner = new FlowRunner(plot);
		runner.enter(1);
		Schematic aloe = runner.step().schematic();
		ExitEvaluator evaluator = new ExitEvaluator(PLANTS);
		List<StageTracker.Tracked> plants = new ArrayList<>();
		ExitEvaluator.Context empty = new ExitEvaluator.Context(Census.of(plants, aloe), aloe, runner, new Inventory(), PLANTS);
		assertNull(runner.check(evaluator, empty));

		for (int anchor : aloe.targets().keySet()) plants.add(plant("all_in_aloe", anchor / 10, anchor % 10, StageSet.of(1), StageTracker.Origin.SPAWNED));
		ExitEvaluator.Context filled = new ExitEvaluator.Context(Census.of(plants, aloe), aloe, runner, new Inventory(), PLANTS);
		FlowRunner.Decision decision = runner.check(evaluator, filled);
		assertNotNull(decision);
		assertEquals(2, decision.target());
		runner.apply(decision);
		assertEquals("Step 3", runner.step().label());
	}

	@Test
	void inventoryExitsReadSacksPlusAdjustments() throws IOException {
		Flows.Plot plot = flows("jellybean-buffs-flow.json").plots().getFirst();
		FlowRunner runner = new FlowRunner(plot);
		Inventory inventory = new Inventory();
		inventory.setSack("thunderling", 400, 0);
		ExitEvaluator evaluator = new ExitEvaluator(PLANTS);
		ExitEvaluator.Context context = new ExitEvaluator.Context(Census.of(List.of(), null), runner.step().schematic(), runner, inventory, PLANTS);
		assertNull(runner.check(evaluator, context));
		inventory.adjust("thunderling", 62);
		assertEquals(462, inventory.count("thunderling"));
		FlowRunner.Decision decision = runner.check(evaluator, context);
		assertNotNull(decision);
		assertEquals("PBA", plot.steps().get(decision.target()).label());
		// A new sack read keeps the manual adjustment on top
		inventory.setSack("thunderling", 500, 1);
		assertEquals(562, inventory.count("thunderling"));
		inventory.set("thunderling", 10);
		assertEquals(10, inventory.count("thunderling"));
		assertEquals(inventory.count("thunderling"), Inventory.fromJson(inventory.toJson()).count("thunderling"));
	}

	@Test
	void estimatesFollowTheSimulatorsSpawnChance() throws IOException {
		assertEquals(1 / 0.2875, Estimator.orderStatistic(1, 1, 0.2875), 1e-3);
		Flows.Plot plot = flows("hybrid-aloe-flow.json").plots().getFirst();
		FlowRunner runner = new FlowRunner(plot);
		Estimator estimator = new Estimator(PLANTS);
		ExitEvaluator.Context context = new ExitEvaluator.Context(Census.of(List.of(), runner.step().schematic()), runner.step().schematic(), runner,
				new Inventory(), PLANTS);
		List<Estimator.StepEstimate> plan = estimator.plan(runner, context, 3);
		assertEquals("Hybridize", plan.getFirst().label());
		assertTrue(plan.getFirst().known());
		// 14 Jellybean spots at 0.2875 each: all filled in about 10 cycles
		assertTrue(plan.getFirst().cycles() > 5 && plan.getFirst().cycles() < 15, Double.toString(plan.getFirst().cycles()));
		assertEquals("Aloe", plan.get(1).label());
	}

	@Test
	void everyLayoutCropIsAPlant() {
		for (SkyblockerCrops.Crop crop : SkyblockerCrops.ALL) {
			if (crop.simId() != null) assertNotNull(PLANTS.byId(crop.simId()), crop.name());
			if (crop.simId() != null) assertEquals(PLANTS.byId(crop.simId()), PLANTS.byName(crop.name()), crop.name());
		}
	}

	private static Flows.Condition condition(String kind, String mutationId, int stage) {
		return condition(kind, mutationId, stage, 1);
	}

	private static Flows.Condition condition(String kind, String mutationId, int stage, int count) {
		com.google.gson.JsonObject json = new com.google.gson.JsonObject();
		json.addProperty("kind", kind);
		json.addProperty("mutationId", mutationId);
		json.addProperty("stage", stage);
		json.addProperty("count", count);
		return new Flows.Condition(kind, json, List.of(), false);
	}

	@Test
	void anInventoryAmountCanBeChangedInPlace() throws java.io.IOException {
		String text;
		try (var in = java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/assets/greenhouse-companion/leaderboard/leaderboard.json"))) {
			text = dev.ilyapple.ghc.core.leaderboard.Leaderboard.read(in).entries().stream().filter(e -> e.mutation().equals("zombud")).findFirst().orElseThrow().ironmanFlow();
		}
		Flows flows = Flows.parse(text);
		Flows.Plot plot = flows.plots().getFirst();
		Flows.Step step = plot.steps().getFirst();
		int exit = 0;
		Flows.Condition before = step.exits().get(exit).when().getFirst();
		org.junit.jupiter.api.Assertions.assertTrue(Flows.hasQuantity(before), before.kind());
		String edited = Flows.withQuantity(text, plot.id(), step.id(), exit, 0, 250);
		Flows after = Flows.parse(edited);
		org.junit.jupiter.api.Assertions.assertEquals(250, after.plots().getFirst().steps().getFirst().exits().get(exit).when().getFirst().integer("qty", 0));
		// Only that plot's condition changed
		org.junit.jupiter.api.Assertions.assertEquals(before.integer("qty", 0), after.plots().get(1).steps().getFirst().exits().get(exit).when().getFirst().integer("qty", 0));
	}

	@Test
	void theMutationCountToSwapAtCanBeTyped() throws java.io.IOException {
		// The user's hybrid Shadevine flow: plot 3 swaps once 8 Jellybeans have spawned
		String text = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/greenhouse-companion/flows/hybrid-shadevine.json"));
		Flows.Plot plot = Flows.parse(text).plots().get(2);
		Flows.Condition spawned = plot.steps().getFirst().exits().getFirst().when().getFirst();
		org.junit.jupiter.api.Assertions.assertEquals("count", Flows.amountField(spawned));
		org.junit.jupiter.api.Assertions.assertEquals(8, spawned.integer("count", 1));
		String edited = Flows.withAmount(text, plot.id(), plot.steps().getFirst().id(), 0, 0, "count", 12);
		Flows after = Flows.parse(edited);
		org.junit.jupiter.api.Assertions.assertEquals(12, after.plots().get(2).steps().getFirst().exits().getFirst().when().getFirst().integer("count", 1));
		org.junit.jupiter.api.Assertions.assertEquals(16, after.plots().getFirst().steps().getFirst().exits().getFirst().when().getFirst().integer("count", 1),
				"other plots keep theirs");
		// A count can't go below 1, and a cycles condition has its own field
		String zero = Flows.withAmount(text, plot.id(), plot.steps().getFirst().id(), 0, 0, "count", 0);
		org.junit.jupiter.api.Assertions.assertEquals(1, Flows.parse(zero).plots().get(2).steps().getFirst().exits().getFirst().when().getFirst().integer("count", 1));
		org.junit.jupiter.api.Assertions.assertThrows(com.google.gson.JsonParseException.class,
				() -> Flows.withAmount(text, plot.id(), plot.steps().getFirst().id(), 0, 0, "qty", 5));
		org.junit.jupiter.api.Assertions.assertEquals("n", Flows.amountField(new Flows.Condition("cycles", new com.google.gson.JsonObject(), List.of(), false)));
	}
}
