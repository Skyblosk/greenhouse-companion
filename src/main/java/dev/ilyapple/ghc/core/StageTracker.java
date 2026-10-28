package dev.ilyapple.ghc.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonObject;

/**
 * Works out every greenhouse plant's stage from its look and the shared growth clock, without the Plant
 * Diagnostics Tool.
 *
 * <p>Each cell keeps the set of stages its plant can be at. A look narrows the set to the stages that look was
 * read at ({@link LookTable}). A tick of the shared clock moves the whole set forward ({@link Growth}). Because
 * looks only change on a tick, the two together pin a plant down: once a look changes from A to B, the plant is
 * at a stage of B whose stage below was A. A mutation that appears in a cell seen empty k ticks ago spawned at one
 * of those ticks, so it starts at 1..k; one the player placed starts fully grown; a crop the player planted at 1.
 *
 * <p>When a look contradicts the clock, the plant must have missed a tick (dry or wilting). From then on it may
 * stall on any tick: the high end keeps moving and the low end stays. If even that doesn't fit, the look wins.
 */
public final class StageTracker {
	public enum Origin { SPAWNED, PLACED, PLANTED, UNKNOWN }

	/** A placement or planting right-click is matched to an appearance this long after it. */
	public static final long USE_WINDOW_MS = 10_000;

	/** One tracked plant, as the evaluator and the UI see it. */
	public record Tracked(int cell, Plants.Plant plant, Origin origin, StageSet stages, boolean lookKnown, boolean exact,
			boolean lookChanged, boolean mayStall, boolean blockOnly, String lookKey, long firstSeenMs) {
		public int x() {
			return cell / 10;
		}

		public int z() {
			return cell % 10;
		}
	}

	/** What one observation did. {@code contradiction} means the look didn't fit the clock at all. */
	public record Update(int cell, @Nullable Tracked before, @Nullable Tracked after, boolean appeared, boolean removed,
			boolean lookChanged, boolean stalled, boolean contradiction) {}

	private static final class State {
		final Plants.Plant plant;
		Origin origin;
		final long firstSeenMs;
		final boolean blockOnly;
		StageSet anchor;
		int pendingMin;
		int pendingMax;
		String lookKey;
		JsonObject lookFingerprint;
		boolean lookKnown;
		boolean exact;
		boolean lookChanged;
		boolean mayStall;

		State(Plants.Plant plant, Origin origin, long firstSeenMs, boolean blockOnly, StageSet anchor, String lookKey, JsonObject lookFingerprint,
				boolean lookKnown) {
			this.plant = plant;
			this.origin = origin;
			this.firstSeenMs = firstSeenMs;
			this.blockOnly = blockOnly;
			this.anchor = anchor;
			this.lookKey = lookKey;
			this.lookFingerprint = lookFingerprint;
			this.lookKnown = lookKnown;
		}
	}

	private record Use(String held, long ms) {}

	private final LookTable table;
	private final Plants plants;
	private final Map<Integer, State> cells = new TreeMap<>();
	/** Ticks (at most) since each empty cell was last seen empty. */
	private final Map<Integer, Integer> emptyFor = new HashMap<>();
	private final Map<Integer, Use> uses = new HashMap<>();
	private long ticksMin;
	private long ticksMax;
	private final boolean unknownLooksAreUnread;

	public StageTracker(LookTable table) {
		this(table, true);
	}

	/**
	 * @param unknownLooksAreUnread narrow a look that isn't in the table to the stages no look has been read at.
	 *                              Wrong for a plant with an unseen second look at a stage that was read.
	 */
	public StageTracker(LookTable table, boolean unknownLooksAreUnread) {
		this.table = table;
		this.plants = table.plants();
		this.unknownLooksAreUnread = unknownLooksAreUnread;
	}

	public LookTable table() {
		return table;
	}

	/** {@code count} ticks of the shared growth clock have passed. */
	public void tick(int count) {
		tick(count, count);
	}

	/** Between {@code min} and {@code max} ticks have passed, for when the clock isn't known exactly. */
	public void tick(int min, int max) {
		if (min < 0 || max < min) throw new IllegalArgumentException(min + ".." + max);
		if (max == 0) return;
		ticksMin += min;
		ticksMax += max;
		for (State state : cells.values()) {
			state.pendingMin += min;
			state.pendingMax += max;
			if (state.pendingMax > 0) state.exact = false;
		}
		emptyFor.replaceAll((cell, ticks) -> ticks + max);
	}

	public long ticksMin() {
		return ticksMin;
	}

	public long ticksMax() {
		return ticksMax;
	}

	/** The player right-clicked {@code cell} holding {@code heldItem} (its display name). */
	public void use(int cell, String heldItem, long ms) {
		uses.put(cell, new Use(heldItem, ms));
	}

	/**
	 * A Crop Diagnostics reading for the plant on {@code cell}: what the menu says replaces what was worked out. The
	 * menu's plant, stage (or fully grown), origin (placed when "Uncollectable", else spawned or planted) and dry or
	 * stopped state all win. Returns {@code null} when nothing is tracked there.
	 */
	public @Nullable Update diagnose(int cell, Diagnosis reading, long ms) {
		State state = cells.get(cell);
		if (state == null) return null;
		Tracked before = tracked(cell, state);
		Plants.Plant plant = state.plant;
		if (reading.dead() && plants.byId("dead_plant") != null) plant = plants.byId("dead_plant");
		else if (reading.plant() != null && plants.byName(reading.plant()) != null) plant = plants.byName(reading.plant());
		StageSet stages;
		if (reading.dead()) stages = plant.allStages();
		else if (reading.stage() != null && plant.allStages().contains(reading.stage())) stages = StageSet.of(reading.stage());
		else if (reading.fullyGrown()) stages = Growth.fullyGrownStages(plant);
		else stages = plant == state.plant ? predict(state, state.mayStall) : plant.allStages();
		boolean grown = reading.fullyGrown() || reading.stage() != null && reading.stage() >= plant.readyStage();
		Origin origin = reading.origin(plant.mutation(), grown);
		if (origin == null) origin = state.origin;
		State fresh = plant == state.plant && origin == state.origin ? state
				: new State(plant, origin, state.firstSeenMs, state.blockOnly, stages, state.lookKey, state.lookFingerprint, state.lookKnown);
		fresh.anchor = stages;
		fresh.pendingMin = 0;
		fresh.pendingMax = 0;
		fresh.exact = stages.size() == 1;
		fresh.mayStall = reading.stalled();
		fresh.lookChanged = state.lookChanged;
		cells.put(cell, fresh);
		return new Update(cell, before, tracked(cell, fresh), false, false, false, false, false);
	}

	/** Marks a plant as one that may miss ticks (known to be dry), or clears it. */
	public void setMayStall(int cell, boolean mayStall) {
		State state = cells.get(cell);
		if (state != null) state.mayStall = mayStall;
	}

	/** Forgets every cell, as when the greenhouse is left for an unknown time. Keeps nothing. */
	public void clear() {
		cells.clear();
		emptyFor.clear();
		uses.clear();
	}

	/** A cell was seen with this fingerprint (recorder JSON format). */
	public Update observe(int cell, JsonObject fingerprint, long ms) {
		return observe(cell, fingerprint, LookKeys.keysOf(fingerprint), ms);
	}

	public Update observe(int cell, JsonObject fingerprint, LookKeys.Keys keys, long ms) {
		State state = cells.get(cell);
		Tracked before = state == null ? null : tracked(cell, state);
		Plants.Plant plant = keys.isEmpty() ? null : plants.byName(keys.plant());

		if (keys.isEmpty() || plant == null) {
			if (keys.isEmpty()) {
				cells.remove(cell);
				emptyFor.put(cell, 0);
			}
			return new Update(cell, before, keys.isEmpty() ? null : before, false, keys.isEmpty() && before != null, false, false, false);
		}

		LookTable.Match match = table.match(plant, fingerprint, keys.key());
		Integer hologram = plant.id().equals(Growth.ALOE) ? keys.hologramStage() : null;
		if (hologram != null && !plant.allStages().contains(hologram)) hologram = null;

		if (state == null || state.plant != plant) {
			// A different plant where one stood a few ticks ago: the old one went and the new one arrived since
			if (state != null && state.pendingMax > 0) emptyFor.put(cell, state.pendingMax);
			State fresh = appear(cell, plant, fingerprint, keys, match, hologram, ms);
			cells.put(cell, fresh);
			emptyFor.remove(cell);
			return new Update(cell, before, tracked(cell, fresh), true, false, false, false, false);
		}

		StageSet predicted = predict(state, state.mayStall);
		if (match.known() && hologram == null && !match.stages().isEmpty() && match.stages().lo() <= 1 && match.stages().hi() < predicted.lo()
				&& !Growth.canReset(plant)) {
			// The same kind back at its first stage, younger than it can be: harvested (or broken) and replanted between
			// two scans. Only a first-stage look counts, so a stage that was simply misjudged isn't taken for a harvest.
			emptyFor.put(cell, 0);
			State fresh = appear(cell, plant, fingerprint, keys, match, hologram, ms);
			cells.put(cell, fresh);
			emptyFor.remove(cell);
			return new Update(cell, before, tracked(cell, fresh), true, true, false, false, false);
		}
		StageSet next;
		boolean stalled = false;
		boolean contradiction = false;
		if (hologram != null) {
			next = StageSet.of(hologram);
			if (!predicted.contains(hologram)) {
				stalled = !state.mayStall && predict(state, true).contains(hologram);
				contradiction = !stalled;
			}
		} else {
			next = predicted.intersect(match.stages());
			if (!keys.key().equals(state.lookKey) && state.pendingMax > 0) {
				// The look changed on a tick, so the plant has left the old look's stages behind
				LookTable.Match old = table.match(plant, state.lookFingerprint, state.lookKey);
				StageSet left = old.look() == null ? next : next.minus(old.look().seen().offset(old.offset()));
				if (!left.isEmpty()) next = left;
			}
			if (!match.known() && unknownLooksAreUnread && !next.isEmpty()) {
				// A look nobody has read is most likely a stage nobody has read
				StageSet unread = table.unread(plant);
				StageSet narrowed = next.intersect(unread);
				if (narrowed.isEmpty() && !state.mayStall) {
					narrowed = predict(state, true).intersect(unread);
					stalled = !narrowed.isEmpty();
				}
				if (!narrowed.isEmpty()) next = narrowed;
			}
			if (next.isEmpty() && !state.mayStall) {
				next = predict(state, true).intersect(match.stages());
				stalled = !next.isEmpty();
			}
			if (next.isEmpty()) {
				next = match.stages();
				contradiction = true;
			}
		}
		if (stalled) state.mayStall = true;
		state.origin = settle(plant, state.origin, next);
		boolean lookChanged = !keys.key().equals(state.lookKey);
		if (lookChanged) state.lookChanged = true;
		state.anchor = next;
		state.pendingMin = 0;
		state.pendingMax = 0;
		state.lookKey = keys.key();
		state.lookFingerprint = fingerprint;
		state.lookKnown = match.known();
		state.exact = hologram != null || next.size() == 1;
		return new Update(cell, before, tracked(cell, state), false, false, lookChanged, stalled, contradiction);
	}

	private State appear(int cell, Plants.Plant plant, JsonObject fingerprint, LookKeys.Keys keys, LookTable.Match match, @Nullable Integer hologram, long ms) {
		Origin origin = Origin.UNKNOWN;
		StageSet start = plant.allStages();
		Use use = uses.remove(cell);
		if (use != null && ms - use.ms() <= USE_WINDOW_MS && plant == plants.byName(use.held())) {
			// Placed mutations come out fully grown; planted crops start at their first stage
			origin = plant.mutation() ? Origin.PLACED : Origin.PLANTED;
			start = plant.mutation() ? Growth.fullyGrownStages(plant) : StageSet.of(plant.allStages().lo());
		} else if (emptyFor.containsKey(cell)) {
			origin = Origin.SPAWNED;
			int ticks = Math.max(1, emptyFor.get(cell));
			start = plant.grows() ? StageSet.range(1, Math.min(ticks, plant.maxStage())) : StageSet.of(0);
		}
		StageSet stages = start.intersect(match.stages());
		if (stages.isEmpty()) stages = match.known() ? match.stages() : start;
		if (!match.known() && unknownLooksAreUnread) {
			StageSet narrowed = stages.intersect(table.unread(plant));
			if (!narrowed.isEmpty()) stages = narrowed;
		}
		if (hologram != null) stages = StageSet.of(hologram);
		State state = new State(plant, settle(plant, origin, stages), ms, keys.plantFromBlock(), stages, keys.key(), fingerprint, match.known());
		state.exact = hologram != null || stages.size() == 1;
		return state;
	}

	/**
	 * What a look says about where a plant came from: a base crop is always planted (they never spawn), and a mutation
	 * seen still growing wasn't placed (placed items come out fully grown), so it spawned. A placed Magic Jellybean can
	 * read 119, so only a Jellybean below that counts as growing.
	 */
	public static Origin settle(Plants.Plant plant, Origin origin, StageSet stages) {
		if (!plant.mutation()) return plant.id().equals("dead_plant") ? origin : Origin.PLANTED;
		if (origin != Origin.UNKNOWN || stages.isEmpty()) return origin;
		int grown = plant.readyStage() - (plant.id().equals(Growth.JELLYBEAN) ? 1 : 0);
		return stages.hi() < grown ? Origin.SPAWNED : origin;
	}

	/** The anchor moved forward by the ticks since it was set. */
	private static StageSet predict(State state, boolean mayStall) {
		if (state.pendingMax == 0) return state.anchor;
		StageSet current = Growth.advance(state.plant, state.anchor, state.pendingMin, mayStall);
		StageSet all = current;
		int unchanged = 0;
		for (int tick = state.pendingMin; tick < state.pendingMax; tick++) {
			current = Growth.advance(state.plant, current, 1, mayStall);
			StageSet widened = all.union(current);
			unchanged = widened.equals(all) ? unchanged + 1 : 0;
			all = widened;
			// Once nothing new has turned up for a full cycle, nothing will
			if (unchanged > state.plant.maxStage() + 1) break;
		}
		return all;
	}

	private static Tracked tracked(int cell, State state) {
		StageSet stages = predict(state, state.mayStall);
		// Still exact after ticks that didn't change the look, as long as the clock knows how many passed
		boolean exact = state.exact && (state.pendingMax == 0 || state.pendingMin == state.pendingMax && stages.size() == 1);
		return new Tracked(cell, state.plant, state.origin, stages, state.lookKnown, exact,
				state.lookChanged, state.mayStall, state.blockOnly, state.lookKey, state.firstSeenMs);
	}

	/** Ticks (at most) since {@code cell} was last seen empty, or {@code null} when it wasn't seen empty. */
	public @Nullable Integer emptyFor(int cell) {
		return emptyFor.get(cell);
	}

	public @Nullable Tracked get(int cell) {
		State state = cells.get(cell);
		return state == null ? null : tracked(cell, state);
	}

	/** Every tracked plant, by cell. */
	public List<Tracked> all() {
		List<Tracked> list = new ArrayList<>();
		cells.forEach((cell, state) -> list.add(tracked(cell, state)));
		return list;
	}

	public Collection<Integer> cells() {
		return cells.keySet();
	}

	/** Everything needed to pick up after a relog. Looks are saved as fingerprints so they re-key with today's rules. */
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("ticksMin", ticksMin);
		json.addProperty("ticksMax", ticksMax);
		com.google.gson.JsonArray list = new com.google.gson.JsonArray();
		cells.forEach((cell, state) -> {
			JsonObject entry = new JsonObject();
			entry.addProperty("cell", cell);
			entry.addProperty("plant", state.plant.id());
			entry.addProperty("origin", state.origin.name());
			entry.addProperty("firstSeenMs", state.firstSeenMs);
			entry.addProperty("blockOnly", state.blockOnly);
			com.google.gson.JsonArray stages = new com.google.gson.JsonArray();
			state.anchor.toList().forEach(stages::add);
			entry.add("anchor", stages);
			entry.addProperty("pendingMin", state.pendingMin);
			entry.addProperty("pendingMax", state.pendingMax);
			entry.add("look", state.lookFingerprint);
			entry.addProperty("lookChanged", state.lookChanged);
			entry.addProperty("mayStall", state.mayStall);
			list.add(entry);
		});
		json.add("cells", list);
		JsonObject empty = new JsonObject();
		emptyFor.forEach((cell, ticks) -> empty.addProperty(Integer.toString(cell), ticks));
		json.add("emptyFor", empty);
		return json;
	}

	public void load(JsonObject json) {
		clear();
		ticksMin = json.has("ticksMin") ? json.get("ticksMin").getAsLong() : 0;
		ticksMax = json.has("ticksMax") ? json.get("ticksMax").getAsLong() : 0;
		if (json.has("cells")) {
			for (var element : json.getAsJsonArray("cells")) {
				JsonObject entry = element.getAsJsonObject();
				Plants.Plant plant = plants.byId(entry.get("plant").getAsString());
				if (plant == null) continue;
				List<Integer> stages = new ArrayList<>();
				entry.getAsJsonArray("anchor").forEach(stage -> stages.add(stage.getAsInt()));
				JsonObject look = entry.getAsJsonObject("look");
				LookKeys.Keys keys = LookKeys.keysOf(look);
				State state = new State(plant, Origin.valueOf(entry.get("origin").getAsString()), entry.get("firstSeenMs").getAsLong(),
						entry.get("blockOnly").getAsBoolean(), StageSet.of(stages), keys.key(), look, table.match(plant, look, keys.key()).known());
				state.pendingMin = entry.get("pendingMin").getAsInt();
				state.pendingMax = entry.get("pendingMax").getAsInt();
				state.lookChanged = entry.get("lookChanged").getAsBoolean();
				state.mayStall = entry.get("mayStall").getAsBoolean();
				cells.put(entry.get("cell").getAsInt(), state);
			}
		}
		if (json.has("emptyFor")) {
			json.getAsJsonObject("emptyFor").entrySet().forEach(entry -> emptyFor.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsInt()));
		}
	}
}
