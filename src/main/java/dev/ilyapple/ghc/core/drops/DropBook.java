package dev.ilyapple.ghc.core.drops;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One book of the drop recorder (this game session's, or all time's): how many of each plant were harvested, and
 * every drop put down to each plant's harvests. Drops that couldn't be pinned on one plant kind are kept apart:
 * {@link #mixed} when harvests of several kinds could have made them, {@link #unmatched} when a "RARE CROP!" line came
 * with no harvest near it.
 */
public final class DropBook {
	/** What breaking a plant was. */
	public enum Kind {
		/** A fully grown plant that wasn't placed: a harvest. */
		HARVESTED,
		/** A placed item taken back: its item comes back, nothing drops. */
		PICKED_UP,
		/** Broken before it was fully grown. */
		EARLY
	}

	/** One plant's line: how often it was broken each way, and what came with its harvests (and pick-ups). */
	public static final class Line {
		private long harvested;
		private long pickedUp;
		private long early;
		private final Map<String, Long> drops = new TreeMap<>();
		private final Map<String, Long> pickupDrops = new TreeMap<>();

		public long harvested() {
			return harvested;
		}

		public long pickedUp() {
			return pickedUp;
		}

		public long early() {
			return early;
		}

		/** Items from this plant's harvests (and early breaks), by item id. */
		public Map<String, Long> drops() {
			return Collections.unmodifiableMap(drops);
		}

		/** Items that came back when placed ones were picked up. */
		public Map<String, Long> pickupDrops() {
			return Collections.unmodifiableMap(pickupDrops);
		}

		public long drop(String item) {
			return drops.getOrDefault(item, 0L);
		}
	}

	private long startedMs;
	private final Map<String, Line> lines = new TreeMap<>();
	private final Map<String, Long> mixed = new TreeMap<>();
	private final Map<String, Long> unmatched = new TreeMap<>();

	public DropBook(long startedMs) {
		this.startedMs = startedMs;
	}

	public long startedMs() {
		return startedMs;
	}

	/** Every plant broken, by simulator id. */
	public Map<String, Line> lines() {
		return Collections.unmodifiableMap(lines);
	}

	public Map<String, Long> mixed() {
		return Collections.unmodifiableMap(mixed);
	}

	public Map<String, Long> unmatched() {
		return Collections.unmodifiableMap(unmatched);
	}

	public void broke(String plant, Kind kind) {
		Line line = lines.computeIfAbsent(plant, id -> new Line());
		switch (kind) {
			case HARVESTED -> line.harvested++;
			case PICKED_UP -> line.pickedUp++;
			case EARLY -> line.early++;
		}
	}

	public void drop(String plant, Kind kind, String item, long count) {
		Line line = lines.computeIfAbsent(plant, id -> new Line());
		(kind == Kind.PICKED_UP ? line.pickupDrops : line.drops).merge(item, count, Long::sum);
	}

	public void mixed(String item, long count) {
		mixed.merge(item, count, Long::sum);
	}

	public void unmatched(String item, long count) {
		unmatched.merge(item, count, Long::sum);
	}

	/** Harvests of every plant {@code filter} lets through. */
	public long harvested(java.util.function.Predicate<String> filter) {
		long total = 0;
		for (Map.Entry<String, Line> entry : lines.entrySet()) if (filter.test(entry.getKey())) total += entry.getValue().harvested;
		return total;
	}

	/** {@code item} from the harvests of every plant {@code filter} lets through. */
	public long dropped(String item, java.util.function.Predicate<String> filter) {
		long total = 0;
		for (Map.Entry<String, Line> entry : lines.entrySet()) if (filter.test(entry.getKey())) total += entry.getValue().drop(item);
		return total;
	}

	/** Everything recorded, by item: harvests, pick-ups, mixed and unmatched together. */
	public Map<String, Long> allDrops() {
		Map<String, Long> all = new TreeMap<>();
		for (Line line : lines.values()) {
			line.drops.forEach((item, count) -> all.merge(item, count, Long::sum));
			line.pickupDrops.forEach((item, count) -> all.merge(item, count, Long::sum));
		}
		mixed.forEach((item, count) -> all.merge(item, count, Long::sum));
		unmatched.forEach((item, count) -> all.merge(item, count, Long::sum));
		return all;
	}

	public boolean isEmpty() {
		return lines.isEmpty() && mixed.isEmpty() && unmatched.isEmpty();
	}

	public void clear(long now) {
		startedMs = now;
		lines.clear();
		mixed.clear();
		unmatched.clear();
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("startedMs", startedMs);
		JsonObject plants = new JsonObject();
		lines.forEach((id, line) -> {
			JsonObject entry = new JsonObject();
			entry.addProperty("harvested", line.harvested);
			entry.addProperty("pickedUp", line.pickedUp);
			entry.addProperty("early", line.early);
			entry.add("drops", counts(line.drops));
			entry.add("pickupDrops", counts(line.pickupDrops));
			plants.add(id, entry);
		});
		json.add("plants", plants);
		json.add("mixed", counts(mixed));
		json.add("unmatched", counts(unmatched));
		return json;
	}

	public static DropBook fromJson(JsonObject json) {
		DropBook book = new DropBook(json.has("startedMs") ? json.get("startedMs").getAsLong() : System.currentTimeMillis());
		if (json.has("plants")) {
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("plants").entrySet()) {
				JsonObject saved = entry.getValue().getAsJsonObject();
				Line line = new Line();
				line.harvested = saved.has("harvested") ? saved.get("harvested").getAsLong() : 0;
				line.pickedUp = saved.has("pickedUp") ? saved.get("pickedUp").getAsLong() : 0;
				line.early = saved.has("early") ? saved.get("early").getAsLong() : 0;
				read(saved, "drops", line.drops);
				read(saved, "pickupDrops", line.pickupDrops);
				book.lines.put(entry.getKey(), line);
			}
		}
		read(json, "mixed", book.mixed);
		read(json, "unmatched", book.unmatched);
		return book;
	}

	private static JsonObject counts(Map<String, Long> counts) {
		JsonObject json = new JsonObject();
		counts.forEach(json::addProperty);
		return json;
	}

	private static void read(JsonObject json, String member, Map<String, Long> into) {
		if (!json.has(member) || !json.get(member).isJsonObject()) return;
		json.getAsJsonObject(member).entrySet().forEach(entry -> into.put(entry.getKey(), entry.getValue().getAsLong()));
	}
}
