package dev.ilyapple.ghc.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The mutations the player has: what their sacks held the last time the mod read them, plus manual adjustments
 * made in the UI. Keyed by simulator id. A manual adjustment is kept as a difference from the sack count, so it
 * survives the next sack read; {@link #set} works out the difference for an exact number.
 */
public final class Inventory {
	private final Map<String, Integer> sacks = new TreeMap<>();
	private final Map<String, Integer> adjustments = new TreeMap<>();
	private long sacksReadMs;

	public int count(String id) {
		return Math.max(0, sacks.getOrDefault(id, 0) + adjustments.getOrDefault(id, 0));
	}

	public int sackCount(String id) {
		return sacks.getOrDefault(id, 0);
	}

	public int adjustment(String id) {
		return adjustments.getOrDefault(id, 0);
	}

	public long sacksReadMs() {
		return sacksReadMs;
	}

	/** One sack slot was read: {@code count} of {@code id} stored. */
	public void setSack(String id, int count, long ms) {
		sacks.put(id, Math.max(0, count));
		sacksReadMs = Math.max(sacksReadMs, ms);
	}

	/** Adds {@code delta} to the manual adjustment. */
	public void adjust(String id, int delta) {
		int value = adjustments.getOrDefault(id, 0) + delta;
		if (value == 0) adjustments.remove(id);
		else adjustments.put(id, value);
	}

	/** Makes {@link #count} exactly {@code count}, whatever the sacks say. */
	public void set(String id, int count) {
		int value = Math.max(0, count) - sacks.getOrDefault(id, 0);
		if (value == 0) adjustments.remove(id);
		else adjustments.put(id, value);
	}

	public void clearAdjustments() {
		adjustments.clear();
	}

	/** Every item with a non-zero count, by id. */
	public Map<String, Integer> all() {
		Map<String, Integer> all = new TreeMap<>();
		for (String id : sacks.keySet()) if (count(id) > 0) all.put(id, count(id));
		for (String id : adjustments.keySet()) if (count(id) > 0) all.put(id, count(id));
		return all;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		JsonObject sackJson = new JsonObject();
		sacks.forEach(sackJson::addProperty);
		JsonObject adjustmentJson = new JsonObject();
		adjustments.forEach(adjustmentJson::addProperty);
		json.add("sacks", sackJson);
		json.add("adjustments", adjustmentJson);
		json.addProperty("sacksReadMs", sacksReadMs);
		return json;
	}

	public static Inventory fromJson(JsonObject json) {
		Inventory inventory = new Inventory();
		for (Map.Entry<String, Integer> entry : ints(json, "sacks").entrySet()) inventory.sacks.put(entry.getKey(), entry.getValue());
		for (Map.Entry<String, Integer> entry : ints(json, "adjustments").entrySet()) inventory.adjustments.put(entry.getKey(), entry.getValue());
		if (json.has("sacksReadMs")) inventory.sacksReadMs = json.get("sacksReadMs").getAsLong();
		return inventory;
	}

	private static Map<String, Integer> ints(JsonObject json, String member) {
		Map<String, Integer> map = new LinkedHashMap<>();
		if (!json.has(member) || !json.get(member).isJsonObject()) return map;
		for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject(member).entrySet()) map.put(entry.getKey(), entry.getValue().getAsInt());
		return map;
	}
}
