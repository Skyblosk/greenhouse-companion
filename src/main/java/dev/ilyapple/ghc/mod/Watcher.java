package dev.ilyapple.ghc.mod;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import dev.ilyapple.ghc.core.GrowthClock;
import dev.ilyapple.ghc.core.LookKeys;
import dev.ilyapple.ghc.core.StageTracker;

/**
 * Watches the greenhouse the player stands in. Every {@link #SCAN_TICKS} ticks it fingerprints all 100 cells; a
 * new look is passed to the tracker once it has held for two scans, so stands that load one by one don't count as
 * looks. Growth (a plant changing look, or one appearing that nobody placed) tells the clock a tick happened, and
 * the clock's ticks go to every greenhouse on the server before the new look is applied.
 *
 * <p>For {@link #WARMUP_TICKS} after arriving nothing is passed on, since entities are still loading; then every
 * plant is read at once. Cells are taken to be empty only after that.
 *
 * <p>A plot only counts as a greenhouse once a Carpenter NPC is seen standing in it, so
 * ordinary farm plots are never scanned. Each scan also feeds the Jellybean view and the world labels.
 */
final class Watcher {
	private static final int SCAN_TICKS = 10;
	private static final int WARMUP_TICKS = 60;
	private static final int CARPENTER_CHECK_TICKS = 40;
	private static final int CELLS = GardenGrid.SIZE * GardenGrid.SIZE;

	private final ModState state;
	private final Location location;
	private final JellybeanView jellybeans;
	private final Settings settings;
	private long tick;
	private boolean inGarden;
	/** Every cell's look has been applied to the tracker since arriving (or enough scans have passed). */
	private boolean ready;
	private int scans;
	/** A look must hold this many scans before it counts, so this many scans read every steady cell. */
	private static final int READY_SCANS = 6;
	private @Nullable Level lastLevel;
	private @Nullable BlockPos corner;
	/** The server, plus the SkyBlock profile's id once Hypixel has named it ({@link ModState#scope}). */
	private @Nullable String scope;
	private int warmupLeft;
	private final String[] applied = new String[CELLS];
	/** Every cell's fingerprint from the last scan, for the debug stream's snapshot. */
	private final JsonObject[] latest = new JsonObject[CELLS];
	private final String[] pending = new String[CELLS];
	private final JsonObject[] pendingPrint = new JsonObject[CELLS];

	Watcher(ModState state, Location location, JellybeanView jellybeans, Settings settings) {
		this.state = state;
		this.location = location;
		this.jellybeans = jellybeans;
		this.settings = settings;
	}

	/** The greenhouse being watched, or {@code null} when the player isn't in one. */
	ModState.@Nullable Site current() {
		if (corner == null || scope == null) return null;
		String key = GardenGrid.key(scope, corner);
		return state.isGreenhouse(key) ? state.site(scope, corner) : null;
	}

	/**
	 * Whether every cell's look has reached the tracker since arriving, so empty cells really are empty and the
	 * greenhouse can be identified. A look only counts once it held for two scans, so the first scan alone isn't enough.
	 */
	boolean ready() {
		return ready;
	}

	@Nullable BlockPos corner() {
		return corner;
	}

	JsonObject[] latest() {
		return latest;
	}

	boolean inGarden() {
		return inGarden;
	}

	void tick(Minecraft client) {
		tick++;
		if (client.level != lastLevel) {
			// A new world: every entity unloads and loads again, which is not growth
			lastLevel = client.level;
			reset(null);
		}
		if (client.level == null || client.player == null) return;
		location.tick(client);
		boolean wasInGarden = inGarden;
		inGarden = location.inGarden();
		if (inGarden != wasInGarden) DebugStream.emit("location", event -> {
			event.addProperty("inGarden", inGarden);
			event.addProperty("source", location.source());
		});
		if (!inGarden) {
			if (corner != null) reset(null);
			return;
		}

		String currentScope = state.scope(GardenGrid.server(client));
		BlockPos newCorner = GardenGrid.cornerFor(client.player.blockPosition());
		if (!newCorner.equals(corner) || !currentScope.equals(scope)) {
			scope = currentScope;
			reset(newCorner);
		}
		if (corner == null || !GardenGrid.near(client.player, corner)) {
			warmupLeft = WARMUP_TICKS;
			return;
		}
		String key = GardenGrid.key(scope, corner);
		if (!state.isGreenhouse(key)) {
			if (tick % CARPENTER_CHECK_TICKS == 0 && GardenGrid.hasCarpenter(client.level, corner)) {
				state.markGreenhouse(key);
				BlockPos confirmed = corner;
				DebugStream.emit("confirmed", event -> {
					event.addProperty("plot", GardenGrid.plotNumber(confirmed));
					event.add("corner", DebugStream.corner(confirmed));
				});
			}
			return;
		}
		if (tick % SCAN_TICKS != 0) return;
		if (warmupLeft > 0) {
			warmupLeft -= SCAN_TICKS;
			if (warmupLeft > 0) return;
		}
		scan(client.level, corner);
	}

	private void reset(@Nullable BlockPos newCorner) {
		if (newCorner != null && !newCorner.equals(corner) || newCorner == null && corner != null) {
			BlockPos left = corner;
			DebugStream.emit("greenhouse", event -> {
				event.addProperty("entered", newCorner != null);
				BlockPos at = newCorner != null ? newCorner : left;
				event.addProperty("plot", GardenGrid.plotNumber(at));
				event.add("corner", DebugStream.corner(at));
				event.addProperty("confirmed", scope != null && state.isGreenhouse(GardenGrid.key(scope, at)));
			});
		}
		corner = newCorner;
		java.util.Arrays.fill(latest, null);
		dev.ilyapple.ghc.mod.render.Highlight.clear();
		dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
		warmupLeft = WARMUP_TICKS;
		ready = false;
		scans = 0;
		jellybeans.clear(Minecraft.getInstance().level);
		WorldLabels.update(null, null, jellybeans, settings);
		for (int cell = 0; cell < CELLS; cell++) {
			applied[cell] = null;
			pending[cell] = null;
			pendingPrint[cell] = null;
		}
	}

	@SuppressWarnings("unchecked")
	private void scan(Level level, BlockPos corner) {
		List<Entity>[] buckets = new List[CELLS];
		for (int cell = 0; cell < CELLS; cell++) buckets[cell] = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, GardenGrid.scanBox(corner), Fingerprints::isModelEntity)) {
			// A young Jellybean's held bean stands over the next cell but is part of its own
			int cell = GardenGrid.plantCellOf(level, corner, entity);
			if (cell >= 0) buckets[cell].add(entity);
		}

		ModState.Site site = state.site(scope, corner);
		long now = System.currentTimeMillis();
		state.seen(site, now);
		jellybeans.begin(corner);
		for (int cell = 0; cell < CELLS; cell++) {
			JsonObject print = Fingerprints.fingerprint(level, corner, cell, buckets[cell]);
			latest[cell] = print;
			jellybeans.cell(cell, print, buckets[cell], site.greenhouse().tracker().get(cell));
			LookKeys.Keys keys = LookKeys.keysOf(print);
			// The raw key includes holograms, so All-in Aloe's "Stage N" counts as a change
			String raw = keys.raw();
			if (raw.equals(applied[cell])) {
				pending[cell] = null;
				continue;
			}
			if (!raw.equals(pending[cell])) {
				pending[cell] = raw;
				pendingPrint[cell] = print;
				continue;
			}
			String previous = applied[cell];
			applied[cell] = raw;
			pending[cell] = null;
			apply(site, cell, print, keys, previous, now);
		}
		scans++;
		int read = 0;
		for (int cell = 0; cell < CELLS; cell++) if (applied[cell] != null) read++;
		if (!ready && (read == CELLS || scans >= READY_SCANS)) {
			ready = true;
			int cellsRead = read;
			DebugStream.emit("scan", event -> {
				DebugStream.site(event, site);
				event.addProperty("cellsRead", cellsRead);
				event.addProperty("scans", scans);
				event.addProperty("plants", site.greenhouse().tracker().all().size());
				int beans = 0;
				for (int cell = 0; cell < CELLS; cell++) if (jellybeans.bean(cell) != null) beans++;
				event.addProperty("jellybeans", beans);
			});
		}
		jellybeans.end(level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel ? clientLevel : null, settings);
		dev.ilyapple.ghc.mod.render.Highlight.update(level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel ? clientLevel : null,
				corner, site, buckets);
		// Only once every cell has been read: before that, empty-looking cells may still be loading
		if (ready) dev.ilyapple.ghc.mod.render.LayoutOverlay.update(level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel ? clientLevel : null,
				corner, site, buckets);
		WorldLabels.update(corner, site, jellybeans, settings);
	}

	/** {@code previous} is the raw key last applied on this visit, {@code null} on the first look after arriving. */
	private void apply(ModState.Site site, int cell, JsonObject print, LookKeys.Keys keys, @Nullable String previous, long now) {
		StageTracker tracker = site.greenhouse().tracker();
		StageTracker.Tracked before = tracker.get(cell);
		// Only a change seen while watching shows when a tick happened; the first look after arriving doesn't
		boolean watched = previous != null;
		boolean grew = watched && before != null && !keys.isEmpty() && keys.plant() != null
				&& before.plant() == state.plants().byName(keys.plant()) && !keys.key().equals(before.lookKey());
		boolean spawned = watched && LookKeys.EMPTY_KEY.equals(previous) && !keys.isEmpty() && keys.plant() != null
				&& !GreenhouseCompanion.usedRecently(site, cell, now);
		if (grew || spawned) {
			GrowthClock clock = state.clock(site.server());
			int ticks = clock.onGrowthSeen(now);
			if (ticks > 0) GreenhouseCompanion.applyTicks(site.server(), ticks);
		}
		StageTracker.Update update = site.greenhouse().observe(cell, print, now);
		state.markDirty();
		boolean worthTelling = update.appeared() || update.removed() || update.lookChanged() || update.stalled() || update.contradiction()
				|| update.after() != null && (update.before() == null || !update.after().stages().equals(update.before().stages()) || !update.after().lookKnown());
		if (worthTelling) DebugStream.emit("look", event -> {
			DebugStream.site(event, site);
			event.addProperty("cell", cell);
			event.addProperty("x", GardenGrid.cellX(cell));
			event.addProperty("z", GardenGrid.cellZ(cell));
			StageTracker.Tracked after = update.after();
			if (after == null) {
				event.addProperty("plant", keys.isEmpty() ? null : keys.plant());
				event.addProperty("removed", update.removed());
				return;
			}
			event.addProperty("plant", after.plant().id());
			event.addProperty("look", LookKeys.shortId(keys.key()));
			event.addProperty("source", DebugStream.source(after.plant(), print, keys));
			DebugStream.stages(event, after.stages());
			event.addProperty("origin", after.origin().name());
			event.addProperty("exact", after.exact());
			event.addProperty("appeared", update.appeared());
			event.addProperty("lookChanged", update.lookChanged());
			event.addProperty("grew", grew || spawned);
			if (update.stalled()) event.addProperty("stalled", true);
			if (update.contradiction()) event.addProperty("contradiction", true);
			// The raw look whenever no source knows it, so it can be added to the model book or calibration
			if (!after.lookKnown()) event.add("fingerprint", print);
		});
	}
}
