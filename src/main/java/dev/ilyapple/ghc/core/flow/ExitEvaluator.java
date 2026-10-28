package dev.ilyapple.ghc.core.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.ilyapple.ghc.core.Inventory;
import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.StageSet;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

/**
 * Decides the simulator's exit conditions from tracked plants, with the same meaning the simulator gives them.
 *
 * <p>The tracker often knows a stage only to within two or three stages. Instead of asking for the Plant
 * Diagnostics Tool, a condition is decided at the end of the range it is about: <b>highest</b>-stage conditions
 * and "some plant" conditions take each plant's highest possible stage, <b>lowest</b>-stage conditions and
 * "every plant" conditions take the lowest. A Magic Jellybean at 107–108 is at 108 for "highest stage at least
 * 108", so the swap happens as soon as it may be due. {@link Verdict#assumed} says the other end would have
 * decided differently, so the UI can show the assumption.
 */
public final class ExitEvaluator {
	/** Everything an exit can look at. {@code layout} is the current step's schematic. */
	public record Context(Census census, @Nullable Schematic layout, FlowRunner runner, Inventory inventory, Plants plants) {}

	/**
	 * @param value     whether the condition holds
	 * @param assumed   the plants' stages were known only to a range, and the other end of it gives the other answer
	 * @param supported false for conditions the mod can't follow in game (scripts, decay timers); those never hold
	 * @param text      the condition in the simulator's words
	 * @param detail    what the mod saw, e.g. "highest 108 (107–108)"
	 */
	public record Verdict(boolean value, boolean assumed, boolean supported, String text, String detail) {}

	private final Plants plants;

	public ExitEvaluator(Plants plants) {
		this.plants = plants;
	}

	/** One verdict per condition of the exit. */
	public List<Verdict> exit(Flows.Exit exit, Context context) {
		List<Verdict> verdicts = new ArrayList<>();
		for (Flows.Condition condition : exit.when()) verdicts.add(condition(condition, context));
		return verdicts;
	}

	public Verdict condition(Flows.Condition condition, Context context) {
		String id = condition.mutationId();
		String name = name(id);
		int stage = condition.integer("stage", 0);
		int count = condition.integer("count", 1);
		Census census = context.census();
		FlowRunner runner = context.runner();
		return switch (condition.kind()) {
			case "group" -> {
				List<Verdict> parts = new ArrayList<>();
				for (Flows.Condition part : condition.of()) parts.add(condition(part, context));
				boolean value = !parts.isEmpty() && (condition.any() ? parts.stream().anyMatch(Verdict::value) : parts.stream().allMatch(Verdict::value));
				boolean assumed = parts.stream().anyMatch(Verdict::assumed);
				yield new Verdict(value, assumed, parts.stream().allMatch(Verdict::supported), (condition.any() ? "any of " : "all of ") + parts.size(),
						String.join("; ", parts.stream().map(part -> part.text() + (part.value() ? " ✔" : " ✘")).toList()));
			}
			case "highestStageAtLeast", "highestStageBelow" -> {
				List<Census.Plant> list = census.of(id);
				int high = list.stream().mapToInt(plant -> plant.stages().hi()).max().orElse(-1);
				int low = list.stream().mapToInt(plant -> plant.stages().lo()).max().orElse(-1);
				boolean atLeast = condition.kind().equals("highestStageAtLeast");
				boolean value = atLeast ? high >= stage : high < stage;
				boolean other = atLeast ? low >= stage : low < stage;
				yield new Verdict(value, value != other, true,
						atLeast ? "some " + name + " at stage " + stage + "+" : "every " + name + " below stage " + stage + " (or none)",
						list.isEmpty() ? "no " + name : "highest " + high + range(low, high));
			}
			case "lowestStageAtLeast", "lowestStageBelow" -> {
				List<Census.Plant> list = census.of(id);
				int low = list.stream().mapToInt(plant -> plant.stages().lo()).min().orElse(-1);
				int high = list.stream().mapToInt(plant -> plant.stages().hi()).min().orElse(-1);
				boolean all = !list.isEmpty() && low >= stage;
				boolean allOther = !list.isEmpty() && high >= stage;
				boolean atLeast = condition.kind().equals("lowestStageAtLeast");
				boolean value = atLeast == all;
				boolean other = atLeast == allOther;
				yield new Verdict(value, value != other, true,
						atLeast ? "every " + name + " at stage " + stage + "+" : "some " + name + " below stage " + stage + " (or none)",
						list.isEmpty() ? "no " + name : "lowest " + low + range(low, high));
			}
			case "fullyGrown" -> {
				int needed = Math.max(1, count);
				List<Census.Plant> spawned = census.of(id).stream().filter(plant -> plant.origin() == dev.ilyapple.ghc.core.StageTracker.Origin.SPAWNED).toList();
				int maybe = (int) spawned.stream().filter(plant -> plant.stages().hi() >= plant.kind().readyStage()).count();
				int surely = (int) spawned.stream().filter(plant -> plant.stages().lo() >= plant.kind().readyStage()).count();
				boolean value = maybe >= needed;
				yield new Verdict(value, value != surely >= needed, true,
						needed > 1 ? needed + " x " + name + " fully grown" : name + " fully grown",
						maybe + (maybe != surely ? " (" + surely + " certain)" : "") + " of " + spawned.size() + " spawned fully grown");
			}
			case "allFullyGrown", "noneFullyGrown" -> {
				List<Census.Plant> grown = census.plants().stream().filter(Census.Plant::grownOnPlot).toList();
				int maybe = (int) grown.stream().filter(plant -> plant.stages().hi() >= plant.kind().readyStage()).count();
				int surely = (int) grown.stream().filter(plant -> plant.stages().lo() >= plant.kind().readyStage()).count();
				if (condition.kind().equals("allFullyGrown")) {
					boolean value = !grown.isEmpty() && surely == grown.size();
					boolean other = !grown.isEmpty() && maybe == grown.size();
					yield new Verdict(value, value != other, true, "everything fully grown", surely + " of " + grown.size() + " fully grown");
				}
				boolean value = maybe == 0;
				yield new Verdict(value, value != (surely == 0), true, "nothing fully grown", maybe + " of " + grown.size() + " may be fully grown");
			}
			case "mutationHarvested" -> counter(runner.harvested(id), count, count + " x " + name + " harvested", "harvested");
			case "mutationSpawned" -> counter(runner.spawned(id), count, count + " x " + name + " spawned", "spawned");
			case "plantDecayed" -> {
				String kind = condition.string("kindId");
				yield counter(runner.decayed(kind), 1, "a " + name(kind) + " decayed", "decayed");
			}
			case "cycles" -> {
				int n = condition.integer("n", 0);
				yield new Verdict(runner.cyclesInStep() >= n, false, true, n + " cycles in step", runner.cyclesInStep() + " so far");
			}
			case "inventoryAtLeast", "inventoryBelow", "collectedAtLeast" -> {
				String item = condition.string("item");
				int qty = condition.integer("qty", 0);
				int have = context.inventory().count(item);
				boolean below = condition.kind().equals("inventoryBelow");
				String text = condition.kind().equals("collectedAtLeast") ? "collected " + name(item) + " >= " + qty + " in total"
						: "inventory " + name(item) + (below ? " < " : " >= ") + qty;
				yield new Verdict(below ? have < qty : have >= qty, false, true, text, "have " + have);
			}
			case "layoutShort" -> {
				Map<String, Integer> missing = missingInputs(context);
				boolean short_ = missing.entrySet().stream().anyMatch(entry -> context.inventory().count(entry.getKey()) < entry.getValue());
				yield new Verdict(short_, false, true, "inventory can't fill the layout", missing.isEmpty() ? "layout is filled" : "needs " + missing);
			}
			case "targetsFilled" -> {
				Schematic layout = context.layout();
				int total = layout == null ? 0 : layout.targets().size();
				int filled = 0;
				if (layout != null) {
					for (Map.Entry<Integer, Integer> target : layout.targets().entrySet()) {
						if (census.targetFilled(target.getKey(), target.getValue())) filled++;
					}
				}
				int wanted = condition.integer("count", 0);
				boolean value = total > 0 && (wanted <= 0 ? filled == total : filled >= wanted);
				yield new Verdict(value, false, true, wanted <= 0 ? "every target filled" : wanted + " targets filled", filled + " of " + total + " filled");
			}
			case "stepVisits" -> {
				int visits = runner.visits(condition.json().has("sinceStep") ? condition.string("sinceStep") : null);
				int needed = Math.max(1, count);
				yield new Verdict(visits >= needed, false, true, "entered this step " + needed + " times", visits + " so far");
			}
			case "decayImminent" -> new Verdict(false, false, false, "something decays within " + condition.integer("withinCycles", 0) + " cycles",
					"decay timers can't be seen in game");
			case "script" -> new Verdict(false, false, false, "script: " + condition.string("expr"), "plot scripts don't run in game");
			default -> new Verdict(false, false, false, condition.kind(), "unknown condition");
		};
	}

	private static Verdict counter(int have, int needed, String text, String what) {
		return new Verdict(have >= needed, false, true, text, have + " " + what + " in this step");
	}

	/** Layout inputs not yet in place, by simulator id: what re-placing the layout would cost. */
	public static Map<String, Integer> missingInputs(Context context) {
		Map<String, Integer> missing = new java.util.TreeMap<>();
		Schematic layout = context.layout();
		if (layout == null) return missing;
		for (Map.Entry<Integer, Integer> placement : layout.placements().entrySet()) {
			SkyblockerCrops.Crop crop = SkyblockerCrops.byId(placement.getValue());
			if (crop == null || crop.simId() == null) continue;
			Plants.Plant plant = context.plants().byId(crop.simId());
			if (plant == null || !plant.mutation()) continue;
			var cell = context.census().cell(placement.getKey());
			if (cell == null || !cell.plant().id().equals(crop.simId())) missing.merge(crop.simId(), 1, Integer::sum);
		}
		return missing;
	}

	private String name(String id) {
		Plants.Plant plant = plants.byId(id);
		return plant == null ? id : plant.name();
	}

	private static String range(int low, int high) {
		return low == high ? "" : " (" + StageSet.range(Math.min(low, high), Math.max(low, high)) + ")";
	}
}
