package dev.ilyapple.ghc.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A copy of the stage recorder's {@code Fingerprinter.keysOf} and {@code Crops} without any Minecraft classes, so
 * a live fingerprint and a calibration reading get the same key. {@code LookKeysTest} checks that every key in a
 * real {@code calibration.json} comes out the same from its saved fingerprint. Change both copies together.
 */
public final class LookKeys {
	public static final String EMPTY_KEY = "empty";
	private static final Pattern STAGE_HOLOGRAM = Pattern.compile("(?i)^\\s*stage\\s+(\\d+)\\s*$");

	private LookKeys() {}

	/**
	 * What a fingerprint's JSON boils down to.
	 *
	 * @param key            the model only: plant stands (without head pose), displays and blocks, digits in names replaced by {@code #}
	 * @param raw            the key plus text holograms, names as shown
	 * @param plant          the plant named by the first head or display item, else by a vanilla block
	 * @param plantFromBlock the plant came only from a vanilla block, which may be a bigger mutation's hidden click target
	 * @param holograms      text holograms (stands with only a name, text displays), lowest first
	 */
	public record Keys(String key, String raw, String summary, @Nullable String plant, boolean plantFromBlock, List<String> holograms) {
		public boolean isEmpty() {
			return EMPTY_KEY.equals(key);
		}

		/** The number on a "Stage N" hologram (All-in Aloe shows one), or {@code null}. */
		public @Nullable Integer hologramStage() {
			for (String text : holograms) {
				Matcher matcher = STAGE_HOLOGRAM.matcher(text);
				if (matcher.find()) return Integer.parseInt(matcher.group(1));
			}
			return null;
		}
	}

	public static Keys keysOf(JsonObject fingerprint) {
		record Part(double dy, String key, String raw, String summary, boolean inKey, @Nullable String plant, @Nullable String hologram) {}
		List<Part> parts = new ArrayList<>();

		JsonArray entities = fingerprint.has("entities") ? fingerprint.getAsJsonArray("entities") : new JsonArray();
		for (JsonElement element : entities) {
			JsonObject entity = element.getAsJsonObject();
			double dy = entity.get("dy").getAsDouble();
			String kind = string(entity, "kind");
			String name = string(entity, "name");
			StringBuilder key = new StringBuilder();
			String summary;
			boolean inKey = true;
			@Nullable String plant = null;

			switch (kind) {
				case "armor_stand" -> {
					String head = string(entity, "head");
					JsonObject slots = entity.has("slots") ? entity.getAsJsonObject("slots") : new JsonObject();
					if (head.isEmpty()) {
						// Blank markers and pets, saved before the recorder dropped them at the source
						if (!slots.isEmpty() || name.isEmpty()) continue;
						// A text hologram: kept in raw, but not part of the model
						inKey = false;
					}
					key.append("AS dy=").append(dy)
							.append(" head=").append(head)
							.append(" tex=").append(entity.has("tex") ? entity.get("tex").getAsString() : "-")
							.append(flag(entity, "small") ? " small" : "")
							.append(flag(entity, "invisible") ? " invis" : "")
							.append(flag(entity, "marker") ? " marker" : "");
					if (!slots.isEmpty()) {
						for (Map.Entry<String, JsonElement> slot : slots.entrySet()) {
							key.append(' ').append(slot.getKey()).append('=').append(itemKey(slot.getValue().getAsJsonObject()));
						}
						key.append(" arms=").append(triple(entity.getAsJsonArray("rightArm"))).append('/').append(triple(entity.getAsJsonArray("leftArm")));
					}
					plant = head.isEmpty() ? null : byHeadName(head);
					summary = (head.isEmpty() ? "\"" + name + "\"" : head) + "@" + dy;
				}
				case "item_display" -> {
					JsonObject item = entity.getAsJsonObject("item");
					String itemKey = itemKey(item);
					key.append("ID dy=").append(dy).append(" item=").append(itemKey);
					boolean empty = itemKey.equals("empty");
					summary = "item_display:" + (empty ? "empty" : string(item, "name")) + "@" + dy;
					if (!empty) plant = byHeadName(string(item, "name"));
				}
				case "block_display" -> {
					key.append("BD dy=").append(dy).append(" block=").append(string(entity, "block"));
					summary = "block_display:" + string(entity, "block") + "@" + dy;
				}
				case "text_display" -> {
					inKey = false;
					key.append("TD dy=").append(dy);
					summary = "text:\"" + string(entity, "text") + "\"@" + dy;
				}
				default -> {
					key.append(kind).append(" dy=").append(dy)
							.append(" w=").append(entity.get("width").getAsDouble())
							.append(" h=").append(entity.get("height").getAsDouble());
					summary = kind + "@" + dy;
				}
			}

			String text = kind.equals("text_display") ? string(entity, "text") : name;
			String label = kind.equals("text_display") ? " text=" : " name=";
			String partKey = key + (text.isEmpty() ? "" : label + normalizeDigits(text));
			String partRaw = key + (text.isEmpty() ? "" : label + text);
			parts.add(new Part(dy, partKey, partRaw, summary, inKey, plant, inKey || text.isEmpty() ? null : text));
		}
		parts.sort(Comparator.comparingDouble(Part::dy).thenComparing(Part::key));

		StringBuilder key = new StringBuilder();
		StringBuilder raw = new StringBuilder();
		List<String> summaries = new ArrayList<>();
		List<String> holograms = new ArrayList<>();
		@Nullable String plant = null;
		for (Part part : parts) {
			if (part.inKey()) key.append(key.isEmpty() ? "" : " | ").append(part.key());
			raw.append(raw.isEmpty() ? "" : " | ").append(part.raw());
			summaries.add(part.summary());
			if (part.hologram() != null) holograms.add(part.hologram());
			if (plant == null) plant = part.plant();
		}

		boolean fromBlock = false;
		List<String> blocks = blocksOf(fingerprint);
		if (!blocks.isEmpty()) {
			String part = "BLOCKS " + String.join("/", blocks);
			key.append(key.isEmpty() ? "" : " | ").append(part);
			raw.append(raw.isEmpty() ? "" : " | ").append(part);
			summaries.add(String.join("/", blocks));
			if (plant == null) {
				for (String block : blocks) {
					plant = byBlockId(blockPath(block));
					if (plant != null) {
						fromBlock = true;
						break;
					}
				}
			}
		}

		return new Keys(key.isEmpty() ? EMPTY_KEY : key.toString(), raw.isEmpty() ? EMPTY_KEY : raw.toString(),
				summaries.isEmpty() ? "empty" : String.join(", ", summaries), plant, fromBlock, List.copyOf(holograms));
	}

	public static List<String> blocksOf(JsonObject fingerprint) {
		List<String> blocks = new ArrayList<>();
		if (fingerprint.has("blocks")) fingerprint.getAsJsonArray("blocks").forEach(block -> blocks.add(block.getAsString()));
		return blocks;
	}

	/** {@code minecraft:wheat[age=5]} → {@code wheat}. */
	public static String blockPath(String blockState) {
		String id = blockState.contains("[") ? blockState.substring(0, blockState.indexOf('[')) : blockState;
		return id.substring(id.indexOf(':') + 1);
	}

	/** Short stable id for a key, the same one the recorder shows in chat. */
	public static String shortId(String key) {
		return String.format("%08x", key.hashCode());
	}

	public static String normalizeDigits(String text) {
		return text.replaceAll("\\d+", "#");
	}

	private static String string(JsonObject json, String member) {
		return json.has(member) && !json.get(member).isJsonNull() ? json.get(member).getAsString() : "";
	}

	private static boolean flag(JsonObject json, String member) {
		return json.has(member) && json.get(member).getAsBoolean();
	}

	private static String triple(@Nullable JsonArray array) {
		if (array == null) return "?";
		List<String> values = new ArrayList<>();
		array.forEach(value -> values.add(String.valueOf(value.getAsDouble())));
		return String.join(",", values);
	}

	private static String itemKey(JsonObject item) {
		String id = string(item, "id");
		if (id.isEmpty() || id.equals("minecraft:air")) return "empty";
		StringBuilder key = new StringBuilder(id).append('/').append(string(item, "name"));
		if (item.has("tex")) key.append("/tex=").append(item.get("tex").getAsString());
		if (item.has("dye")) key.append("/dye=").append(item.get("dye").getAsString().replace("#", ""));
		return key.toString();
	}

	// The recorder's Crops table: head-name tokens, longest first, matched case-insensitively with "contains"
	private record Token(String token, String name) {}

	private static final List<Token> TOKENS = List.of(
			new Token("ashwreath", "Ashwreath"),
			new Token("choconut", "Choconut"),
			new Token("dustgrain", "Dustgrain"),
			new Token("gloomgourd", "Gloomgourd"),
			new Token("lonelilly", "Lonelily"),
			new Token("lonelily", "Lonelily"),
			new Token("scourroot", "Scourroot"),
			new Token("shadevine", "Shadevine"),
			new Token("veilshroom", "Veilshroom"),
			new Token("witherbloom", "Witherbloom"),
			new Token("chocoberry", "Chocoberry"),
			new Token("cindershade", "Cindershade"),
			new Token("coalroot", "Coalroot"),
			new Token("creambloom", "Creambloom"),
			new Token("duskbloom", "Duskbloom"),
			new Token("thornshade", "Thornshade"),
			new Token("blastberry", "Blastberry"),
			new Token("cheesebite", "Cheesebite"),
			new Token("chloronite", "Chloronite"),
			new Token("donoteatshroom", "Do Not Eat Shroom"),
			new Token("fleshtrap", "Fleshtrap"),
			new Token("magicjellybean", "Magic Jellybean"),
			new Token("noctilume", "Noctilume"),
			new Token("snoozling", "Snoozling"),
			new Token("soggybud", "Soggybud"),
			new Token("chorusfruit", "Chorus Fruit"),
			new Token("plantboy", "PlantBoy Advance"),
			new Token("puffercloud", "Puffercloud"),
			new Token("shellfruit", "Shellfruit"),
			new Token("startlevine", "Startlevine"),
			new Token("stoplightpetal", "Stoplight Petal"),
			new Token("thunderling", "Thunderling"),
			new Token("turtlellini", "Turtlellini"),
			new Token("zombud", "Zombud"),
			new Token("allinaloe", "All-in Aloe"),
			new Token("devourer", "Devourer"),
			new Token("glasscorn", "Glasscorn"),
			new Token("godseed", "Godseed"),
			new Token("jerryseed", "Jerryflower"),
			new Token("jerryflower", "Jerryflower"),
			new Token("phantomleaf", "Phantomleaf"),
			new Token("timestalk", "Timestalk"),
			new Token("coco", "Cocoa Beans"),
			new Token("wildrose", "Wild Rose"),
			new Token("moonflower", "Moonflower"),
			new Token("sunflower", "Sunflower"),
			new Token("cactus", "Cactus"),
			new Token("melon", "Melon"),
			new Token("pumpkin", "Pumpkin"),
			new Token("brownmushroom", "Brown Mushroom"),
			new Token("redmushroom", "Red Mushroom"),
			new Token("helianthus", "Helianthus"),
			new Token("fermento", "Fermento"),
			new Token("squash", "Squash"),
			new Token("cropie", "Cropie")
	).stream().sorted(Comparator.comparingInt((Token t) -> t.token().length()).reversed()).toList();

	/** The plant whose token appears in {@code headName}, or {@code null}. */
	public static @Nullable String byHeadName(String headName) {
		String lower = headName.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
		for (Token token : TOKENS) {
			if (lower.contains(token.token())) return token.name();
		}
		return null;
	}

	/** Vanilla crop blocks used in the Greenhouse, keyed by block id path. */
	public static @Nullable String byBlockId(String blockIdPath) {
		return switch (blockIdPath) {
			case "wheat" -> "Wheat";
			case "carrots" -> "Carrot";
			case "potatoes" -> "Potato";
			case "nether_wart" -> "Nether Wart";
			case "red_mushroom" -> "Red Mushroom";
			case "brown_mushroom" -> "Brown Mushroom";
			case "sugar_cane" -> "Sugar Cane";
			case "dead_bush" -> "Dead Plant";
			case "fire" -> "Fire";
			default -> null;
		};
	}
}
