package dev.ilyapple.ghc.core.drops;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The items the drop recorder deals in, by SkyBlock item id. Crops are counted in base units (GreenEssentials'
 * {@code Catalog}): an auto-compactor turning 160 Wheat into an Enchanted Wheat isn't a drop, so both count as Wheat.
 * Also reads the chat: "RARE CROP!" lines (SkyHanni's {@code RareCropTracker} wording) and the lines of a [Sacks]
 * report's hover.
 */
public final class Items {
	/** An item and how many base units one of it is. */
	public record Unit(String base, long amount) {}

	private static final Map<String, Unit> UNITS = new HashMap<>();
	private static final Map<String, String> NAMES = new LinkedHashMap<>();
	private static final Map<String, String> BY_NAME = new HashMap<>();

	/**
	 * Drops that come with a "RARE CROP!" line. They're counted from the chat only, never from the inventory or sacks,
	 * so none is counted twice.
	 */
	public static final Set<String> RARE = Set.of("CROPIE", "SQUASH", "FERMENTO", "HELIANTHUS", "SEASONING", "BURROWING_SPORES",
			"RAREFINDER_CHIP", "WARTY", "CORNUCOPIA", "CARROT_ZEST", "DEEPFRIES", "AGGOURDIAN", "CANE_KNOT", "MELON_JUICE", "CACTUS_FLOWER",
			"DESIGNER_COFFEE_BEANS", "FEASTFUNGUS", "BOTROOT", "SALTED_SUNFLOWER_SEEDS", "CRYSTALIZED_MOONLIGHT", "FLORAL_GELATIN",
			// "RARE CROP! Ethereal Vine" also reaches the sacks: counted twice before 28 Oct
			"ETHEREAL_VINE");

	private static final Map<String, String> CROP_ITEMS = Map.ofEntries(
			Map.entry("wheat", "WHEAT"), Map.entry("seeds", "SEEDS"), Map.entry("potato", "POTATO_ITEM"), Map.entry("carrot", "CARROT_ITEM"),
			Map.entry("pumpkin", "PUMPKIN"), Map.entry("melon", "MELON"), Map.entry("cocoa_beans", "INK_SACK:3"), Map.entry("sugar_cane", "SUGAR_CANE"),
			Map.entry("cactus", "CACTUS"), Map.entry("nether_wart", "NETHER_STALK"), Map.entry("red_mushroom", "RED_MUSHROOM"),
			Map.entry("brown_mushroom", "BROWN_MUSHROOM"), Map.entry("moonflower", "MOONFLOWER"), Map.entry("sunflower", "DOUBLE_PLANT"),
			Map.entry("wild_rose", "WILD_ROSE"));

	/** The item a simulator crop id drops as: {@code nether_wart} → {@code NETHER_STALK}; a mutation's is its id in capitals. */
	public static String itemOf(String simId) {
		String item = CROP_ITEMS.get(simId);
		return item != null ? item : simId.toUpperCase(Locale.ROOT);
	}

	/** The two drops the recorder was built to check. */
	public static final String FERMENTO = "FERMENTO";
	public static final String HELIANTHUS = "HELIANTHUS";

	static {
		family("WHEAT", "Wheat", "HAY_BLOCK", 9, "ENCHANTED_WHEAT", 160, "ENCHANTED_HAY_BALE", 25_600);
		family("SEEDS", "Seeds", "ENCHANTED_SEEDS", 160, "BOX_OF_SEEDS", 25_600);
		family("CARROT_ITEM", "Carrot", "ENCHANTED_CARROT", 160, "ENCHANTED_GOLDEN_CARROT", 25_600);
		family("POTATO_ITEM", "Potato", "ENCHANTED_POTATO", 160, "ENCHANTED_BAKED_POTATO", 25_600);
		family("NETHER_STALK", "Nether Wart", "ENCHANTED_NETHER_STALK", 160, "MUTANT_NETHER_STALK", 25_600);
		family("PUMPKIN", "Pumpkin", "ENCHANTED_PUMPKIN", 160, "POLISHED_PUMPKIN", 25_600);
		family("MELON", "Melon Slice", "MELON_BLOCK", 9, "ENCHANTED_MELON", 160, "ENCHANTED_MELON_BLOCK", 25_600);
		family("INK_SACK:3", "Cocoa Beans", "ENCHANTED_COCOA", 160, "ENCHANTED_COOKIE", 25_600);
		family("SUGAR_CANE", "Sugar Cane", "ENCHANTED_SUGAR", 160, "ENCHANTED_SUGAR_CANE", 25_600);
		family("CACTUS", "Cactus", "ENCHANTED_CACTUS_GREEN", 160, "ENCHANTED_CACTUS", 25_600);
		family("RED_MUSHROOM", "Red Mushroom", "HUGE_MUSHROOM_2", 9, "ENCHANTED_RED_MUSHROOM", 160, "ENCHANTED_HUGE_MUSHROOM_2", 25_600);
		family("BROWN_MUSHROOM", "Brown Mushroom", "HUGE_MUSHROOM_1", 9, "ENCHANTED_BROWN_MUSHROOM", 160, "ENCHANTED_HUGE_MUSHROOM_1", 25_600);
		family("DOUBLE_PLANT", "Sunflower", "ENCHANTED_SUNFLOWER", 160, "COMPACTED_SUNFLOWER", 25_600);
		family("MOONFLOWER", "Moonflower", "ENCHANTED_MOONFLOWER", 160, "COMPACTED_MOONFLOWER", 25_600);
		family("WILD_ROSE", "Wild Rose", "ENCHANTED_WILD_ROSE", 160, "COMPACTED_WILD_ROSE", 25_600);
		alias("Melon", "MELON");
		alias("Cocoa Bean", "INK_SACK:3");
		alias("Enchanted Cocoa Beans", "ENCHANTED_COCOA");
		alias("Enchanted Nether Wart", "ENCHANTED_NETHER_STALK");
		alias("All-in Aloe Fragment", "ALL_IN_ALOE_FRAGMENT");
	}

	private Items() {}

	private static void family(String base, String name, Object... forms) {
		UNITS.put(base, new Unit(base, 1));
		NAMES.put(base, name);
		alias(name, base);
		for (int i = 0; i < forms.length; i += 2) UNITS.put((String) forms[i], new Unit(base, ((Number) forms[i + 1]).longValue()));
	}

	private static void alias(String name, String id) {
		BY_NAME.put(name.toLowerCase(Locale.ROOT), id);
	}

	/** What {@code count} of {@code id} is in base units: Enchanted Wheat → Wheat × 160. Other items stay as they are. */
	public static Unit unit(String id) {
		Unit unit = UNITS.get(id);
		return unit != null ? unit : new Unit(id, 1);
	}

	/** An item's name: the crop's name, else its id in title case (FERMENTO → Fermento, MAGIC_JELLYBEAN → Magic Jellybean). */
	public static String name(String id) {
		String known = NAMES.get(id);
		if (known != null) return known;
		StringBuilder name = new StringBuilder();
		for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
			if (word.isEmpty()) continue;
			if (!name.isEmpty()) name.append(' ');
			name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return name.toString();
	}

	/** The id for an item's name as chat writes it: "Nether Wart" → NETHER_STALK, "Fermento" → FERMENTO. */
	public static String idForName(String name) {
		String clean = name.strip();
		String known = BY_NAME.get(clean.toLowerCase(Locale.ROOT));
		if (known != null) return known;
		return clean.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_').replaceAll("[^A-Z0-9_:]", "");
	}

	private static final Pattern RARE_CROP = Pattern.compile("^(?:VERY )?RARE CROP! (?:(\\d+)x )?(.+?)(?: \\(.*)?$");

	/** A "RARE CROP! Fermento (+97)" line: the item and how many, or {@code null}. */
	public static Map.@Nullable Entry<String, Long> rareCrop(String line) {
		Matcher matcher = RARE_CROP.matcher(line.strip());
		if (!matcher.matches()) return null;
		long count = matcher.group(1) == null ? 1 : Long.parseLong(matcher.group(1));
		return Map.entry(idForName(matcher.group(2)), count);
	}

	private static final Pattern SACK_LINE = Pattern.compile("(?m)^\\s*([+-])([\\d,]+) (.+?) \\((.+)\\)\\s*$");

	/**
	 * What a [Sacks] report's hovers say reached the sacks, in base units: "Added items:" lines count, "Removed items:"
	 * lines take away (moving crops through a compactor isn't income). Only gains are returned.
	 */
	public static Map<String, Long> sackGains(List<String> hovers) {
		Map<String, Long> changes = new LinkedHashMap<>();
		for (String hover : hovers.stream().distinct().toList()) {
			if (!hover.startsWith("Added") && !hover.startsWith("Removed")) continue;
			Matcher matcher = SACK_LINE.matcher(hover);
			while (matcher.find()) {
				Unit unit = unit(idForName(matcher.group(3)));
				long count = Long.parseLong(matcher.group(2).replace(",", "")) * unit.amount();
				changes.merge(unit.base(), matcher.group(1).equals("-") ? -count : count, Long::sum);
			}
		}
		changes.values().removeIf(count -> count <= 0);
		return changes;
	}

	/** Item counts in base units. */
	public static Map<String, Long> inBaseUnits(Map<String, Long> counts) {
		Map<String, Long> base = new HashMap<>();
		counts.forEach((id, count) -> {
			Unit unit = unit(id);
			base.merge(unit.base(), count * unit.amount(), Long::sum);
		});
		return base;
	}
}
