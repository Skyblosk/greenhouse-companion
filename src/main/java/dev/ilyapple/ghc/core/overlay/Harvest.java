package dev.ilyapple.ghc.core.overlay;

import java.util.Set;

import org.jspecify.annotations.Nullable;

import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

/**
 * Which plants are ready to harvest, for the hitbox highlight: fully grown (even at the lowest stage the tracker allows),
 * not something you placed, and not a spawn the layout keeps as one of its inputs.
 * <ul>
 * <li>Mutations with no growth stages (the commons: Shadevine, Ashwreath…) are grown the moment they spawn.</li>
 * <li>Base crops count too: the simulator harvests and replants them when grown.</li>
 * <li>Dead Plants, Fire and Fermento are never harvested.</li>
 * </ul>
 */
public final class Harvest {
	private static final Set<String> NEVER = Set.of("dead_plant", "fire", "fermento");

	private Harvest() {}

	public static boolean ready(Census.Plant plant, @Nullable Schematic layout) {
		return ready(plant, layout, true);
	}

	/** {@code baseCrops} false: base crops are left to grow and never count as ready. */
	public static boolean ready(Census.Plant plant, @Nullable Schematic layout, boolean baseCrops) {
		if (!baseCrops && !plant.kind().mutation()) return false;
		if (NEVER.contains(plant.id()) || plant.origin() == StageTracker.Origin.PLACED || plant.stages().isEmpty()) return false;
		if (plant.stages().lo() < plant.kind().readyStage()) return false;
		if (plant.kind().mutation() && layout != null && keptAsInput(plant, layout)) return false;
		return true;
	}

	/** A spawned mutation standing where the layout places the same kind: the simulator keeps it as that input. */
	private static boolean keptAsInput(Census.Plant plant, Schematic layout) {
		for (int cell : plant.cells()) {
			Integer id = layout.placements().get(cell);
			if (id == null) continue;
			SkyblockerCrops.Crop crop = SkyblockerCrops.byId(id);
			if (crop != null && plant.id().equals(crop.simId())) return true;
		}
		return false;
	}
}
