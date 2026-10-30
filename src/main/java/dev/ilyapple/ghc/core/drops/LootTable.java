package dev.ilyapple.ghc.core.drops;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What each plant drops, from the simulator's {@code data.json} (Shadevine: 78 Cactus and 104 Sugar Cane at base;
 * Cactus: 132 Cactus), plus the plant's own item for a mutation. Used to split a sack report or a drop between the
 * plants harvested at the time, by how much of the item each one drops, and to rule out plants that can't drop it.
 */
public final class LootTable {
	/**
	 * Armor rare crops and their eligible base crops. Mutations roll armor rare crops independently of their ordinary
	 * crop drops and rarity: the user's 29 Oct logs include Dustgrain Helianthus and Lonelily Fermento.
	 */
	public static final Map<String, Set<String>> RARE_CROP_OF = Map.of(
			"CROPIE", Set.of("WHEAT", "CARROT_ITEM", "POTATO_ITEM"),
			"SQUASH", Set.of("MELON", "PUMPKIN", "INK_SACK:3"),
			"FERMENTO", Set.of("SUGAR_CANE", "CACTUS", "NETHER_STALK", "RED_MUSHROOM", "BROWN_MUSHROOM"),
			"HELIANTHUS", Set.of("DOUBLE_PLANT", "MOONFLOWER", "WILD_ROSE"));
	/** Ethereal Vine only comes from spawned mutations (the simulator: 15% a common up to 40% a legendary). */
	public static final String ETHEREAL_VINE = "ETHEREAL_VINE";

	private final Map<String, Map<String, Double>> drops = new HashMap<>();
	private final Set<String> mutations;

	public LootTable(Map<String, Map<String, Double>> drops) {
		this(drops, Set.of());
	}

	public LootTable(Map<String, Map<String, Double>> drops, Set<String> mutations) {
		drops.forEach((plant, items) -> this.drops.put(plant, Map.copyOf(items)));
		this.mutations = Set.copyOf(mutations);
	}

	/**
	 * Mutations can roll every armor rare crop, including common mutations. Base crops retain their crop-specific
	 * armor drops. Ethereal Vine only comes from mutations. A plant missing from the table can drop anything.
	 */
	public boolean canDrop(String plant, String item) {
		Map<String, Double> items = drops.get(plant);
		if (items == null) return true;
		Set<String> crops = RARE_CROP_OF.get(item);
		if (crops != null) return mutations.contains(plant) || items.keySet().stream().anyMatch(crops::contains);
		if (item.equals(ETHEREAL_VINE)) return mutations.contains(plant);
		return true;
	}

	/** Reads {@code crops} and {@code mutations} from the simulator's data. */
	public static LootTable of(JsonObject data) {
		Map<String, Map<String, Double>> drops = new HashMap<>();
		Set<String> mutations = new HashSet<>();
		for (String section : new String[] {"crops", "mutations"}) {
			if (!data.has(section)) continue;
			for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject(section).entrySet()) {
				Map<String, Double> items = new HashMap<>();
				JsonObject plant = entry.getValue().getAsJsonObject();
				if (plant.has("drops") && plant.get("drops").isJsonObject()) {
					plant.getAsJsonObject("drops").entrySet().forEach(drop -> items.merge(Items.itemOf(drop.getKey()), drop.getValue().getAsDouble(), Double::sum));
				}
				if (section.equals("mutations")) {
					items.merge(Items.itemOf(entry.getKey()), 1.0, Double::sum);
					mutations.add(entry.getKey());
				}
				drops.put(entry.getKey(), items);
			}
		}
		return new LootTable(drops, mutations);
	}

	/** How much of {@code item} (base units) one harvest of {@code plant} drops at base; 0 when it drops none. */
	public double weight(String plant, String item) {
		Map<String, Double> items = drops.get(plant);
		return items == null ? 0 : items.getOrDefault(Items.unit(item).base(), 0.0);
	}
}
