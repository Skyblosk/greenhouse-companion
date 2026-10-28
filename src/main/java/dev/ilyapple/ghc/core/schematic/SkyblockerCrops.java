package dev.ilyapple.ghc.core.schematic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Skyblocker's greenhouse crop ids ({@code GreenhouseCrops}, Skyblocker 6.10.4 / master, LGPL-3.0), the numbers in
 * its schematic grid. 1–40 are the mutations in SkyShards' order; the simulator id is added here.
 */
public final class SkyblockerCrops {
	/**
	 * @param name           Skyblocker's name, also the SkyMutations name
	 * @param armorStandName the head-name token Skyblocker matches
	 * @param simId          the SkyShards simulator id, or {@code null} for crops the simulator doesn't have
	 * @param size           the footprint's side: 1, 2 (2x2) or 3 (3x3)
	 */
	public record Crop(String name, String armorStandName, int id, @Nullable String simId, int size) {}

	public static final List<Crop> ALL = List.of(
			new Crop("Ashwreath", "ashwreath", 1, "ashwreath", 1),
			new Crop("Choconut", "Choconut", 2, "choconut", 1),
			new Crop("Dustgrain", "Dustgrain", 3, "dustgrain", 1),
			new Crop("Gloomgourd", "Gloomgourd", 4, "gloomgourd", 1),
			new Crop("Lonelily", "Lonelilly", 5, "lonelily", 1),
			new Crop("Scourroot", "Scourroot", 6, "scourroot", 1),
			new Crop("Shadevine", "shadevine", 7, "shadevine", 1),
			new Crop("Veilshroom", "Veilshroom", 8, "veilshroom", 1),
			new Crop("Witherbloom", "Witherbloom", 9, "witherbloom", 1),
			new Crop("Chocoberry", "Chocoberry", 10, "chocoberry", 1),
			new Crop("Cindershade", "Cindershade", 11, "cindershade", 1),
			new Crop("Coalroot", "Coalroot", 12, "coalroot", 1),
			new Crop("Creambloom", "Creambloom", 13, "creambloom", 1),
			new Crop("Duskbloom", "duskbloom", 14, "duskbloom", 1),
			new Crop("Thornshade", "thornshade", 15, "thornshade", 1),
			new Crop("Blastberry", "blastberry", 16, "blastberry", 1),
			new Crop("Cheesebite", "cheesebite", 17, "cheesebite", 1),
			new Crop("Chloronite", "chloronite", 18, "chloronite", 1),
			new Crop("Do Not Eat Shroom", "donoteatshroom", 19, "do_not_eat_shroom", 1),
			new Crop("Fleshtrap", "fleshtrap", 20, "fleshtrap", 1),
			new Crop("Magic Jellybean", "magicjellybean", 21, "magic_jellybean", 1),
			new Crop("Noctilume", "noctilume", 22, "noctilume", 2),
			new Crop("Snoozling", "snoozlingFlower", 23, "snoozling", 3),
			new Crop("Soggybud", "soggybud", 24, "soggybud", 1),
			new Crop("Chorus Fruit", "chorusFruit", 25, "chorus_fruit", 1),
			new Crop("PlantBoy Advance", "Plantboy", 26, "plantboy_advance", 2),
			new Crop("Puffercloud", "puffercloud", 27, "puffercloud", 1),
			new Crop("Shellfruit", "shellfruit", 28, "shellfruit", 1),
			new Crop("Startlevine", "startlevine", 29, "startlevine", 1),
			new Crop("Stoplight Petal", "stoplightpetal", 30, "stoplight_petal", 1),
			new Crop("Thunderling", "thunderling", 31, "thunderling", 1),
			new Crop("Turtlellini", "turtlellini", 32, "turtlellini", 1),
			new Crop("Zombud", "zombud", 33, "zombud", 1),
			new Crop("All In Aloe", "allinaloe", 34, "all_in_aloe", 1),
			new Crop("Devourer", "devourer", 35, "devourer", 1),
			new Crop("Glasscorn", "glasscorn", 36, "glasscorn", 2),
			new Crop("Godseed", "godseed", 37, "godseed", 3),
			new Crop("Jerryflower", "jerryseed", 38, "jerryflower", 1),
			new Crop("Phantomleaf", "phantomleaf", 39, "phantomleaf", 1),
			new Crop("Timestalk", "timestalk", 40, "timestalk", 1),
			new Crop("Cocoa Beans", "coco", 41, "cocoa_beans", 1),
			new Crop("Wild Rose", "wildrose", 42, "wild_rose", 1),
			new Crop("Moonflower", "moonflower", 43, "moonflower", 1),
			new Crop("Sunflower", "sunflower", 44, "sunflower", 1),
			new Crop("Cactus", "cactus", 45, "cactus", 1),
			new Crop("Melon Seeds", "melon", 46, "melon", 1),
			new Crop("Pumpkin Seeds", "pumpkin", 47, "pumpkin", 1),
			new Crop("Brown Mushroom", "brownmushroom", 48, "brown_mushroom", 1),
			new Crop("Red Mushroom", "redmushroom", 49, "red_mushroom", 1),
			new Crop("Wheat Seeds", "wheat", 50, "wheat", 1),
			new Crop("Carrot", "carrot", 51, "carrot", 1),
			new Crop("Potato", "potato", 52, "potato", 1),
			new Crop("Nether Wart", "netherwart", 53, "nether_wart", 1),
			new Crop("Sugar Cane", "sugarcane", 54, "sugar_cane", 1),
			new Crop("Dead Plants", "deadplant", 55, "dead_plant", 1),
			new Crop("Fire", "fire", 56, "fire", 1),
			new Crop("Helianthus", "helianthus", 57, null, 1),
			new Crop("Fermento", "fermento", 58, "fermento", 1),
			new Crop("Squash", "squash", 59, null, 1),
			new Crop("Cropie", "cropie", 60, null, 1));

	private static final Map<Integer, Crop> BY_ID = new HashMap<>();
	private static final Map<String, Crop> BY_NAME = new HashMap<>();
	private static final Map<String, Crop> BY_SIM_ID = new HashMap<>();

	static {
		for (Crop crop : ALL) {
			BY_ID.put(crop.id(), crop);
			BY_NAME.put(crop.name(), crop);
			if (crop.simId() != null) BY_SIM_ID.put(crop.simId(), crop);
		}
	}

	private SkyblockerCrops() {}

	public static @Nullable Crop byId(int id) {
		return BY_ID.get(id);
	}

	/** By Skyblocker's exact name, as SkyMutations codes spell it. */
	public static @Nullable Crop byName(String name) {
		return BY_NAME.get(name);
	}

	public static @Nullable Crop bySimId(String simId) {
		return BY_SIM_ID.get(simId);
	}

	/** Skyblocker's {@code SkyShardsLayout.sizeOf}. */
	public static int sizeOf(int cropId) {
		Crop crop = BY_ID.get(cropId);
		return crop == null ? 1 : crop.size();
	}
}
