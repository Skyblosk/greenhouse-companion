package dev.ilyapple.ghc.core.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

/**
 * Joining a flow step part way through. A greenhouse identified at a step has usually been on it for a while, but
 * the runner's per-step counters start at zero, so "16 x Magic Jellybean spawned" would wait for 16 more. This
 * counts what the greenhouse already shows towards the step's exits:
 * <ul>
 * <li>{@code mutationSpawned}: plants of that kind standing on the layout's target spots for it (not placed ones);</li>
 * <li>{@code mutationHarvested}: the layout's placements of that kind whose cell no longer holds one, taken to have
 *     been harvested (a step that keeps the last step's spawns as layout plants, like Jellybean + Shadevine).</li>
 * </ul>
 * Time already spent in the step ({@code cycles}) can't be seen, so it stays at zero.
 */
public final class MidStep {
	public record Seeded(String kind, String id, int count) {}

	private MidStep() {}

	/** Adds what the census shows to the runner's counters for its current step; returns what it added. */
	public static List<Seeded> seed(FlowRunner runner, Census census, int rotation) {
		Schematic layout = runner.step().schematic() == null ? null : runner.step().schematic().rotated(rotation);
		if (layout == null) return List.of();
		Map<String, Integer> spawnedIds = new LinkedHashMap<>();
		Map<String, Integer> harvestedIds = new LinkedHashMap<>();
		for (Flows.Exit exit : runner.step().exits()) collect(exit.when(), spawnedIds, harvestedIds);

		List<Seeded> seeded = new ArrayList<>();
		for (String id : spawnedIds.keySet()) {
			int count = 0;
			for (Census.Plant plant : census.plants()) {
				if (!plant.id().equals(id) || plant.origin() == StageTracker.Origin.PLACED) continue;
				int anchor = plant.cells().stream().mapToInt(Integer::intValue).min().orElse(-1);
				Integer target = layout.targets().get(anchor);
				SkyblockerCrops.Crop crop = target == null ? null : SkyblockerCrops.byId(target);
				if (crop != null && id.equals(crop.simId())) count++;
			}
			for (int i = 0; i < count; i++) runner.onSpawn(id);
			if (count > 0) seeded.add(new Seeded("spawned", id, count));
		}
		for (String id : harvestedIds.keySet()) {
			int count = 0;
			for (Map.Entry<Integer, Integer> placement : layout.placements().entrySet()) {
				SkyblockerCrops.Crop crop = SkyblockerCrops.byId(placement.getValue());
				if (crop == null || !id.equals(crop.simId())) continue;
				StageTracker.Tracked there = census.cell(placement.getKey());
				if (there == null || !there.plant().id().equals(id)) count++;
			}
			for (int i = 0; i < count; i++) runner.onHarvest(id);
			if (count > 0) seeded.add(new Seeded("harvested", id, count));
		}
		return seeded;
	}

	private static void collect(List<Flows.Condition> conditions, Map<String, Integer> spawned, Map<String, Integer> harvested) {
		for (Flows.Condition condition : conditions) {
			switch (condition.kind()) {
				case "group" -> collect(condition.of(), spawned, harvested);
				case "mutationSpawned" -> spawned.put(condition.mutationId(), condition.integer("count", 1));
				case "mutationHarvested" -> harvested.put(condition.mutationId(), condition.integer("count", 1));
				default -> {
				}
			}
		}
	}
}
