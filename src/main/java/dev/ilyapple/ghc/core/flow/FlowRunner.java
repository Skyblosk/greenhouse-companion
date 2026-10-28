package dev.ilyapple.ghc.core.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Where one plot is in its flow, kept the way the simulator's runner keeps it: the step, cycles spent in it, and
 * what spawned, was harvested or decayed since it began. {@link #check} picks the exit to take with the same rules
 * as the simulator: exits in order, an exit's {@code to} or else the next step, and a last step of a flow that
 * doesn't loop finishes the flow instead of moving.
 */
public final class FlowRunner {
	/** Exit {@code exitIndex} of the current step holds and leads to step {@code target} (or finishes the flow). */
	public record Decision(int exitIndex, int target, boolean finishes, List<ExitEvaluator.Verdict> verdicts) {}

	private final Flows.Plot plot;
	private int stepIndex;
	private int cyclesInStep;
	private final Map<String, Integer> spawned = new TreeMap<>();
	private final Map<String, Integer> harvested = new TreeMap<>();
	private final Map<String, Integer> decayed = new TreeMap<>();
	private final List<String> history = new ArrayList<>();
	private boolean finished;

	public FlowRunner(Flows.Plot plot) {
		this.plot = plot;
		enter(plot.startIndex());
	}

	public Flows.Plot plot() {
		return plot;
	}

	public int stepIndex() {
		return stepIndex;
	}

	public Flows.Step step() {
		return plot.steps().get(stepIndex);
	}

	public int cyclesInStep() {
		return cyclesInStep;
	}

	public boolean finished() {
		return finished;
	}

	public int spawned(String id) {
		return spawned.getOrDefault(id, 0);
	}

	public int harvested(String id) {
		return harvested.getOrDefault(id, 0);
	}

	public int decayed(String id) {
		return decayed.getOrDefault(id, 0);
	}

	public List<String> history() {
		return List.copyOf(history);
	}

	public void onTicks(int ticks) {
		cyclesInStep += ticks;
	}

	public void onSpawn(String id) {
		spawned.merge(id, 1, Integer::sum);
	}

	public void onHarvest(String id) {
		harvested.merge(id, 1, Integer::sum);
	}

	public void onDecay(String id) {
		decayed.merge(id, 1, Integer::sum);
	}

	/** Starts a step, clearing the per-step counters. */
	public void enter(int index) {
		stepIndex = Math.max(0, Math.min(index, plot.steps().size() - 1));
		cyclesInStep = 0;
		spawned.clear();
		harvested.clear();
		decayed.clear();
		finished = false;
		if (!plot.steps().isEmpty()) history.add(step().id());
	}

	/** Times the current step was entered since {@code sinceStep} was last entered (the simulator's {@code stepVisits}). */
	public int visits(@Nullable String sinceStep) {
		String current = history.isEmpty() ? null : history.getLast();
		int count = 0;
		for (int i = history.size() - 1; i >= 0; i--) {
			if (history.get(i).equals(current)) count++;
			if (sinceStep != null && history.get(i).equals(sinceStep)) break;
		}
		return count;
	}

	/** The first exit of the current step that holds, or {@code null} when the plot should stay. */
	public @Nullable Decision check(ExitEvaluator evaluator, ExitEvaluator.Context context) {
		return check(evaluator, context, false);
	}

	/** Like {@link #check}, but only the exits marked to be checked the moment a step starts. */
	public @Nullable Decision checkOnEntry(ExitEvaluator evaluator, ExitEvaluator.Context context) {
		return check(evaluator, context, true);
	}

	private @Nullable Decision check(ExitEvaluator evaluator, ExitEvaluator.Context context, boolean entryOnly) {
		if (plot.steps().isEmpty()) return null;
		List<Flows.Exit> exits = step().exits();
		boolean last = stepIndex == plot.steps().size() - 1;
		for (int i = 0; i < exits.size(); i++) {
			Flows.Exit exit = exits.get(i);
			if (entryOnly && !exit.checkOnEntry()) continue;
			int target;
			boolean finishes = false;
			if (exit.to() != null) {
				target = plot.indexOf(exit.to());
				if (target < 0) continue;
			} else if (last && !plot.loop()) {
				target = stepIndex;
				finishes = true;
			} else {
				target = (stepIndex + 1) % plot.steps().size();
			}
			List<ExitEvaluator.Verdict> verdicts = evaluator.exit(exit, context);
			boolean holds = !verdicts.isEmpty() && (exit.any() ? verdicts.stream().anyMatch(ExitEvaluator.Verdict::value)
					: verdicts.stream().allMatch(ExitEvaluator.Verdict::value));
			if (!holds) continue;
			if (finishes && finished) continue;
			return new Decision(i, target, finishes, verdicts);
		}
		return null;
	}

	/** Takes a decision: moves to its step, or marks the flow finished. */
	public void apply(Decision decision) {
		if (decision.finishes()) finished = true;
		else enter(decision.target());
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("stepIndex", stepIndex);
		json.addProperty("cyclesInStep", cyclesInStep);
		json.addProperty("finished", finished);
		json.add("spawned", counts(spawned));
		json.add("harvested", counts(harvested));
		json.add("decayed", counts(decayed));
		JsonArray list = new JsonArray();
		history.forEach(list::add);
		json.add("history", list);
		return json;
	}

	/** Restores what {@link #toJson} saved, if it still fits the plot. */
	public void load(JsonObject json) {
		int index = json.has("stepIndex") ? json.get("stepIndex").getAsInt() : stepIndex;
		if (index < 0 || index >= plot.steps().size()) return;
		stepIndex = index;
		cyclesInStep = json.has("cyclesInStep") ? json.get("cyclesInStep").getAsInt() : 0;
		finished = json.has("finished") && json.get("finished").getAsBoolean();
		read(json, "spawned", spawned);
		read(json, "harvested", harvested);
		read(json, "decayed", decayed);
		history.clear();
		if (json.has("history")) json.getAsJsonArray("history").forEach(element -> history.add(element.getAsString()));
		if (history.isEmpty()) history.add(step().id());
	}

	private static JsonObject counts(Map<String, Integer> map) {
		JsonObject json = new JsonObject();
		map.forEach(json::addProperty);
		return json;
	}

	private static void read(JsonObject json, String member, Map<String, Integer> into) {
		into.clear();
		if (json.has(member)) json.getAsJsonObject(member).entrySet().forEach(entry -> into.put(entry.getKey(), entry.getValue().getAsInt()));
	}
}
