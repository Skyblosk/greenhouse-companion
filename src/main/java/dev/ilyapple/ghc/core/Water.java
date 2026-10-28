package dev.ilyapple.ghc.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.ilyapple.ghc.core.flow.Census;

/**
 * Each crop's water, kept the way the stage is (MagicAddons' {@code Plant.waterLevel}): the water it was last known to
 * have and the stage it was at then. A crop uses 20 water for every stage it grows (times its effects: Water Retain
 * ÷1.5, Improved Water Retain ÷2, Water Drain ×1.3, Immunity cancels the drain), so its water now follows from how many
 * stages it has grown since. Measured on the alpha (27 Oct 2026): a flat 20 a stage, a skipped stage uses none, and a
 * stage that would take it to −100 stops it instead.
 *
 * <p>Known water comes from a crop appearing while watched (it starts at 0), the bars a held Hydro Can shows, and Crop
 * Diagnostics ({@code Water: -30/100}). Crops that need none: Cactus, Cocoa Beans, Nether Wart, the Mushrooms, placed
 * items and mutations without "requires watering" (Cindershade, Zombud, Chorus Fruit…).
 */
public final class Water {
	public static final double FULL = 100;
	public static final double DRY = -100;
	/** Water a stage of growth uses before effects. */
	public static final double LOSS = 20;
	private static final Set<String> NEGATIVE = Set.of("xp_loss", "harvest_loss", "water_drain");

	/**
	 * Base crops that never need water in game (MagicAddons' crop definitions, and the user's greenhouse 27 Oct 2026),
	 * although the simulator waters every planted base crop: their water stays at +100.
	 */
	public static final Set<String> DRY_BASE_CROPS = Set.of("cactus", "cocoa_beans", "nether_wart", "red_mushroom", "brown_mushroom");

	/**
	 * Mutations that grow without water. The simulator's {@code requires_watering} already says so for each, and
	 * MagicAddons agrees; listed so a data change can't start watering them (the user named the first seven).
	 */
	public static final Set<String> DRY_MUTATIONS = Set.of("cindershade", "zombud", "chorus_fruit", "shellfruit", "phantomleaf", "all_in_aloe",
			"plantboy_advance", "thunderling", "turtlellini", "jerryflower");

	/** How many stages pass before you're back, for visits 1–4 times a day (the user's numbers, 28 Oct). */
	public static int stagesPerVisit(int visitsPerDay) {
		return switch (Math.max(1, Math.min(4, visitsPerDay))) {
			case 1 -> 14;
			case 2 -> 7;
			case 3 -> 5;
			default -> 4;
		};
	}

	/** What a crop's water asks for: nothing, water before you're back, or water now (at −100 it has stopped growing). */
	public enum Need { NONE, SOON, DRY }

	/** The water a crop had at {@code stage}. */
	public record Mark(double water, int stage) {}

	private final Map<Integer, Mark> cells = new TreeMap<>();

	public static boolean consumes(Plants.Plant kind, StageTracker.Origin origin) {
		String id = kind.id();
		if (id.equals("soggybud") || id.equals("fire") || id.equals("dead_plant") || id.equals("fermento")) return false;
		if (!kind.mutation()) return !DRY_BASE_CROPS.contains(id);
		return kind.requiresWatering() && !DRY_MUTATIONS.contains(id) && origin != StageTracker.Origin.PLACED;
	}

	/**
	 * A plant appeared on {@code cell}. {@code seenNew}: the cell was watched, so it is new and came up at stage 1 with no
	 * water (MagicAddons' {@code claimSpawnedMutation}); its water now follows from its stage.
	 */
	public void appeared(int cell, Plants.Plant kind, StageTracker.Origin origin, boolean seenNew) {
		if (!consumes(kind, origin) || !seenNew) cells.remove(cell);
		else cells.put(cell, new Mark(0, 1));
	}

	public void removed(int cell) {
		cells.remove(cell);
	}

	/** It turned out placed (or not): placed items need no water. */
	public void settled(int cell, Plants.Plant kind, StageTracker.Origin origin) {
		if (!consumes(kind, origin)) cells.remove(cell);
	}

	/** The crop on {@code plant}'s cells has {@code water} at {@code stage} (a bar or Crop Diagnostics). */
	public void read(Census.Plant plant, double water, int stage) {
		Mark mark = new Mark(Math.max(DRY, Math.min(FULL, water)), stage);
		for (int cell : plant.cells()) cells.put(cell, mark);
	}

	/** The crop's water now, at {@code stage}, or {@code null} when it isn't known. */
	public @Nullable Double water(Census.Plant plant, int stage, double factor) {
		Mark mark = markOf(plant);
		if (mark == null) return null;
		return Math.max(DRY, mark.water() - LOSS * factor * Math.max(0, stage - mark.stage()));
	}

	/**
	 * Whether the crop runs out before you're back {@code stagesAhead} stages from now: it would reach −100 before it is
	 * fully grown (MagicAddons' {@code waterLastsUntilGrown}). Unknown water counts as needing it.
	 */
	public static boolean needsWater(@Nullable Double water, int stage, int readyStage, double factor, int stagesAhead) {
		if (stage >= readyStage) return false;
		if (water == null) return true;
		if (water <= DRY) return true;
		int growing = Math.min(stagesAhead, readyStage - stage);
		return water - LOSS * factor * growing <= DRY;
	}

	/** {@link #needsWater}, and whether it's dried out already. */
	public static Need need(@Nullable Double water, int stage, int readyStage, double factor, int stagesAhead) {
		if (!needsWater(water, stage, readyStage, factor, stagesAhead)) return Need.NONE;
		return water != null && water <= DRY ? Need.DRY : Need.SOON;
	}

	private @Nullable Mark markOf(Census.Plant plant) {
		for (int cell : plant.cells()) {
			Mark mark = cells.get(cell);
			if (mark != null) return mark;
		}
		return null;
	}

	// Effects

	/** Each plant's water use multiplier from the effects it holds. */
	public Map<Census.Plant, Double> factors(List<Census.Plant> plants) {
		Map<Census.Plant, Double> factors = new HashMap<>();
		held(unique(plants)).forEach((plant, effects) -> factors.put(plant, factor(effective(effects))));
		return factors;
	}

	/** The water use multiplier for a crop's effective effects. */
	public static double factor(Set<String> effects) {
		double retain = effects.contains("improved_water_retain") ? 1 : effects.contains("water_retain") ? 0.5 : 0;
		return (1 + (effects.contains("water_drain") ? 0.3 : 0)) / (1 + retain);
	}

	/** Immunity strips negative effects; an improved effect hides its base one. */
	public static Set<String> effective(Set<String> held) {
		Set<String> effects = new HashSet<>(held);
		if (effects.contains("immunity")) effects.removeAll(NEGATIVE);
		for (String effect : held) if (effect.startsWith("improved_")) effects.remove(effect.substring("improved_".length()));
		return effects;
	}

	/**
	 * What each plant holds from its neighbours (the simulator's effect pass): every plant gives its own effects to the
	 * plants beside its footprint (Fire receives nothing); then each plant holding Effect Spread passes what it holds,
	 * Effect Spread aside, one step further, again whenever what it holds grows.
	 */
	static Map<Census.Plant, Set<String>> held(List<Census.Plant> plants) {
		Map<Integer, Census.Plant> at = new HashMap<>();
		for (Census.Plant plant : plants) for (int cell : plant.cells()) at.put(cell, plant);
		Map<Census.Plant, Set<String>> held = new LinkedHashMap<>();
		for (Census.Plant plant : plants) held.put(plant, new HashSet<>());
		for (Census.Plant plant : plants) {
			if (!plant.dead()) give(plant, Set.copyOf(plant.kind().effects()), at, held, null);
		}
		Deque<Census.Plant> queue = new ArrayDeque<>();
		Set<Census.Plant> spreaders = new HashSet<>();
		for (Census.Plant plant : plants) {
			if (held.get(plant).contains("effect_spread")) {
				spreaders.add(plant);
				queue.add(plant);
			}
		}
		while (!queue.isEmpty()) {
			Census.Plant plant = queue.poll();
			Set<String> passed = new HashSet<>(held.get(plant));
			passed.remove("effect_spread");
			give(plant, passed, at, held, changed -> {
				if (spreaders.contains(changed) && !queue.contains(changed)) queue.add(changed);
			});
		}
		return held;
	}

	private static void give(Census.Plant from, Set<String> effects, Map<Integer, Census.Plant> at, Map<Census.Plant, Set<String>> held,
			java.util.function.@Nullable Consumer<Census.Plant> onChange) {
		if (effects.isEmpty()) return;
		for (int cell : from.cells()) {
			int x = cell / 10;
			int z = cell % 10;
			for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int nx = x + step[0];
				int nz = z + step[1];
				if (nx < 0 || nx >= 10 || nz < 0 || nz >= 10) continue;
				Census.Plant to = at.get(nx * 10 + nz);
				if (to == null || to == from || to.id().equals("fire")) continue;
				if (held.get(to).addAll(effects) && onChange != null) onChange.accept(to);
			}
		}
	}

	/** Census lists several big plants side by side as copies of one group: each group once. */
	private static List<Census.Plant> unique(List<Census.Plant> plants) {
		Map<List<Integer>, Census.Plant> byCells = new LinkedHashMap<>();
		for (Census.Plant plant : plants) byCells.putIfAbsent(plant.cells(), plant);
		return List.copyOf(byCells.values());
	}

	// Saving

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		cells.forEach((cell, mark) -> {
			JsonArray entry = new JsonArray();
			entry.add(mark.water());
			entry.add(mark.stage());
			json.add(Integer.toString(cell), entry);
		});
		return json;
	}

	public void load(@Nullable JsonObject json) {
		cells.clear();
		if (json == null) return;
		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			JsonArray saved = entry.getValue().getAsJsonArray();
			// The old simulated ranges ([lo, hi, known]) aren't kept
			if (saved.size() != 2 || !saved.get(1).getAsJsonPrimitive().isNumber()) continue;
			cells.put(Integer.parseInt(entry.getKey()), new Mark(saved.get(0).getAsDouble(), saved.get(1).getAsInt()));
		}
	}
}
