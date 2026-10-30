package dev.ilyapple.ghc.core.flow;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

/** Copies the existing census; no world scans or spawn-requirement search. */
public final class LiveLayout {
	private LiveLayout() {}

	public static Schematic copy(Census census) {
		Map<Integer, Integer> placed = new LinkedHashMap<>();
		Map<Integer, Integer> targets = new LinkedHashMap<>();
		java.util.Set<Integer> covered = new java.util.HashSet<>();
		for (Census.Plant plant : census.plants()) {
			var crop = SkyblockerCrops.bySimId(plant.id());
			if (crop == null) continue;
			boolean grown = !plant.stages().isEmpty() && plant.stages().lo() >= plant.kind().readyStage();
			// A placed Jellybean's shape reads 119, one stage short of the spawned plant's ready look.
			grown |= plant.id().equals("magic_jellybean") && plant.origin() == StageTracker.Origin.PLACED
					&& plant.stages().lo() >= 119;
			boolean growing = plant.lookKnown() && !plant.stages().isEmpty()
					&& plant.stages().hi() < plant.kind().readyStage();
			if (plant.kind().mutation() && !grown && !growing) continue;
			int anchor = plant.cells().stream().filter(cell -> !covered.contains(cell)).mapToInt(Integer::intValue).min().orElse(-1);
			int size = crop.size();
			if (anchor < 0 || anchor / 10 + size > 10 || anchor % 10 + size > 10) continue;
			(plant.kind().mutation() && !grown ? targets : placed).put(anchor, crop.id());
			for (int dx = 0; dx < size; dx++) for (int dz = 0; dz < size; dz++) covered.add(anchor + dx * 10 + dz);
		}
		if (placed.isEmpty() && targets.isEmpty()) throw new IllegalArgumentException("No recognised plants to copy. Visit this greenhouse first.");
		return Schematic.fromAnchors(placed, targets);
	}
}
