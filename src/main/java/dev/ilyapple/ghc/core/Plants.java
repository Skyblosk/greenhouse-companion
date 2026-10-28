package dev.ilyapple.ghc.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Every Greenhouse crop and mutation, read from the SkyShards simulator's {@code data.json} (bundled from the
 * b8e7f0dc build, the same file the live site served on 17 October 2026). Plants are keyed by the simulator's id
 * ({@code magic_jellybean}); {@link #byName} also accepts the recorder's display names and Skyblocker's names.
 */
public final class Plants {
	public static final String RESOURCE = "/assets/greenhouse-companion/skyshards/data.json";

	/** A neighbour a mutation needs before it can spawn. */
	public record Requirement(String id, int count) {}

	/**
	 * @param stages      the simulator's growth stages; 0 for a plant with one look that never grows
	 * @param readyStage  the stage the simulator calls fully grown
	 * @param spawnWeight the simulator's spawn weight; 0 for plants that never spawn on their own
	 * @param effects     what it gives its neighbours: its positive and negative buffs ({@code water_retain}, ...)
	 */
	public record Plant(String id, String name, boolean mutation, int size, int stages, int readyStage, int spawnWeight,
			String ground, List<Requirement> requirements, int decay, boolean requiresWatering, String rarity, List<String> effects) {
		/** The stages a living plant can be at: 1..stages, or just 0 when it never grows. */
		public StageSet allStages() {
			return stages <= 0 ? StageSet.of(0) : StageSet.range(1, stages);
		}

		public int maxStage() {
			return Math.max(0, stages);
		}

		public boolean grows() {
			return stages > 0;
		}
	}

	private static @Nullable Plants defaults;

	private final Map<String, Plant> byId;
	private final Map<String, Plant> byNormalizedName = new LinkedHashMap<>();

	private Plants(Map<String, Plant> byId) {
		this.byId = byId;
		for (Plant plant : byId.values()) {
			byNormalizedName.put(normalize(plant.name()), plant);
			byNormalizedName.put(normalize(plant.id()), plant);
		}
		// Names other mods and the recorder use for the same plants
		alias("Do Not Eat Shroom", "do_not_eat_shroom");
		alias("All In Aloe", "all_in_aloe");
		alias("Wheat Seeds", "wheat");
		alias("Melon Seeds", "melon");
		alias("Pumpkin Seeds", "pumpkin");
		alias("Dead Plants", "dead_plant");
		alias("Jerryseed", "jerryflower");
	}

	private void alias(String name, String id) {
		Plant plant = byId.get(id);
		if (plant != null) byNormalizedName.putIfAbsent(normalize(name), plant);
	}

	public static synchronized Plants defaults() {
		if (defaults == null) {
			try (InputStream in = Plants.class.getResourceAsStream(RESOURCE)) {
				if (in == null) throw new IllegalStateException("missing " + RESOURCE);
				defaults = parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
			} catch (IOException e) {
				throw new IllegalStateException("could not read " + RESOURCE, e);
			}
		}
		return defaults;
	}

	public static Plants parse(JsonObject data) {
		Map<String, Plant> plants = new LinkedHashMap<>();
		for (String section : new String[] {"crops", "mutations"}) {
			if (!data.has(section)) continue;
			boolean mutation = section.equals("mutations");
			for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject(section).entrySet()) {
				JsonObject json = entry.getValue().getAsJsonObject();
				String id = entry.getKey();
				int stages = json.has("growth_stages") && !json.get("growth_stages").isJsonNull() ? json.get("growth_stages").getAsInt() : 0;
				List<Requirement> requirements = new ArrayList<>();
				if (json.has("requirements")) {
					for (JsonElement element : json.getAsJsonArray("requirements")) {
						JsonObject requirement = element.getAsJsonObject();
						requirements.add(new Requirement(requirement.get("crop").getAsString(), requirement.get("count").getAsInt()));
					}
				}
				plants.put(id, new Plant(id, json.get("name").getAsString(), mutation,
						json.has("size") ? json.get("size").getAsInt() : 1,
						stages, readyStage(id, stages),
						json.has("spawn_weight") ? json.get("spawn_weight").getAsInt() : 0,
						json.has("ground") ? json.get("ground").getAsString() : "farmland",
						List.copyOf(requirements),
						json.has("decay") && !json.get("decay").isJsonNull() ? json.get("decay").getAsInt() : 0,
						json.has("requires_watering") && json.get("requires_watering").getAsBoolean(),
						json.has("rarity") ? json.get("rarity").getAsString() : (mutation ? "common" : "crop"),
						effects(json)));
			}
		}
		return new Plants(plants);
	}

	/** Positive then negative buffs; Fire gives nothing (the simulator's one inert plant). */
	private static List<String> effects(JsonObject json) {
		List<String> effects = new ArrayList<>();
		for (String member : new String[] {"positive_buffs", "negative_buffs"}) {
			if (!json.has(member) || !json.get(member).isJsonArray()) continue;
			for (JsonElement element : json.getAsJsonArray(member)) effects.add(element.getAsString());
		}
		return List.copyOf(effects);
	}

	/** All-in Aloe's harvest stage when nothing else is set: the stage with the most drops on average. */
	public static final int ALOE_HARVEST_STAGE = 14;

	/**
	 * The simulator's {@code readyStage}: the last stage, except Glasscorn (7, where the menu first says FULLY GROWN)
	 * and All-in Aloe (its harvest stage setting).
	 */
	private static int readyStage(String id, int stages) {
		return switch (id) {
			case "glasscorn" -> Math.min(7, stages);
			case "all_in_aloe" -> Math.max(1, Math.min(stages, ALOE_HARVEST_STAGE));
			default -> Math.max(0, stages);
		};
	}

	public @Nullable Plant byId(String id) {
		return byId.get(id);
	}

	/** Looks a plant up by any of its names: simulator id, display name, the recorder's or Skyblocker's name. */
	public @Nullable Plant byName(@Nullable String name) {
		if (name == null) return null;
		return byNormalizedName.get(normalize(name));
	}

	public Collection<Plant> all() {
		return byId.values();
	}

	private static String normalize(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
	}
}
