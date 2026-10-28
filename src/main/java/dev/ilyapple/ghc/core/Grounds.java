package dev.ilyapple.ghc.core;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

/**
 * Which soils a plant grows on. The simulator's data gives one ground per plant; some take more. Shadevine was seen
 * on farmland and sand about equally often in the stage recorder's 77,000 cell readings (every other mutation only
 * ever on its one ground), Lonelily also grows on dirt (the wiki's Mutations table, as GreenEssentials lists it), Fire
 * burns on netherrack or soul sand, and a Dead Plant is left on whatever its plant stood on.
 */
public final class Grounds {
	private static final Map<String, Set<String>> EXTRA = Map.of(
			"shadevine", Set.of("farmland", "sand"),
			"lonelily", Set.of("farmland", "dirt"),
			"fire", Set.of("netherrack", "soul_sand"));

	private Grounds() {}

	/** The soils a plant accepts; empty means any (Dead Plant). */
	public static Set<String> accepted(Plants.Plant plant) {
		if (plant.id().equals("dead_plant")) return Set.of();
		Set<String> soils = new LinkedHashSet<>();
		soils.add(plant.ground());
		soils.addAll(EXTRA.getOrDefault(plant.id(), Set.of()));
		return soils;
	}

	/**
	 * The soils a layout cell needs: the tile the layout sets there, else what the plant placed or wanted there grows
	 * on (for a 2x2 or 3x3 plant, every cell of it). {@code null} when the layout says nothing about the cell.
	 */
	public static @Nullable Set<String> required(Schematic layout, int cell, Plants plants) {
		String tile = layout.ground().get(cell);
		if (tile != null) return Set.of(tile);
		Plants.Plant plant = plantCovering(layout, cell, plants);
		return plant == null ? null : accepted(plant);
	}

	/** The plant a layout places or wants at {@code cell}, counting every cell of a big plant. */
	public static Plants.@Nullable Plant plantCovering(Schematic layout, int cell, Plants plants) {
		int x = cell / Schematic.SIZE;
		int z = cell % Schematic.SIZE;
		for (Map<Integer, Integer> anchors : java.util.List.of(layout.placements(), layout.targets())) {
			for (Map.Entry<Integer, Integer> entry : anchors.entrySet()) {
				int size = SkyblockerCrops.sizeOf(entry.getValue());
				int ax = entry.getKey() / Schematic.SIZE;
				int az = entry.getKey() % Schematic.SIZE;
				if (x < ax || x >= ax + size || z < az || z >= az + size) continue;
				SkyblockerCrops.Crop crop = SkyblockerCrops.byId(entry.getValue());
				return crop == null || crop.simId() == null ? null : plants.byId(crop.simId());
			}
		}
		return null;
	}

	/** {@code minecraft:farmland[moisture=7]} → {@code farmland}. */
	public static String soilId(String blockState) {
		String id = blockState.contains("[") ? blockState.substring(0, blockState.indexOf('[')) : blockState;
		return id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
	}

	public static boolean accepts(@Nullable Set<String> required, @Nullable String soil) {
		return required == null || required.isEmpty() || soil != null && required.contains(soilId(soil));
	}
}
