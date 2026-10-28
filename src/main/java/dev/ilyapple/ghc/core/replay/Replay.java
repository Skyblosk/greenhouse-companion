package dev.ilyapple.ghc.core.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ilyapple.ghc.core.Growth;
import dev.ilyapple.ghc.core.LookKeys;
import dev.ilyapple.ghc.core.LookTable;
import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.StageSet;
import dev.ilyapple.ghc.core.StageTracker;

/**
 * Runs the tracker over a real recording ({@code changes.jsonl}) and checks it against every Crop Diagnostics
 * reading in {@code diagnostics.jsonl}: did the tracker's stage set contain the stage the menu showed?
 *
 * <p>The stages come only from looks, right-clicks and the clock; the readings are used just to score. The clock
 * is rebuilt from the readings' "Next Stage" countdowns, because the alpha's 3-hour skips move it in jumps no look
 * can time: each skip shifts the countdown by {@code -3h + k * period}, which gives the number of ticks k, and the
 * ticks are applied at the moment the whole greenhouse respawns.
 */
public final class Replay {
	/** A skip moves the alpha's greenhouse clock forward by exactly this much. */
	static final long SKIP_MS = 3 * 3600_000L;
	private static final Pattern NEXT_STAGE = Pattern.compile("(?i)^\\s*next\\s*stage\\s*:\\s*(.+)$");
	private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([dhms])");

	/** One scored reading. {@code truth} is a set because Glasscorn's menu says FULLY GROWN at 7 and 8. */
	public record Result(String time, String corner, int cell, String plant, StageSet truth, @Nullable StageSet tracked, boolean contains,
			StageTracker.@Nullable Origin origin, boolean lookKnown, boolean lookChanged, boolean exact, String lookId) {
		public int width() {
			return tracked == null ? 0 : tracked.width();
		}
	}

	/** A tick event rebuilt from the countdowns: {@code count} ticks at {@code ms}. */
	public record Tick(long ms, int count, int skips) {}

	private record Event(long ms, int order, Kind kind, String corner, int cell, @Nullable JsonObject fingerprint,
			LookKeys.@Nullable Keys keys, @Nullable String text, int ticks, @Nullable Truth truth) {}

	private enum Kind { TICK, USE, OBSERVE, READING }

	private record Truth(String plantId, StageSet stages, String time) {}

	private final List<Event> events;
	private final List<Tick> ticks;
	private final Plants plants;

	private Replay(List<Event> events, List<Tick> ticks, Plants plants) {
		this.events = events;
		this.ticks = ticks;
		this.plants = plants;
	}

	public List<Tick> ticks() {
		return ticks;
	}

	public long readings() {
		return events.stream().filter(event -> event.kind() == Kind.READING).count();
	}

	/** Reads a recorder folder ({@code changes.jsonl}, {@code diagnostics.jsonl}); truth comes from {@code calibration}'s observations. */
	public static Replay load(Path folder, LookTable calibration, long periodMs) throws IOException {
		Plants plants = calibration.plants();
		// Truth: the calibration's observations, which the recorder re-parsed with its current parser
		// Readings added live carry their own timestamp, a few milliseconds after the diagnostics record's
		Map<String, TreeMap<Long, Truth>> truths = new HashMap<>();
		for (LookTable.Observation observation : calibration.observations()) {
			if (observation.time() == null || observation.cell() == null) continue;
			Plants.Plant plant = plants.byId(observation.plantId());
			if (plant == null) continue;
			StageSet stages = observation.fullyGrown() ? Growth.fullyGrownStages(plant) : StageSet.of(observation.stage());
			truths.computeIfAbsent(observation.cell(), cell -> new TreeMap<>())
					.put(java.time.Instant.parse(observation.time()).toEpochMilli(), new Truth(plant.id(), stages, observation.time()));
		}

		List<Event> events = new ArrayList<>();
		Map<String, List<long[]>> countdowns = new HashMap<>();
		for (String line : Files.readAllLines(folder.resolve("diagnostics.jsonl"), StandardCharsets.UTF_8)) {
			if (line.isBlank()) continue;
			JsonObject record = JsonParser.parseString(line).getAsJsonObject();
			if (!"diagnostics".equals(string(record, "type"))) continue;
			long ms = record.get("ms").getAsLong();
			String server = server(record);
			Long remaining = nextStageMs(record);
			if (remaining != null) countdowns.computeIfAbsent(server, key -> new ArrayList<>()).add(new long[] {ms, ms + remaining});
			if (!record.has("use") || !record.getAsJsonObject("use").has("cell")) continue;
			JsonObject cell = record.getAsJsonObject("use").getAsJsonObject("cell");
			int x = cell.get("x").getAsInt();
			int z = cell.get("z").getAsInt();
			TreeMap<Long, Truth> atCell = truths.get(x + "," + z);
			Map.Entry<Long, Truth> nearest = atCell == null ? null : atCell.ceilingEntry(ms - 1500);
			if (nearest == null || nearest.getKey() > ms + 1500) continue;
			Truth truth = nearest.getValue();
			events.add(new Event(ms, 3, Kind.READING, server + "|" + corner(cell), x * 10 + z, null, null, null, 0, truth));
		}

		Map<String, List<Long>> growth = new HashMap<>();
		Map<String, Long> lastUse = new HashMap<>();
		Map<String, Map<Long, Integer>> respawns = new HashMap<>();
		String server = UNTAGGED_SERVER;
		for (String line : Files.readAllLines(folder.resolve("changes.jsonl"), StandardCharsets.UTF_8)) {
			if (line.isBlank()) continue;
			JsonObject record = JsonParser.parseString(line).getAsJsonObject();
			String type = string(record, "type");
			long ms = record.get("ms").getAsLong();
			if (type.equals("located") || type.equals("session")) server = server(record);
			String at = server + "|";
			switch (type) {
				case "change", "text" -> {
					JsonObject cell = record.getAsJsonObject("cell");
					JsonObject after = record.getAsJsonObject("after");
					LookKeys.Keys afterKeys = LookKeys.keysOf(after);
					events.add(new Event(ms, 2, Kind.OBSERVE, at + corner(cell), index(cell), after, afterKeys, null, 0, null));
					JsonArray causes = record.has("causes") ? record.getAsJsonArray("causes") : new JsonArray();
					if (contains(causes, "added") && contains(causes, "removed")) respawns.computeIfAbsent(server, key -> new HashMap<>()).merge(ms, 1, Integer::sum);
					// A plant that grew, or one that appeared without a right-click (a spawn): a tick just happened
					if (type.equals("change") && record.has("before")) {
						LookKeys.Keys beforeKeys = LookKeys.keysOf(record.getAsJsonObject("before"));
						boolean grew = !beforeKeys.isEmpty() && !afterKeys.isEmpty() && beforeKeys.plant() != null && beforeKeys.plant().equals(afterKeys.plant())
								&& !beforeKeys.key().equals(afterKeys.key());
						Long used = lastUse.get(at + corner(cell) + "/" + index(cell));
						boolean spawned = beforeKeys.isEmpty() && afterKeys.plant() != null && (used == null || ms - used > StageTracker.USE_WINDOW_MS);
						if (grew || spawned) growth.computeIfAbsent(server, key -> new ArrayList<>()).add(ms);
					}
				}
				case "snapshot" -> {
					for (JsonElement element : record.getAsJsonArray("cells")) {
						JsonObject snapshot = element.getAsJsonObject();
						JsonObject cell = snapshot.getAsJsonObject("cell");
						JsonObject fingerprint = snapshot.getAsJsonObject("fingerprint");
						events.add(new Event(ms, 2, Kind.OBSERVE, at + corner(cell), index(cell), fingerprint, LookKeys.keysOf(fingerprint), null, 0, null));
					}
				}
				case "use" -> {
					JsonObject cell = record.getAsJsonObject("cell");
					lastUse.put(at + corner(cell) + "/" + index(cell), ms);
					events.add(new Event(ms, 1, Kind.USE, at + corner(cell), index(cell), null, null, string(record, "heldItem"), 0, null));
				}
				default -> {
				}
			}
		}
		// Each server keeps its own greenhouses and its own clock
		List<Tick> ticks = new ArrayList<>();
		for (Map.Entry<String, List<long[]>> entry : countdowns.entrySet()) {
			// A skip respawns every plant of the greenhouse in the same instant
			List<Long> bursts = new ArrayList<>(growth.getOrDefault(entry.getKey(), List.of()));
			respawns.getOrDefault(entry.getKey(), Map.of()).forEach((ms, count) -> {
				if (count >= 3) bursts.add(ms);
			});
			bursts.sort(Long::compare);
			for (Tick tick : rebuildClock(entry.getValue(), bursts, periodMs)) {
				ticks.add(tick);
				events.add(new Event(tick.ms(), 0, Kind.TICK, entry.getKey() + "|", -1, null, null, null, tick.count(), null));
			}
		}
		ticks.sort(Comparator.comparingLong(Tick::ms));
		events.sort(Comparator.comparingLong(Event::ms).thenComparingInt(Event::order));
		return new Replay(events, ticks, plants);
	}

	/**
	 * Every pair of consecutive countdowns gives the ticks between them: the predicted next tick moved by
	 * {@code k * period - skips * 3h}. The fewest skips that fit wins. A skip's ticks fall on the first sign of growth
	 * between the two readings (the whole greenhouse respawning, or a plant changing look), else just before the second
	 * reading. Ticks without a skip fall on the predicted time, or a moment earlier if a plant visibly grew first.
	 */
	static List<Tick> rebuildClock(List<long[]> countdowns, List<Long> bursts, long periodMs) {
		List<Tick> ticks = new ArrayList<>();
		countdowns.sort(Comparator.comparingLong(pair -> pair[0]));
		for (int i = 1; i < countdowns.size(); i++) {
			long[] previous = countdowns.get(i - 1);
			long[] current = countdowns.get(i);
			long shift = current[1] - previous[1];
			if (Math.abs(shift) < 10_000) continue;
			int skips = -1;
			int count = 0;
			for (int s = 0; s <= 3 && skips < 0; s++) {
				double exact = (double) (shift + s * SKIP_MS) / periodMs;
				int k = (int) Math.round(exact);
				if (k < 0) continue;
				double residual = Math.abs(shift + s * SKIP_MS - (double) k * periodMs);
				if (residual < 0.08 * periodMs * Math.max(1, k)) {
					skips = s;
					count = k;
				}
			}
			if (skips < 0 || count == 0) continue;
			if (skips == 0) {
				for (int j = 0; j < count; j++) {
					long at = previous[1] + j * (current[1] - previous[1]) / count;
					for (long seen : bursts) {
						if (seen >= at - 5_000 && seen <= at) {
							at = seen - 1;
							break;
						}
					}
					ticks.add(new Tick(at, 1, 0));
				}
			} else {
				long at = current[0] - 1;
				for (long burst : bursts) {
					if (burst > previous[0] && burst <= current[0]) {
						// Just before it: the records of one respawn are spread over a few milliseconds
						at = Math.max(previous[0] + 1, burst - 1);
						break;
					}
				}
				ticks.add(new Tick(at, count, skips));
			}
		}
		return ticks;
	}

	/** Runs the tracker with {@code table} and scores every reading. */
	public List<Result> run(LookTable table) {
		return run(table, null, -1, null);
	}

	/** Whether trackers made by {@link #run} narrow unknown looks to unread stages. */
	private boolean unknownLooksAreUnread = true;

	public Replay unknownLooksAreUnread(boolean value) {
		Replay copy = new Replay(events, ticks, plants);
		copy.unknownLooksAreUnread = value;
		return copy;
	}

	/** Like {@link #run(LookTable)}, printing every event for one cell of one greenhouse to {@code trace}. */
	public List<Result> run(LookTable table, @Nullable String traceCorner, int traceCell, java.util.function.@Nullable Consumer<String> trace) {
		return run(table, traceCorner, traceCell, trace, new HashMap<>());
	}

	/** Every greenhouse's tracker at the end of the recording, keyed {@code server|x,z}: a real starting point for demos. */
	public Map<String, StageTracker> finalTrackers(LookTable table) {
		Map<String, StageTracker> trackers = new HashMap<>();
		run(table, null, -1, null, trackers);
		return trackers;
	}

	private List<Result> run(LookTable table, @Nullable String traceCorner, int traceCell, java.util.function.@Nullable Consumer<String> trace,
			Map<String, StageTracker> trackers) {
		List<Result> results = new ArrayList<>();
		for (Event event : events) {
			switch (event.kind()) {
				case TICK -> {
					trackers.forEach((key, tracker) -> {
						if (key.startsWith(event.corner())) tracker.tick(event.ticks());
					});
					if (trace != null) trace.accept(java.time.Instant.ofEpochMilli(event.ms()) + " TICK x" + event.ticks() + " -> "
							+ describe(trackers.containsKey(traceCorner) ? trackers.get(traceCorner).get(traceCell) : null));
				}
				case USE -> {
					trackers.computeIfAbsent(event.corner(), corner -> new StageTracker(table, unknownLooksAreUnread)).use(event.cell(), event.text(), event.ms());
					if (trace != null && event.corner().equals(traceCorner) && event.cell() == traceCell) {
						trace.accept(java.time.Instant.ofEpochMilli(event.ms()) + " USE " + event.text());
					}
				}
				case OBSERVE -> {
					StageTracker.Update update = trackers.computeIfAbsent(event.corner(), corner -> new StageTracker(table, unknownLooksAreUnread))
							.observe(event.cell(), event.fingerprint(), event.keys(), event.ms());
					if (trace != null && event.corner().equals(traceCorner) && event.cell() == traceCell) {
						LookTable.Match match = update.after() == null ? null : table.match(update.after().plant(), event.fingerprint(), event.keys().key());
						trace.accept(java.time.Instant.ofEpochMilli(event.ms()) + " LOOK " + LookKeys.shortId(event.keys().key()) + " " + event.keys().summary()
								+ " | look says " + (match == null ? "-" : match.stages() + (match.known() ? "" : " (unknown)")) + " -> " + describe(update.after())
								+ (update.stalled() ? " STALLED" : "") + (update.contradiction() ? " CONTRADICTION" : ""));
					}
				}
				case READING -> {
					StageTracker tracker = trackers.get(event.corner());
					Truth truth = event.truth();
					StageTracker.Tracked tracked = tracker == null ? null : tracker.get(event.cell());
					if (tracker != null && (tracked == null || !tracked.plant().id().equals(truth.plantId()))) {
						// A 2x2 or 3x3 plant: the clicked cell may hold only its hidden click block, so use the nearest cell of it
						tracked = nearest(tracker, event.cell(), truth.plantId());
					}
					if (trace != null && event.corner().equals(traceCorner) && event.cell() == traceCell) {
						trace.accept(java.time.Instant.ofEpochMilli(event.ms()) + " READING menu " + truth.stages() + " tracker " + (tracked == null ? null : tracked.stages()));
					}
					boolean samePlant = tracked != null && tracked.plant().id().equals(truth.plantId());
					StageSet stages = samePlant ? tracked.stages() : null;
					results.add(new Result(truth.time(), event.corner(), event.cell(), truth.plantId(), truth.stages(), stages,
							stages != null && !stages.intersect(truth.stages()).isEmpty(), samePlant ? tracked.origin() : null,
							samePlant && tracked.lookKnown(), samePlant && tracked.lookChanged(), samePlant && tracked.exact(),
							samePlant ? LookKeys.shortId(tracked.lookKey()) : "-"));
				}
			}
		}
		return results;
	}

	/**
	 * Each reading scored with a table that lacks what {@code holdOut} picks for it, e.g. the reading itself or every
	 * reading of that plant at that stage. Readings that pick the same observations share one run.
	 */
	public List<Result> runHoldingOut(LookTable table, java.util.function.Function<Result, Predicate<LookTable.Observation>> holdOut,
			java.util.function.Function<Result, String> groupKey) {
		List<Result> baseline = run(table);
		Map<String, List<Integer>> groups = new LinkedHashMap<>();
		for (int i = 0; i < baseline.size(); i++) groups.computeIfAbsent(groupKey.apply(baseline.get(i)), key -> new ArrayList<>()).add(i);
		Result[] out = new Result[baseline.size()];
		groups.values().parallelStream().forEach(indices -> {
			Result first = baseline.get(indices.getFirst());
			List<Result> rerun = run(table.without(holdOut.apply(first)));
			for (int index : indices) out[index] = rerun.get(index);
		});
		return List.of(out);
	}

	private StageTracker.@Nullable Tracked nearest(StageTracker tracker, int cell, String plantId) {
		Plants.Plant plant = plants.byId(plantId);
		if (plant == null || plant.size() < 2) return tracker.get(cell);
		StageTracker.Tracked best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (StageTracker.Tracked other : tracker.all()) {
			if (!other.plant().id().equals(plantId)) continue;
			int distance = Math.max(Math.abs(other.x() - cell / 10), Math.abs(other.z() - cell % 10));
			if (distance < plant.size() && distance < bestDistance) {
				best = other;
				bestDistance = distance;
			}
		}
		return best != null ? best : tracker.get(cell);
	}

	private static String describe(StageTracker.@Nullable Tracked tracked) {
		if (tracked == null) return "-";
		return tracked.plant().id() + " " + tracked.stages() + " " + tracked.origin().name().toLowerCase()
				+ (tracked.mayStall() ? " may-stall" : "") + (tracked.lookKnown() ? "" : " unknown-look") + (tracked.blockOnly() ? " block-only" : "");
	}

	// Parsing helpers

	private static @Nullable Long nextStageMs(JsonObject record) {
		if (!record.has("slots")) return null;
		for (JsonElement element : record.getAsJsonArray("slots")) {
			JsonObject slot = element.getAsJsonObject();
			if (!slot.has("lore")) continue;
			for (JsonElement lore : slot.getAsJsonArray("lore")) {
				Matcher matcher = NEXT_STAGE.matcher(lore.getAsString());
				if (!matcher.find()) continue;
				Matcher parts = DURATION.matcher(matcher.group(1));
				long total = 0;
				boolean any = false;
				while (parts.find()) {
					any = true;
					long value = Long.parseLong(parts.group(1));
					total += switch (parts.group(2)) {
						case "d" -> value * 86_400_000L;
						case "h" -> value * 3_600_000L;
						case "m" -> value * 60_000L;
						default -> value * 1000L;
					};
				}
				return any ? total : null;
			}
		}
		return null;
	}

	/** Readings and sessions from before the recorder noted the server were all on the alpha. */
	static final String UNTAGGED_SERVER = "alpha.hypixel.net";

	private static String server(JsonObject record) {
		String server = string(record, "server");
		return server.isEmpty() ? UNTAGGED_SERVER : server;
	}

	private static String corner(JsonObject cell) {
		return (cell.get("worldX").getAsInt() - cell.get("x").getAsInt()) + "," + (cell.get("worldZ").getAsInt() - cell.get("z").getAsInt());
	}

	private static int index(JsonObject cell) {
		return cell.get("x").getAsInt() * 10 + cell.get("z").getAsInt();
	}

	private static boolean contains(JsonArray array, String value) {
		for (JsonElement element : array) if (element.getAsString().equals(value)) return true;
		return false;
	}

	private static String string(JsonObject json, String member) {
		return json.has(member) && !json.get(member).isJsonNull() ? json.get(member).getAsString() : "";
	}

	public Plants plants() {
		return plants;
	}

	/** Counts per plant, for the report. */
	public static Map<String, int[]> byPlant(List<Result> results) {
		Map<String, int[]> map = new TreeMap<>();
		for (Result result : results) {
			int[] counts = map.computeIfAbsent(result.plant(), plant -> new int[2]);
			counts[0]++;
			if (result.contains()) counts[1]++;
		}
		return map;
	}
}
