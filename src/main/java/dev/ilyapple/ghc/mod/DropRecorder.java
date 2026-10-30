package dev.ilyapple.ghc.mod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import dev.ilyapple.ghc.core.LookKeys;
import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.drops.DropBook;
import dev.ilyapple.ghc.core.drops.DropMatcher;
import dev.ilyapple.ghc.core.drops.Items;
import dev.ilyapple.ghc.core.drops.LootTable;
import dev.ilyapple.ghc.core.flow.Census;

/**
 * Records every greenhouse harvest and what dropped from it, to check drop rates (Fermento and Helianthus first).
 * After GreenEssentials' harvest tracker: a left click on a plant is only a harvest once the plant is seen to break
 * (its look changes within {@link #CONFIRM_TICKS}); merely clicking counts nothing. Drops come from three places:
 * <ul>
 * <li>"RARE CROP! Fermento (+97)" chat lines, the only source for {@link Items#RARE} items;</li>
 * <li>the inventory, in base units, while no screen is open (moving items in a menu isn't a drop);</li>
 * <li>[Sacks] reports, from their hover.</li>
 * </ul>
 * {@link DropMatcher} puts each one down to its harvest. The all-time book is saved to
 * {@code config/greenhouse-companion/drops/drops.json}; every harvest and drop also goes, one JSON line each, to
 * {@code drops/log-<date>.jsonl}, so the numbers can be checked by hand.
 */
public final class DropRecorder {
	private static final int CONFIRM_TICKS = 30;
	private static final long PEST_QUIET_MS = 3_000;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Pattern PEST = Pattern.compile("^You received [\\d,]+x .+ for killing an? .+!$");

	/** A click on a plant, waiting to see the plant break. */
	private record Click(ModState.Site site, BlockPos corner, Census.Plant plant, int cell, String signature, long tick) {}

	private final Settings settings;
	private final Path dir;
	private final DropBook session = new DropBook(System.currentTimeMillis());
	private final DropBook allTime;
	private final LootTable loot;
	private final DropMatcher matcher;
	private final List<Click> clicks = new ArrayList<>();
	/** When each plant (site key + cells) was last seen to break. */
	private final Map<String, Long> recentlyBroken = new HashMap<>();
	private @Nullable Map<String, Long> lastInventory;
	private long tick;
	private long lastPestMs;
	private boolean dirty;
	private long lastSaveMs = System.currentTimeMillis();
	private String lastSaved = "";

	DropRecorder(Settings settings, Path configDir) {
		this.settings = settings;
		this.dir = configDir.resolve("greenhouse-companion").resolve("drops");
		this.loot = loadLoot();
		DropBook saved = load(dir.resolve("drops.json"));
		// Rebuild old attribution from the read-only logs, including common mutations' armor rare crops.
		this.allTime = saved != null ? saved : rebuild(dir, loot);
		this.matcher = new DropMatcher(List.of(session, allTime), loot);
		if (saved == null) save();
	}

	private static LootTable loadLoot() {
		try (var in = DropRecorder.class.getResourceAsStream(dev.ilyapple.ghc.core.Plants.RESOURCE)) {
			if (in != null) return LootTable.of(JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (IOException | RuntimeException e) {
			GreenhouseCompanion.LOGGER.warn("[GHC] Could not read the loot tables", e);
		}
		return new LootTable(Map.of());
	}

	/**
	 * The all-time book from every harvest and drop in {@code dir}'s logs, matched again under today's rules (breaks that
	 * yielded nothing and placed items aren't counted, drops are split by loot table, Ethereal Vine only from chat).
	 */
	static DropBook rebuild(Path dir, LootTable loot) {
		List<JsonObject> events = new ArrayList<>();
		try (var files = Files.list(dir)) {
			for (Path file : files.filter(path -> path.getFileName().toString().matches("log-.*\\.jsonl")).sorted().toList()) {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					if (!line.isBlank()) events.add(JsonParser.parseString(line).getAsJsonObject());
				}
			}
		} catch (IOException | RuntimeException e) {
			GreenhouseCompanion.LOGGER.warn("[GHC] Could not read the drop logs in {}", dir, e);
		}
		events.sort(java.util.Comparator.comparingLong(DropRecorder::arrived));
		DropBook book = new DropBook(events.isEmpty() ? System.currentTimeMillis() : arrived(events.getFirst()));
		DropMatcher replay = new DropMatcher(List.of(book), loot);
		long last = 0;
		for (JsonObject event : events) {
			long t = arrived(event);
			last = t;
			// Include all events in a client tick before settling its drops, just like the live recorder.
			replay.tick(t / 50 - 1, t - 50);
			String type = event.get("type").getAsString();
			if (type.equals("reset") && "allTime".equals(string(event, "book"))) {
				book.clear(t);
				replay = new DropMatcher(List.of(book), loot);
			}
			if (type.equals("harvest") && !"placed".equals(string(event, "origin"))) replay.harvest(new DropMatcher.Harvest(string(event, "plant"), t / 50, t));
			if (!type.equals("drop")) continue;
			DropMatcher.Source source = DropMatcher.Source.valueOf(string(event, "source").toUpperCase(java.util.Locale.ROOT));
			String item = string(event, "item");
			if (source != DropMatcher.Source.CHAT && Items.RARE.contains(item)) continue;
			replay.drop(new DropMatcher.Drop(item, event.get("count").getAsLong(), source, t / 50, t, true));
		}
		replay.tick(last / 50 + 10_000, last + DropMatcher.COUNT_WITHIN_MS + 1);
		return book;
	}

	/** Drop logs carry settlement time; replay in arrival order so later harvests remain eligible. */
	private static long arrived(JsonObject event) {
		long t = event.get("t").getAsLong();
		if (!"drop".equals(string(event, "type")) || "sacks".equals(string(event, "source"))) return t;
		long lag = event.has("lagTicks") ? event.get("lagTicks").getAsLong() : DropMatcher.OLD_SETTLE_TICKS;
		return t - lag * 50L;
	}

	private static String string(JsonObject json, String member) {
		return json.has(member) && !json.get(member).isJsonNull() ? json.get(member).getAsString() : "";
	}

	public DropBook session() {
		return session;
	}

	public DropBook allTime() {
		return allTime;
	}

	public Path folder() {
		return dir;
	}

	/** Clears one book (the all-time one is saved at once). */
	public void reset(boolean everything) {
		long now = System.currentTimeMillis();
		if (everything) {
			allTime.clear(now);
			dirty = true;
			save();
		} else {
			session.clear(now);
		}
		log("reset", json -> json.addProperty("book", everything ? "allTime" : "session"));
	}

	// Clicks

	/** A left click that went through (not blocked) on {@code pos}. */
	public void clickedBlock(BlockPos pos) {
		BlockPos corner = GreenhouseCompanion.watcherCorner();
		if (corner != null) clicked(corner, GardenGrid.cellOf(corner, pos));
	}

	public void clickedEntity(Entity entity) {
		BlockPos corner = GreenhouseCompanion.watcherCorner();
		if (corner != null) clicked(corner, GardenGrid.plantCellOf(entity.level(), corner, entity));
	}

	private void clicked(BlockPos corner, int cell) {
		if (!settings.recordDrops || cell < 0) return;
		ModState.Site site = GreenhouseCompanion.currentSite();
		Minecraft client = Minecraft.getInstance();
		if (site == null || client.level == null) return;
		Census.Plant plant = plantAt(site, cell);
		if (plant == null || plant.dead() || plant.id().equals("fire")) return;
		// Holding the button sends a click every tick: one wait per plant, and none for a plant that just broke (the
		// tracker still lists it for a scan or two)
		for (Click click : clicks) if (click.site() == site && click.plant().cells().equals(plant.cells())) return;
		Long broke = recentlyBroken.get(site.key() + plant.cells());
		if (broke != null && tick - broke < CONFIRM_TICKS + 20) return;
		clicks.add(new Click(site, corner, plant, cell, signature(client.level, corner, plant.cells()), tick));
	}

	private static Census.@Nullable Plant plantAt(ModState.Site site, int cell) {
		for (Census.Plant plant : site.greenhouse().context().census().plants()) if (plant.cells().contains(cell)) return plant;
		return null;
	}

	/** Every cell's raw look across the plant's footprint: it changes when the plant breaks. */
	private static String signature(Level level, BlockPos corner, List<Integer> cells) {
		Map<Integer, List<Entity>> byCell = new HashMap<>();
		for (int cell : cells) byCell.put(cell, new ArrayList<>());
		for (Entity entity : level.getEntities((Entity) null, GardenGrid.scanBox(corner), Fingerprints::isModelEntity)) {
			List<Entity> list = byCell.get(GardenGrid.plantCellOf(level, corner, entity));
			if (list != null) list.add(entity);
		}
		StringBuilder signature = new StringBuilder();
		for (int cell : cells) signature.append(LookKeys.keysOf(Fingerprints.fingerprint(level, corner, cell, byCell.get(cell))).raw()).append('|');
		return signature.toString();
	}

	// Chat

	void onChat(Component message) {
		if (!settings.recordDrops) return;
		String text = ChatFormatting.stripFormatting(message.getString());
		if (text == null) return;
		text = text.strip();
		if (PEST.matcher(text).matches()) {
			lastPestMs = System.currentTimeMillis();
			return;
		}
		Map.Entry<String, Long> rare = Items.rareCrop(text);
		if (rare != null) {
			String chat = text;
			DebugStream.emit("rareCrop", json -> {
				json.addProperty("message", chat);
				json.addProperty("item", rare.getKey());
				json.addProperty("count", rare.getValue());
				json.addProperty("tick", tick);
				DebugStream.site(json, GreenhouseCompanion.currentSite());
			});
			drop(rare.getKey(), rare.getValue(), DropMatcher.Source.CHAT);
			return;
		}
		if (!text.startsWith("[Sacks]")) return;
		Set<String> hovers = new LinkedHashSet<>();
		collectHovers(message, hovers);
		Items.sackGains(List.copyOf(hovers)).forEach((item, count) -> {
			if (!Items.RARE.contains(item)) drop(item, count, DropMatcher.Source.SACKS);
		});
	}

	private static void collectHovers(Component component, Set<String> into) {
		if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText show) {
			String text = ChatFormatting.stripFormatting(show.value().getString());
			if (text != null) into.add(text);
		}
		for (Component sibling : component.getSiblings()) collectHovers(sibling, into);
	}

	private void drop(String item, long count, DropMatcher.Source source) {
		matcher.drop(new DropMatcher.Drop(item, count, source, tick, System.currentTimeMillis(), GreenhouseCompanion.currentSite() != null));
	}

	// Ticks

	void tick(Minecraft client) {
		tick++;
		long now = System.currentTimeMillis();
		if (!settings.recordDrops || client.player == null || client.level == null) {
			clicks.clear();
			lastInventory = null;
			return;
		}
		confirmClicks(client.level);
		readInventory(client, now);
		for (DropMatcher.Outcome outcome : matcher.tick(tick, now)) {
			if (outcome.verdict().equals("ignored")) continue;
			dirty = true;
			log("drop", json -> {
				json.addProperty("item", outcome.drop().item());
				json.addProperty("count", outcome.drop().count());
				json.addProperty("source", outcome.drop().source().name().toLowerCase(java.util.Locale.ROOT));
				if (outcome.drop().source() != DropMatcher.Source.SACKS) json.addProperty("lagTicks", tick - outcome.drop().tick());
				json.addProperty("verdict", outcome.verdict());
				if (outcome.shares().size() == 1) json.addProperty("plant", outcome.shares().keySet().iterator().next());
				JsonObject shares = new JsonObject();
				outcome.shares().forEach(shares::addProperty);
				json.add("shares", shares);
				JsonArray candidates = new JsonArray();
				outcome.candidates().forEach(candidates::add);
				json.add("candidates", candidates);
			});
		}
		for (DropMatcher.Harvest harvest : matcher.yieldedNothing()) {
			log("nothing", json -> json.addProperty("plant", harvest.plant()));
		}
		if (dirty && now - lastSaveMs > 30_000) save();
	}

	private void confirmClicks(Level level) {
		for (Iterator<Click> it = clicks.iterator(); it.hasNext();) {
			Click click = it.next();
			if (tick - click.tick() > CONFIRM_TICKS) {
				it.remove();
				continue;
			}
			if (signature(level, click.corner(), click.plant().cells()).equals(click.signature())) continue;
			it.remove();
			Census.Plant plant = click.plant();
			recentlyBroken.put(click.site().key() + plant.cells(), tick);
			recentlyBroken.values().removeIf(at -> tick - at > 200);
			// Taking back a placed item is never a harvest; anything else counts once something drops from it
			boolean placed = plant.kind().mutation() && plant.origin() == StageTracker.Origin.PLACED;
			if (!placed) matcher.harvest(new DropMatcher.Harvest(plant.id(), tick, System.currentTimeMillis()));
			log("harvest", json -> {
				json.addProperty("plant", plant.id());
				json.addProperty("origin", plant.origin().name().toLowerCase(java.util.Locale.ROOT));
				if (!plant.stages().isEmpty()) {
					json.addProperty("stageLo", plant.stages().lo());
					json.addProperty("stageHi", plant.stages().hi());
				}
				json.addProperty("site", click.site().name());
				json.addProperty("cell", click.cell());
				json.addProperty("clickTicksAgo", tick - click.tick());
			});
		}
	}

	/** Inventory gains in base units while no screen is open, not just after a pest kill; rare drops come from chat. */
	private void readInventory(Minecraft client, long now) {
		if (dev.ilyapple.ghc.mod.compat.Mc.screen() != null || now - lastPestMs < PEST_QUIET_MS || !GreenhouseCompanion.location().inGarden()) {
			lastInventory = null;
			return;
		}
		Map<String, Long> raw = new HashMap<>();
		for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
			String id = skyblockId(stack);
			if (id != null) raw.merge(id, (long) stack.getCount(), Long::sum);
		}
		Map<String, Long> counts = Items.inBaseUnits(raw);
		Map<String, Long> before = lastInventory;
		lastInventory = counts;
		if (before == null) return;
		counts.forEach((item, count) -> {
			long gained = count - before.getOrDefault(item, 0L);
			if (gained > 0 && !Items.RARE.contains(item)) drop(item, gained, DropMatcher.Source.INVENTORY);
		});
	}

	/** The SkyBlock item id ({@code custom_data.id}), or {@code null} for vanilla items. */
	static @Nullable String skyblockId(ItemStack stack) {
		if (stack.isEmpty()) return null;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		var tag = data.copyTag();
		String id = tag.getString("id").orElse(null);
		if (id == null) id = tag.getCompound("ExtraAttributes").flatMap(extra -> extra.getString("id")).orElse(null);
		return id == null || id.isEmpty() ? null : id;
	}

	// Files

	private interface Fields {
		void add(JsonObject json);
	}

	private void log(String type, Fields fields) {
		JsonObject json = new JsonObject();
		json.addProperty("t", System.currentTimeMillis());
		json.addProperty("type", type);
		fields.add(json);
		DebugStream.emit("dropRecorder", event -> json.entrySet().forEach(entry -> event.add(entry.getKey().equals("type") ? "event" : entry.getKey(), entry.getValue())));
		try {
			Files.createDirectories(dir);
			try (BufferedWriter out = Files.newBufferedWriter(dir.resolve("log-" + LocalDate.now() + ".jsonl"), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				out.write(json.toString());
				out.newLine();
			}
		} catch (IOException e) {
			GreenhouseCompanion.LOGGER.warn("[GHC] Could not write the drop log", e);
		}
	}

	/** Version 5 replays armor rare crops for every mutation, regardless of its ordinary crop drops or rarity. */
	private static @Nullable DropBook load(Path file) {
		try {
			if (Files.exists(file)) {
				JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				int version = root.has("version") ? root.get("version").getAsInt() : 1;
				if (root.has("allTime")) {
					if (version >= 5) return DropBook.fromJson(root.getAsJsonObject("allTime"));
					// A player may have removed the raw logs. An upgrade must not replace their totals with an empty replay.
					if (!hasReplayLogs(file.getParent())) {
						GreenhouseCompanion.LOGGER.warn("[GHC] Keeping saved drop totals because no replay logs are available in {}", file.getParent());
						return DropBook.fromJson(root.getAsJsonObject("allTime"));
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not read {}, rebuilding the drop book from the logs", file, e);
		}
		return null;
	}

	private static boolean hasReplayLogs(Path dir) {
		try (var files = Files.list(dir)) {
			for (Path file : files.filter(path -> path.getFileName().toString().matches("log-.*\\.jsonl")).toList()) {
				if (Files.isRegularFile(file) && Files.size(file) > 0) return true;
			}
		} catch (IOException e) {
			GreenhouseCompanion.LOGGER.warn("[GHC] Could not check the replay logs in {}", dir, e);
		}
		return false;
	}

	public void save() {
		dirty = false;
		lastSaveMs = System.currentTimeMillis();
		JsonObject root = new JsonObject();
		root.addProperty("version", 5);
		root.add("allTime", allTime.toJson());
		String text = GSON.toJson(root);
		if (text.equals(lastSaved)) return;
		try {
			Files.createDirectories(dir);
			Path file = dir.resolve("drops.json");
			Path temp = file.resolveSibling("drops.json.tmp");
			Files.writeString(temp, text, StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			lastSaved = text;
		} catch (IOException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not save the drop book", e);
		}
	}
}
