package dev.ilyapple.ghc.core;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonObject;

import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.core.flow.Estimator;
import dev.ilyapple.ghc.core.flow.ExitEvaluator;
import dev.ilyapple.ghc.core.flow.FlowRunner;
import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.schematic.Schematic;

/**
 * One greenhouse: its plants' stages and, when a flow plot is assigned to it, where it is in that flow. The
 * game side feeds it looks, right-clicks and ticks; the UI reads {@link #status}.
 */
public final class Greenhouse {
	/**
	 * What the UI shows.
	 *
	 * @param decision the exit that holds right now, if any; {@link #update} has already moved to its step
	 * @param verdicts one list per exit of the current step, in order
	 * @param plan     the current step's estimate from now, then the steps after it
	 */
	public record Status(@Nullable FlowRunner runner, Flows.@Nullable Step step, Census census, List<List<ExitEvaluator.Verdict>> verdicts,
			FlowRunner.@Nullable Decision decision, List<Estimator.StepEstimate> plan, boolean swapPending) {}

	/** A step change for the player: swap the greenhouse to {@code to}'s layout. */
	public record Swap(Flows.Step from, Flows.Step to, boolean finished, List<ExitEvaluator.Verdict> because) {}

	private final StageTracker tracker;
	private final Water water = new Water();
	private final Plants plants;
	private final ExitEvaluator evaluator;
	private final Estimator estimator;
	private final Inventory inventory;
	private @Nullable FlowRunner runner;
	private boolean swapPending;

	public Greenhouse(LookTable table, Inventory inventory) {
		this.tracker = new StageTracker(table);
		this.plants = table.plants();
		this.evaluator = new ExitEvaluator(plants);
		this.estimator = new Estimator(plants);
		this.inventory = inventory;
	}

	public StageTracker tracker() {
		return tracker;
	}

	public Water water() {
		return water;
	}

	/** The stage tracker's save, with each crop's water in it. */
	public JsonObject saveState() {
		JsonObject json = tracker.toJson();
		json.add("water", water.toJson());
		return json;
	}

	public void loadState(JsonObject json) {
		tracker.load(json);
		water.load(json.has("water") && json.get("water").isJsonObject() ? json.getAsJsonObject("water") : null);
	}

	public @Nullable FlowRunner runner() {
		return runner;
	}

	/** Follows {@code plot} from its start step, or stops following when {@code null}. */
	public void follow(Flows.@Nullable Plot plot) {
		runner = plot == null ? null : new FlowRunner(plot);
		swapPending = runner != null;
	}

	public void tick(int min, int max) {
		tracker.tick(min, max);
		if (runner != null) runner.onTicks(max);
	}

	public void use(int cell, String heldItem, long ms) {
		tracker.use(cell, heldItem, ms);
	}

	/** A cell's look, as the recorder fingerprints it. Counts spawns, harvests and decays for the flow. */
	public StageTracker.Update observe(int cell, JsonObject fingerprint, long ms) {
		Integer emptyFor = tracker.emptyFor(cell);
		StageTracker.Update update = tracker.observe(cell, fingerprint, ms);
		StageTracker.Tracked now = update.after();
		if (now == null) {
			if (update.removed()) water.removed(cell);
		} else if (update.appeared()) {
			// Seen empty before, or a harvest and replant seen happening: it's new, so it starts with no water
			water.appeared(cell, now.plant(), now.origin(), emptyFor != null || update.removed());
		} else if (update.before() != null && update.before().origin() != now.origin()) {
			water.settled(cell, now.plant(), now.origin());
		}
		if (runner != null) {
			StageTracker.Tracked before = update.before();
			StageTracker.Tracked after = update.after();
			if (update.appeared() && after != null && after.origin() == StageTracker.Origin.SPAWNED) runner.onSpawn(after.plant().id());
			if (update.appeared() && after != null && before != null && after.plant().id().equals("dead_plant")) runner.onDecay(before.plant().id());
			if (update.removed() && before != null && harvestable(before)) runner.onHarvest(before.plant().id());
		}
		return update;
	}

	/**
	 * Whether a plant that went was harvested (counts for "harvested" exits): a fully grown mutation, not placed. One
	 * whose origin wasn't seen counts only on a spawn spot, as {@link Census} calls the others placed.
	 */
	private boolean harvestable(StageTracker.Tracked plant) {
		if (!plant.plant().mutation() || plant.origin() == StageTracker.Origin.PLACED) return false;
		Schematic layout = layout();
		if (plant.origin() == StageTracker.Origin.UNKNOWN && layout != null && !Census.onTarget(plant.cell(), layout)) return false;
		int threshold = plant.plant().id().equals(Growth.ALOE) ? Growth.ALOE_FIRST_RESET_STAGE : plant.plant().readyStage();
		return plant.stages().hi() >= threshold;
	}

	/** Quarter turns the greenhouse is planted at, relative to the flow's layouts (see {@link Schematic#rotated}). */
	private int rotation;

	public int rotation() {
		return rotation;
	}

	public void setRotation(int rotation) {
		this.rotation = Math.floorMod(rotation, 4);
	}

	/** The current step's layout, turned the way this greenhouse is planted, or {@code null} when no flow is followed. */
	public @Nullable Schematic layout() {
		Schematic drawn = runner == null ? null : runner.step().schematic();
		return drawn == null ? null : drawn.rotated(rotation);
	}

	/**
	 * A Crop Diagnostics reading for the plant on {@code cell}, applied to every cell of it (a big plant's cells within
	 * its size of the one clicked). Returns what changed.
	 */
	public List<StageTracker.Update> diagnose(int cell, Diagnosis reading, long ms) {
		List<Integer> cells = List.of(cell);
		for (Census.Plant plant : context().census().plants()) {
			if (!plant.cells().contains(cell)) continue;
			int reach = plant.kind().size() - 1;
			cells = plant.cells().stream()
					.filter(other -> Math.abs(other / 10 - cell / 10) <= reach && Math.abs(other % 10 - cell % 10) <= reach).toList();
			break;
		}
		List<StageTracker.Update> updates = new ArrayList<>();
		for (int each : cells) {
			StageTracker.Update update = tracker.diagnose(each, reading, ms);
			if (update != null) updates.add(update);
		}
		return updates;
	}

	public ExitEvaluator.Context context() {
		Census census = Census.of(tracker.all(), layout());
		return new ExitEvaluator.Context(census, layout(), runner, inventory, plants);
	}

	/** Checks the exits; when one holds, moves to its step and returns the swap to tell the player about. */
	public List<Swap> update() {
		List<Swap> swaps = new ArrayList<>();
		if (runner == null) return swaps;
		for (int guard = 0; guard < runner.plot().steps().size() + 1; guard++) {
			FlowRunner.Decision decision = guard == 0 ? runner.check(evaluator, context()) : runner.checkOnEntry(evaluator, context());
			if (decision == null) break;
			Flows.Step from = runner.step();
			runner.apply(decision);
			swaps.add(new Swap(from, runner.plot().steps().get(decision.target()), decision.finishes(), decision.verdicts()));
			if (decision.finishes()) break;
			swapPending = true;
		}
		return swaps;
	}

	/** The player has put the new layout in place (or said so in the UI). */
	public void swapDone() {
		swapPending = false;
	}

	public boolean swapPending() {
		return swapPending;
	}

	public Status status(int lookahead) {
		ExitEvaluator.Context context = context();
		if (runner == null) return new Status(null, null, context.census(), List.of(), null, List.of(), false);
		List<List<ExitEvaluator.Verdict>> verdicts = new ArrayList<>();
		for (Flows.Exit exit : runner.step().exits()) verdicts.add(evaluator.exit(exit, context));
		FlowRunner.Decision decision = runner.check(evaluator, context);
		return new Status(runner, runner.step(), context.census(), verdicts, decision, estimator.plan(runner, context, lookahead), swapPending);
	}
}
