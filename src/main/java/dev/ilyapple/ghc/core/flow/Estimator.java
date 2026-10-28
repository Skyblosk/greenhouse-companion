package dev.ilyapple.ghc.core.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.StageTracker.Origin;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

/**
 * Rough "how long until this step ends" for the current step and the ones after it.
 *
 * <p>Growth is exact: a plant at stage s needs {@code ready - s} ticks, using the same most-restrictive end of
 * its range as {@link ExitEvaluator}. Spawning is the simulator's chance: each empty target cell rolls
 * {@code spawnWeight / 100 * spawnBonus} per cycle (0.2875 for weight 25 with the simulator's default 1.15 bonus),
 * assuming the layout gives the mutation its neighbours. Conditions the mod can't foresee (inventory, decay,
 * scripts) give no estimate unless they already hold, and the path stops at that step.
 */
public final class Estimator {
	public static final double DEFAULT_SPAWN_BONUS = 1.15;
	/** Steps whose estimate would be longer than this are shown as unknown. */
	private static final double NEVER = 1e9;

	/**
	 * @param cycles    expected cycles until the step ends (for the current step: from now), {@link Double#POSITIVE_INFINITY} if unknown
	 * @param exitIndex the exit expected to fire, or -1
	 * @param reason    the condition that sets the estimate, in the simulator's words
	 */
	public record StepEstimate(int stepIndex, String label, double cycles, int exitIndex, String reason) {
		public boolean known() {
			return Double.isFinite(cycles);
		}
	}

	/** What the estimate works from: a census of plants plus the step's layout and counters. */
	private record Plot(List<Census.Plant> plants, Schematic layout, int cyclesInStep, Map<String, Integer> harvested,
			Map<String, Integer> spawned, Map<String, Integer> filledTargets) {}

	private final Plants plants;
	private final double spawnBonus;

	public Estimator(Plants plants, double spawnBonus) {
		this.plants = plants;
		this.spawnBonus = spawnBonus;
	}

	public Estimator(Plants plants) {
		this(plants, DEFAULT_SPAWN_BONUS);
	}

	/** The current step from now, then up to {@code lookahead} more steps along the exits expected to fire first. */
	public List<StepEstimate> plan(FlowRunner runner, ExitEvaluator.Context context, int lookahead) {
		List<StepEstimate> plan = new ArrayList<>();
		Flows.Plot flow = runner.plot();
		if (flow.steps().isEmpty()) return plan;
		Schematic layout = runner.step().schematic();
		Map<String, Integer> filled = new TreeMap<>();
		if (layout != null) {
			for (Map.Entry<Integer, Integer> target : layout.targets().entrySet()) {
				SkyblockerCrops.Crop crop = SkyblockerCrops.byId(target.getValue());
				if (crop != null && crop.simId() != null && context.census().targetFilled(target.getKey(), target.getValue())) {
					filled.merge(crop.simId(), 1, Integer::sum);
				}
			}
		}
		Map<String, Integer> harvested = new TreeMap<>();
		Map<String, Integer> spawned = new TreeMap<>();
		for (Plants.Plant plant : plants.all()) {
			if (runner.harvested(plant.id()) > 0) harvested.put(plant.id(), runner.harvested(plant.id()));
			if (runner.spawned(plant.id()) > 0) spawned.put(plant.id(), runner.spawned(plant.id()));
		}
		Plot now = new Plot(context.census().plants(), layout == null ? empty() : layout, runner.cyclesInStep(), harvested, spawned, filled);

		int index = runner.stepIndex();
		StepEstimate current = estimate(flow, index, now, context);
		plan.add(current);
		for (int i = 0; i < lookahead && current.known() && current.exitIndex() >= 0; i++) {
			int next = targetOf(flow, index, current.exitIndex());
			if (next < 0) break;
			index = next;
			Flows.Step step = flow.steps().get(index);
			current = estimate(flow, index, fresh(step), context);
			plan.add(current);
		}
		return plan;
	}

	private static int targetOf(Flows.Plot flow, int index, int exitIndex) {
		Flows.Exit exit = flow.steps().get(index).exits().get(exitIndex);
		if (exit.to() != null) return flow.indexOf(exit.to());
		if (index == flow.steps().size() - 1 && !flow.loop()) return -1;
		return (index + 1) % flow.steps().size();
	}

	/** A step just entered: its layout placed (fully grown, as placed mutations are), nothing spawned yet. */
	private Plot fresh(Flows.Step step) {
		Schematic layout = step.schematic() == null ? empty() : step.schematic();
		List<Census.Plant> list = new ArrayList<>();
		for (Map.Entry<Integer, Integer> placement : layout.placements().entrySet()) {
			SkyblockerCrops.Crop crop = SkyblockerCrops.byId(placement.getValue());
			Plants.Plant plant = crop == null || crop.simId() == null ? null : plants.byId(crop.simId());
			if (plant == null) continue;
			list.add(new Census.Plant(plant, List.of(placement.getKey()), plant.mutation() ? dev.ilyapple.ghc.core.StageSet.of(plant.maxStage())
					: dev.ilyapple.ghc.core.StageSet.of(plant.allStages().lo()), plant.mutation() ? Origin.PLACED : Origin.PLANTED, true, true));
		}
		return new Plot(list, layout, 0, Map.of(), Map.of(), Map.of());
	}

	private StepEstimate estimate(Flows.Plot flow, int index, Plot plot, ExitEvaluator.Context context) {
		Flows.Step step = flow.steps().get(index);
		double best = Double.POSITIVE_INFINITY;
		int bestExit = -1;
		String reason = step.exits().isEmpty() ? "no exit" : "can't tell";
		for (int i = 0; i < step.exits().size(); i++) {
			Flows.Exit exit = step.exits().get(i);
			if (targetOf(flow, index, i) < 0 && exit.to() == null && index == flow.steps().size() - 1 && !flow.loop()) continue;
			double cycles = exit.any() ? Double.POSITIVE_INFINITY : 0;
			String why = "";
			for (Flows.Condition condition : exit.when()) {
				double c = cycles(condition, plot, context);
				if (exit.any() ? c < cycles : c > cycles) {
					cycles = c;
					why = describe(condition);
				}
			}
			if (exit.when().isEmpty()) cycles = Double.POSITIVE_INFINITY;
			if (cycles < best) {
				best = cycles;
				bestExit = i;
				reason = why;
			}
		}
		if (best >= NEVER) best = Double.POSITIVE_INFINITY;
		if (!Double.isFinite(best) && !step.exits().isEmpty() && !step.exits().getFirst().when().isEmpty()) {
			// Say what it waits on: the first exit's conditions
			reason = "waits on " + String.join(" and ", step.exits().getFirst().when().stream().map(this::describe).toList());
		}
		return new StepEstimate(index, step.label(), best, Double.isFinite(best) ? bestExit : -1, reason);
	}

	/** Expected cycles until one condition holds, from the state in {@code plot}. */
	private double cycles(Flows.Condition condition, Plot plot, ExitEvaluator.Context context) {
		String id = condition.mutationId();
		int stage = condition.integer("stage", 0);
		int count = condition.integer("count", 1);
		Plants.Plant kind = plants.byId(id);
		List<Census.Plant> ofKind = plot.plants().stream().filter(plant -> plant.id().equals(id)).toList();
		int targets = plot.layout().targetCounts().getOrDefault(id, 0);
		int emptyTargets = Math.max(0, targets - plot.filledTargets().getOrDefault(id, 0));
		double p = kind == null ? 0 : spawnChance(kind);
		return switch (condition.kind()) {
			case "group" -> {
				double result = condition.any() ? Double.POSITIVE_INFINITY : 0;
				for (Flows.Condition part : condition.of()) {
					double c = cycles(part, plot, context);
					result = condition.any() ? Math.min(result, c) : Math.max(result, c);
				}
				yield result;
			}
			case "cycles" -> Math.max(0, condition.integer("n", 0) - plot.cyclesInStep());
			case "highestStageAtLeast" -> {
				int high = ofKind.stream().mapToInt(plant -> plant.stages().hi()).max().orElse(-1);
				if (high >= stage) yield 0;
				double fromExisting = high >= 1 && kind != null && kind.grows() ? stage - high : Double.POSITIVE_INFINITY;
				double fromSpawn = emptyTargets > 0 ? orderStatistic(emptyTargets, 1, p) + stage - 1 : Double.POSITIVE_INFINITY;
				yield Math.min(fromExisting, fromSpawn);
			}
			case "lowestStageAtLeast" -> {
				if (ofKind.isEmpty()) yield emptyTargets > 0 ? orderStatistic(emptyTargets, emptyTargets, p) + stage - 1 : Double.POSITIVE_INFINITY;
				int low = ofKind.stream().mapToInt(plant -> plant.stages().lo()).min().orElse(0);
				double grow = Math.max(0, stage - low);
				yield emptyTargets > 0 ? Math.max(grow, orderStatistic(emptyTargets, emptyTargets, p) + stage - 1) : grow;
			}
			case "fullyGrown" -> {
				if (kind == null) yield Double.POSITIVE_INFINITY;
				List<Integer> waits = new ArrayList<>();
				for (Census.Plant plant : ofKind) {
					if (plant.origin() == Origin.SPAWNED) waits.add(Math.max(0, kind.readyStage() - plant.stages().hi()));
				}
				waits.sort(Integer::compare);
				int needed = Math.max(1, count);
				if (waits.size() >= needed) yield waits.get(needed - 1);
				int more = needed - waits.size();
				if (more > emptyTargets) yield Double.POSITIVE_INFINITY;
				yield orderStatistic(emptyTargets, more, p) + kind.readyStage() - 1;
			}
			case "allFullyGrown" -> {
				double worst = 0;
				for (Census.Plant plant : plot.plants()) {
					if (plant.grownOnPlot()) worst = Math.max(worst, plant.kind().readyStage() - plant.stages().lo());
				}
				yield worst;
			}
			case "targetsFilled" -> {
				int total = plot.layout().targets().size();
				if (total == 0) yield Double.POSITIVE_INFINITY;
				int filled = plot.filledTargets().values().stream().mapToInt(Integer::intValue).sum();
				int wanted = condition.integer("count", 0) <= 0 ? total : condition.integer("count", 0);
				if (filled >= wanted) yield 0;
				yield fillTime(plot, wanted - filled);
			}
			case "mutationHarvested", "mutationSpawned" -> {
				if (kind == null) yield Double.POSITIVE_INFINITY;
				boolean harvest = condition.kind().equals("mutationHarvested");
				int done = (harvest ? plot.harvested() : plot.spawned()).getOrDefault(id, 0);
				int remaining = count - done;
				if (remaining <= 0) yield 0;
				// Plants already growing come first (spawned here, or already growing when first seen, as when a step
				// keeps last step's spawns as layout plants), then each target cell repeats spawn → grow → harvest
				List<Integer> waits = new ArrayList<>();
				if (harvest) {
					for (Census.Plant plant : ofKind) {
						if (plant.origin() != Origin.PLACED && plant.kind().grows()) waits.add(Math.max(0, kind.readyStage() - plant.stages().hi()));
					}
				}
				waits.sort(Integer::compare);
				if (waits.size() >= remaining) yield waits.get(remaining - 1);
				if (targets == 0 || p <= 0) yield Double.POSITIVE_INFINITY;
				int more = remaining - waits.size();
				double perCell = 1 / p + (harvest ? kind.readyStage() : 0);
				double start = waits.isEmpty() ? 0 : waits.getLast();
				yield Math.max(start, Math.ceil((double) more / targets) * perCell);
			}
			case "inventoryAtLeast", "collectedAtLeast" ->
					context.inventory().count(condition.string("item")) >= condition.integer("qty", 0) ? 0 : Double.POSITIVE_INFINITY;
			case "inventoryBelow" -> context.inventory().count(condition.string("item")) < condition.integer("qty", 0) ? 0 : Double.POSITIVE_INFINITY;
			case "highestStageBelow", "lowestStageBelow", "noneFullyGrown", "layoutShort", "plantDecayed", "stepVisits", "decayImminent", "script" ->
					// Either true already or not something growth leads to
					plot.plants() == context.census().plants() && new ExitEvaluator(plants).condition(condition, context).value() ? 0 : Double.POSITIVE_INFINITY;
			default -> Double.POSITIVE_INFINITY;
		};
	}

	/** Expected cycles until {@code needed} more targets are filled, each kind spawning at its own chance. */
	private double fillTime(Plot plot, int needed) {
		List<Double> chances = new ArrayList<>();
		Map<String, Integer> counts = plot.layout().targetCounts();
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			Plants.Plant kind = plants.byId(entry.getKey());
			int empty = Math.max(0, entry.getValue() - plot.filledTargets().getOrDefault(entry.getKey(), 0));
			for (int i = 0; i < empty; i++) chances.add(kind == null ? 0 : spawnChance(kind));
		}
		if (chances.size() < needed) return Double.POSITIVE_INFINITY;
		// E[T] = Σ P(T > k): the chance that fewer than `needed` cells have spawned by cycle k
		double expected = 0;
		for (int k = 0; k < 100_000; k++) {
			double[] filled = new double[needed + 1];
			filled[0] = 1;
			for (double p : chances) {
				double q = 1 - Math.pow(1 - p, k);
				for (int j = needed; j >= 0; j--) filled[j] = filled[j] * (1 - q) + (j > 0 ? filled[j - 1] * q : 0);
			}
			double fewer = 0;
			for (int j = 0; j < needed; j++) fewer += filled[j];
			expected += fewer;
			if (fewer < 1e-6) break;
		}
		return expected;
	}

	/** Expected cycles until {@code k} of {@code n} independent cells have spawned, each with chance {@code p} per cycle. */
	public static double orderStatistic(int n, int k, double p) {
		if (k <= 0) return 0;
		if (k > n || p <= 0) return Double.POSITIVE_INFINITY;
		double expected = 0;
		for (int cycle = 0; cycle < 100_000; cycle++) {
			double q = 1 - Math.pow(1 - p, cycle);
			double fewer = 0;
			for (int j = 0; j < k; j++) fewer += binomial(n, j) * Math.pow(q, j) * Math.pow(1 - q, n - j);
			expected += fewer;
			if (fewer < 1e-6) break;
		}
		return expected;
	}

	private static double binomial(int n, int k) {
		double result = 1;
		for (int i = 1; i <= k; i++) result = result * (n - k + i) / i;
		return result;
	}

	public double spawnChance(Plants.Plant plant) {
		return Math.min(1, plant.spawnWeight() / 100.0 * spawnBonus);
	}

	private String describe(Flows.Condition condition) {
		Plants.Plant plant = plants.byId(condition.mutationId());
		String name = plant == null ? condition.mutationId() : plant.name();
		return switch (condition.kind()) {
			case "highestStageAtLeast" -> "some " + name + " at stage " + condition.integer("stage", 0) + "+";
			case "lowestStageAtLeast" -> "every " + name + " at stage " + condition.integer("stage", 0) + "+";
			case "fullyGrown" -> condition.integer("count", 1) + " x " + name + " fully grown";
			case "mutationHarvested" -> condition.integer("count", 1) + " x " + name + " harvested";
			case "mutationSpawned" -> condition.integer("count", 1) + " x " + name + " spawned";
			case "targetsFilled" -> condition.integer("count", 0) <= 0 ? "every target filled" : condition.integer("count", 0) + " targets filled";
			case "cycles" -> condition.integer("n", 0) + " cycles in step";
			case "allFullyGrown" -> "everything fully grown";
			case "inventoryAtLeast" -> "inventory " + name(condition.string("item")) + " >= " + condition.integer("qty", 0);
			case "inventoryBelow" -> "inventory " + name(condition.string("item")) + " < " + condition.integer("qty", 0);
			case "plantDecayed" -> "a " + name(condition.string("kindId")) + " decaying";
			case "layoutShort" -> "running short for the layout";
			default -> condition.kind();
		};
	}

	private String name(String id) {
		Plants.Plant plant = plants.byId(id);
		return plant == null ? id : plant.name();
	}

	private static Schematic empty() {
		int[][] grid = new int[10][10];
		for (int[] column : grid) java.util.Arrays.fill(column, -1);
		return new Schematic(grid, Map.of(), Map.of(), "", Schematic.Source.SKY_SHARDS);
	}
}
