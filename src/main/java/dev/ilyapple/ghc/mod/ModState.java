package dev.ilyapple.ghc.mod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;

import dev.ilyapple.ghc.core.Greenhouse;
import dev.ilyapple.ghc.core.GrowthClock;
import dev.ilyapple.ghc.core.Inventory;
import dev.ilyapple.ghc.core.LookTable;
import dev.ilyapple.ghc.core.ModelBook;
import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.flow.FlowCheck;
import dev.ilyapple.ghc.core.flow.Flows;

/**
 * Everything the mod remembers, under {@code config/greenhouse-companion/}:
 * <ul>
 * <li>{@code flows/}: every imported flows file as its own file (the simulator's export, or layout links), written the
 *     moment it is imported, renamed or edited; files dropped into the folder by hand are picked up at start;</li>
 * <li>{@code state.json}: the profiles (each a set of greenhouses following one flows file, all tracked at once), which
 *     flow plot each greenhouse follows, every greenhouse's tracked stages and flow position, each server's growth
 *     clock, and the inventory.</li>
 * </ul>
 * Greenhouses are kept apart per SkyBlock profile: a greenhouse's key is {@code server#profileId|x,z} once Hypixel has
 * said which profile you're on ("Profile ID: …" in chat), so the same plot on two profiles is two greenhouses.
 */
public final class ModState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/**
	 * A greenhouse: {@code scope} is the server, plus {@code #} and the SkyBlock profile's id once known.
	 */
	public record Site(String key, String scope, BlockPos corner, Greenhouse greenhouse) {
		public String name() {
			return "Plot " + GardenGrid.plotName(corner) + (server().contains("alpha") ? " · alpha" : "");
		}

		/** The server it is on (its growth clock is that server's). */
		public String server() {
			int hash = scope.indexOf('#');
			return hash < 0 ? scope : scope.substring(0, hash);
		}

		public int plotNumber() {
			return GardenGrid.plotNumber(corner);
		}
	}

	/** One imported flows file: what was pasted, under a name the player can change, kept in {@code flows/<file>}. */
	public record FlowFile(String name, String text, long importedMs, String file) {}

	/**
	 * Which flow plot a greenhouse follows; {@code auto} when the mod picked it from what grows there, and the
	 * quarter turns it is planted at relative to the flow's layouts.
	 */
	public record Assignment(int plotId, boolean auto, int rotation) {}

	/**
	 * A set of greenhouses that follow one flows file. New greenhouses join the profile that tracks the SkyBlock
	 * profile they're on ({@link #scopes}), else the one shown in the window.
	 */
	public static final class Profile {
		private final String id;
		private String name;
		private @Nullable String flowName;
		private @Nullable Flows flows;
		private List<FlowCheck.Problem> problems = List.of();
		private final Set<String> scopes = new LinkedHashSet<>();

		Profile(String id, String name) {
			this.id = id;
			this.name = name;
		}

		public String id() {
			return id;
		}

		public String name() {
			return name;
		}

		/** The library file it follows, or {@code null}. */
		public @Nullable String flowName() {
			return flowName;
		}

		public @Nullable Flows flows() {
			return flows;
		}

		public List<FlowCheck.Problem> problems() {
			return problems;
		}

		/** The SkyBlock profiles ({@code server#profileId}) whose new greenhouses join this profile. */
		public Set<String> scopes() {
			return Set.copyOf(scopes);
		}
	}

	private final Path file;
	private final Path flowDir;
	private final Plants plants;
	private final LookTable table;
	private final String tableSource;
	private final Inventory inventory;
	private final Map<String, Site> sites = new LinkedHashMap<>();
	private final Map<String, GrowthClock> clocks = new LinkedHashMap<>();
	private final Map<String, Assignment> assignments = new LinkedHashMap<>();
	private final Set<String> greenhouses = new LinkedHashSet<>();
	private final Map<String, JsonObject> pendingRunners = new LinkedHashMap<>();
	private final Map<String, JsonObject> pendingTrackers = new LinkedHashMap<>();
	private final List<FlowFile> library = new ArrayList<>();
	private final Map<String, String> notes = new HashMap<>();
	private final List<Profile> profiles = new ArrayList<>();
	/** Greenhouse key → profile id. */
	private final Map<String, String> siteProfiles = new LinkedHashMap<>();
	private final Map<String, Long> lastSeen = new HashMap<>();
	/** The SkyBlock profile id Hypixel last named on each server this session (not saved). */
	private final Map<String, String> skyblockProfile = new HashMap<>();
	private String viewed = "";
	private boolean dirty;

	private ModState(Path file, Path flowDir, Plants plants, LookTable table, String tableSource, Inventory inventory) {
		this.file = file;
		this.flowDir = flowDir;
		this.plants = plants;
		this.table = table;
		this.tableSource = tableSource;
		this.inventory = inventory;
	}

	/**
	 * Loads the saved state. Looks come from the stage recorder's {@code calibration.json} when that mod has one in
	 * this instance (it keeps growing as you read plants), else from the copy bundled with this mod, plus the
	 * bundled model book for stages neither has read.
	 */
	public static ModState load(Path configDir) {
		Plants plants = Plants.defaults();
		ModelBook models = ModelBook.bundled();
		LookTable table;
		String source;
		Path recorder = configDir.resolve("greenhouse-stage-recorder").resolve("calibration.json");
		try {
			if (Files.exists(recorder)) {
				table = LookTable.fromCalibration(plants, recorder);
				source = "stage recorder's calibration.json";
			} else {
				Path bundled = Files.createTempFile("ghc-calibration", ".json");
				try (InputStream in = ModState.class.getResourceAsStream("/assets/greenhouse-companion/calibration.json")) {
					if (in == null) throw new IOException("bundled calibration missing");
					Files.copy(in, bundled, StandardCopyOption.REPLACE_EXISTING);
				}
				table = LookTable.fromCalibration(plants, bundled);
				Files.deleteIfExists(bundled);
				source = "bundled calibration (26 Oct 2026)";
			}
		} catch (IOException | RuntimeException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not read a calibration, stages will only come from models and the clock", e);
			table = LookTable.of(plants, List.of());
			source = "none";
		}
		table = table.withModels(models);
		return load(configDir.resolve("greenhouse-companion"), plants, table, source);
	}

	/** Loads from {@code dir} (state.json and flows/) with the given looks; for tests too. */
	static ModState load(Path dir, Plants plants, LookTable table, String source) {
		Path file = dir.resolve("state.json");
		JsonObject root = new JsonObject();
		try {
			if (Files.exists(file)) root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not read {}, starting over", file, e);
		}
		Inventory inventory = root.has("inventory") ? Inventory.fromJson(root.getAsJsonObject("inventory")) : new Inventory();
		ModState state = new ModState(file, dir.resolve("flows"), plants, table, source, inventory);
		state.loadLibrary(root);
		if (root.has("assignments")) {
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("assignments").entrySet()) {
				JsonObject json = entry.getValue().getAsJsonObject();
				state.assignments.put(entry.getKey(), new Assignment(json.get("plot").getAsInt(), json.has("auto") && json.get("auto").getAsBoolean(),
						json.has("rotation") ? json.get("rotation").getAsInt() : 0));
			}
		}
		if (root.has("greenhouses")) root.getAsJsonArray("greenhouses").forEach(element -> state.greenhouses.add(element.getAsString()));
		if (root.has("runners")) root.getAsJsonObject("runners").entrySet().forEach(e -> state.pendingRunners.put(e.getKey(), e.getValue().getAsJsonObject()));
		if (root.has("trackers")) root.getAsJsonObject("trackers").entrySet().forEach(e -> state.pendingTrackers.put(e.getKey(), e.getValue().getAsJsonObject()));
		if (root.has("clocks")) root.getAsJsonObject("clocks").entrySet().forEach(e -> state.clocks.put(e.getKey(), GrowthClock.fromJson(e.getValue().getAsJsonObject())));
		if (root.has("lastSeen")) root.getAsJsonObject("lastSeen").entrySet().forEach(e -> state.lastSeen.put(e.getKey(), e.getValue().getAsLong()));
		if (root.has("siteProfiles")) root.getAsJsonObject("siteProfiles").entrySet().forEach(e -> state.siteProfiles.put(e.getKey(), e.getValue().getAsString()));
		if (root.has("profiles")) {
			for (JsonElement element : root.getAsJsonArray("profiles")) {
				JsonObject json = element.getAsJsonObject();
				Profile profile = new Profile(json.get("id").getAsString(), json.get("name").getAsString());
				if (json.has("flow") && !json.get("flow").isJsonNull()) profile.flowName = json.get("flow").getAsString();
				if (json.has("scopes")) json.getAsJsonArray("scopes").forEach(scope -> profile.scopes.add(scope.getAsString()));
				state.profiles.add(profile);
			}
		}
		if (state.profiles.isEmpty()) {
			// Before profiles (version 2): one profile with the flows that were in use
			Profile main = new Profile("p1", "Main");
			int active = root.has("active") ? root.get("active").getAsInt() : -1;
			if (active >= 0 && active < state.library.size()) main.flowName = state.library.get(active).name();
			state.profiles.add(main);
			state.dirty = true;
		}
		// After the profiles, so one that follows an earlier bundled flow follows its new name
		state.upgradeBundledFlow();
		state.viewed = root.has("viewedProfile") ? root.get("viewedProfile").getAsString() : state.profiles.getFirst().id;
		if (state.profile(state.viewed) == null) state.viewed = state.profiles.getFirst().id;
		for (Profile profile : state.profiles) state.refresh(profile, false);
		if (state.library.isEmpty()) state.preload();
		state.restoreSites();
		return state;
	}

	/**
	 * The library: the index in state.json (names, files, order) and the text from each file in {@code flows/}.
	 * Entries saved before files existed (their text in state.json) are written out as files. Files in the folder the
	 * index doesn't know (dropped in by hand) are added at the end, named after the file.
	 */
	private void loadLibrary(JsonObject root) {
		Set<String> known = new HashSet<>();
		if (root.has("library")) {
			for (JsonElement element : root.getAsJsonArray("library")) {
				JsonObject json = element.getAsJsonObject();
				String name = json.get("name").getAsString();
				long imported = json.has("importedMs") ? json.get("importedMs").getAsLong() : 0;
				String fileName = json.has("file") ? json.get("file").getAsString() : null;
				// Only a plain name in the flows folder: a state.json from elsewhere can't point outside it
				if (fileName != null && !plainFileName(fileName)) fileName = null;
				String text = null;
				if (fileName != null) {
					try {
						text = Files.readString(flowDir.resolve(fileName), StandardCharsets.UTF_8).strip();
					} catch (IOException e) {
						GreenhouseCompanion.LOGGER.warn("[GHC] Flows file {} is gone, dropping \"{}\" from the library", fileName, name);
						if (json.has("text")) text = json.get("text").getAsString();
					}
				} else if (json.has("text")) {
					text = json.get("text").getAsString();
				}
				if (text == null) continue;
				FlowFile flowFile = new FlowFile(name, text, imported, fileName != null ? fileName : fileNameFor(name, text));
				library.add(flowFile);
				known.add(flowFile.file());
				if (fileName == null || json.has("text")) {
					writeFile(flowFile);
					dirty = true;
				}
			}
		}
		if (!Files.isDirectory(flowDir)) return;
		List<Path> extra = new ArrayList<>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(flowDir, "*.{json,txt}")) {
			for (Path path : files) if (!known.contains(path.getFileName().toString())) extra.add(path);
		} catch (IOException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not list {}", flowDir, e);
		}
		extra.sort(null);
		for (Path path : extra) {
			try {
				String text = Files.readString(path, StandardCharsets.UTF_8).strip();
				Flows.parse(text);
				String fileName = path.getFileName().toString();
				String name = uniqueName(fileName.substring(0, fileName.lastIndexOf('.')));
				library.add(new FlowFile(name, text, Files.getLastModifiedTime(path).toMillis(), fileName));
				dirty = true;
				GreenhouseCompanion.LOGGER.info("[GHC] Added {} from the flows folder", fileName);
			} catch (IOException | RuntimeException e) {
				GreenhouseCompanion.LOGGER.warn("[GHC] Skipping {}: not a flows file ({})", path.getFileName(), e.getMessage());
			}
		}
	}

	/**
	 * Brings back every saved greenhouse at start, not only the one walked into: they all keep growing (the clock's
	 * ticks, offline ones included, only reach greenhouses that exist), show on the Overview and raise swap alerts.
	 */
	private void restoreSites() {
		for (String key : List.copyOf(greenhouses)) {
			int bar = key.lastIndexOf('|');
			int comma = key.lastIndexOf(',');
			if (bar < 0 || comma < bar) continue;
			try {
				int x = Integer.parseInt(key.substring(bar + 1, comma));
				int z = Integer.parseInt(key.substring(comma + 1));
				site(key.substring(0, bar), new BlockPos(x, GardenGrid.FLOOR_Y, z));
			} catch (NumberFormatException e) {
				GreenhouseCompanion.LOGGER.warn("[GHC] Skipping saved greenhouse {}", key);
			}
		}
	}

	/** The sample flow an empty library starts with: a hybrid Shadevine flow (27 Oct 2026). */
	public static final String TEST_FLOW = "/assets/greenhouse-companion/flows/hybrid-shadevine.json";
	public static final String TEST_FLOW_NAME = "Hybrid Shadevine (27 Oct)";
	/** The flow preloaded before 27 Oct. */
	private static final String OLD_TEST_FLOW_NAME = "Jellybean + Shadevine (24 Oct)";
	/**
	 * Plot 3 (Duskbloom) step codes of earlier bundled versions: the 24 Oct flow, and the first 27 Oct hybrid flow with
	 * the redesigned Duskbloom half the user then chose not to plant. A library entry holding one is upgraded.
	 */
	private static final List<String> SUPERSEDED_CODES = List.of("Rco7CoAwEEXRDT3ENFr72cib", "XYsxCoAwEAQ_tIUp1Pr0JXe5",
			"XcoxDoAgEETRC02hjdYCFxlW", "TcoxDoAwDEPRC3mAAZgNJ0kaQ");

	/**
	 * The user's hybrid Shadevine flow (27 Oct) replaced the 24 Oct one: the new Jellybean half for plot 3, with the
	 * Duskbloom half the user has planted. A library entry still holding an earlier bundled version is swapped for the
	 * current one in place, before the flows are used, so every greenhouse keeps its plot, step and turn (the steps are
	 * the same).
	 */
	private void upgradeBundledFlow() {
		for (int i = 0; i < library.size(); i++) {
			FlowFile flowFile = library.get(i);
			if (!flowFile.name().equals(OLD_TEST_FLOW_NAME) && !flowFile.name().equals(TEST_FLOW_NAME)) continue;
			if (SUPERSEDED_CODES.stream().noneMatch(flowFile.text()::contains)) continue;
			try (InputStream in = ModState.class.getResourceAsStream(TEST_FLOW)) {
				if (in == null) return;
				String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
				// Under its current name (and file name) from now on
				String fileName = flowFile.name().equals(TEST_FLOW_NAME) ? flowFile.file() : fileNameFor(TEST_FLOW_NAME, text);
				FlowFile updated = new FlowFile(TEST_FLOW_NAME, text, System.currentTimeMillis(), fileName);
				library.set(i, updated);
				writeFile(updated);
				if (!fileName.equals(flowFile.file())) Files.deleteIfExists(flowDir.resolve(flowFile.file()));
				for (Profile profile : profiles) if (flowFile.name().equals(profile.flowName)) profile.flowName = TEST_FLOW_NAME;
				dirty = true;
				GreenhouseCompanion.LOGGER.info("[GHC] Updated {} to the current hybrid Shadevine flow (27 Oct)", flowFile.name());
			} catch (IOException e) {
				GreenhouseCompanion.LOGGER.error("[GHC] Could not read the bundled flow", e);
			}
		}
	}

	/** Adds the bundled flow to an empty library as a sample; nothing follows it until a player picks it (28 Oct). */
	public void preload() {
		try (InputStream in = ModState.class.getResourceAsStream(TEST_FLOW)) {
			if (in == null) return;
			addToLibrary(new String(in.readAllBytes(), StandardCharsets.UTF_8), TEST_FLOW_NAME);
		} catch (IOException | RuntimeException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not preload the test flow", e);
		}
	}

	public Plants plants() {
		return plants;
	}

	public LookTable table() {
		return table;
	}

	public String tableSource() {
		return tableSource;
	}

	public Inventory inventory() {
		return inventory;
	}

	/** The folder every imported flows file is kept in. */
	public Path flowFolder() {
		return flowDir;
	}

	public GrowthClock clock(String server) {
		return clocks.computeIfAbsent(server, key -> new GrowthClock());
	}

	/** A short message the UI shows for a greenhouse (how it was identified, why a paste failed). */
	public @Nullable String note(Site site) {
		return notes.get(site.key());
	}

	public void setNote(Site site, @Nullable String note) {
		if (note == null) notes.remove(site.key());
		else notes.put(site.key(), note);
	}

	// Profiles

	public List<Profile> profiles() {
		return List.copyOf(profiles);
	}

	public @Nullable Profile profile(String id) {
		for (Profile profile : profiles) if (profile.id.equals(id)) return profile;
		return null;
	}

	/** The profile the window shows (the dropdown's choice); new greenhouses not tied to another profile join it. */
	public Profile viewedProfile() {
		Profile profile = profile(viewed);
		return profile != null ? profile : profiles.getFirst();
	}

	public void view(Profile profile) {
		if (!profile.id.equals(viewed)) {
			viewed = profile.id;
			dirty = true;
		}
	}

	/** The profile a greenhouse belongs to. */
	public Profile profileOf(Site site) {
		String id = siteProfiles.get(site.key());
		Profile profile = id == null ? null : profile(id);
		if (profile == null) {
			profile = profileFor(site.scope());
			siteProfiles.put(site.key(), profile.id);
			dirty = true;
		}
		return profile;
	}

	/** Where a new greenhouse on {@code scope} goes: the profile tracking that SkyBlock profile, else the one shown. */
	private Profile profileFor(String scope) {
		for (Profile profile : profiles) if (profile.scopes.contains(scope)) return profile;
		return viewedProfile();
	}

	public Profile createProfile(String name) {
		int n = profiles.size() + 1;
		while (profile("p" + n) != null) n++;
		Profile profile = new Profile("p" + n, name.isBlank() ? "Profile " + n : name.strip());
		profiles.add(profile);
		dirty = true;
		return profile;
	}

	public void renameProfile(Profile profile, String name) {
		if (name.isBlank()) return;
		profile.name = name.strip();
		dirty = true;
	}

	/** Deletes a profile and forgets its greenhouses; the last profile can't be deleted. */
	public void deleteProfile(Profile profile) {
		if (profiles.size() <= 1 || !profiles.contains(profile)) return;
		for (Site site : sites(profile)) forget(site);
		siteProfiles.values().removeIf(profile.id::equals);
		profiles.remove(profile);
		if (viewed.equals(profile.id)) viewed = profiles.getFirst().id;
		dirty = true;
	}

	/** Moves a greenhouse to another profile; it stops following the old profile's flow plot and is identified again. */
	public void moveTo(Site site, Profile profile) {
		if (profileOf(site) == profile) return;
		assignments.remove(site.key());
		pendingRunners.remove(site.key());
		siteProfiles.put(site.key(), profile.id);
		site.greenhouse().follow(null);
		applyAssignment(site);
		dirty = true;
	}

	/**
	 * Hypixel said which SkyBlock profile the player is on ("Profile ID: …"). The profile tracking it is shown in the
	 * window from now on. The first one seen on a server is tied to the profile that already has greenhouses there
	 * (from before profiles were tracked), whose greenhouses take the SkyBlock profile's id into their keys; a SkyBlock
	 * profile seen for the first time after that gets a new profile of its own.
	 *
	 * @return the scope ({@code server#id}) the greenhouses on that server now use
	 */
	public String onSkyblockProfile(String server, String profileId, @Nullable String profileName) {
		String id = profileId.toLowerCase(Locale.ROOT);
		String scope = server + "#" + id;
		skyblockProfile.put(server, id);
		for (Profile profile : profiles) {
			if (profile.scopes.contains(scope)) {
				view(profile);
				return scope;
			}
		}
		boolean serverKnown = profiles.stream().anyMatch(profile -> profile.scopes.stream().anyMatch(s -> s.startsWith(server + "#")));
		Profile target;
		if (!serverKnown) {
			// Greenhouses saved before profiles: their key is the plain server
			Profile owner = null;
			for (Site site : sites.values()) if (site.scope().equals(server) && greenhouses.contains(site.key())) owner = profileOf(site);
			target = owner != null ? owner : viewedProfile();
			adopt(server, scope, target);
		} else {
			target = createProfile(profileName == null || profileName.isBlank() ? "" : profileName);
			GreenhouseCompanion.LOGGER.info("[GHC] New SkyBlock profile {} on {}: made profile \"{}\"", id, server, target.name);
		}
		target.scopes.add(scope);
		view(target);
		dirty = true;
		return scope;
	}

	/** The scope greenhouses on {@code server} use: the server plus the SkyBlock profile's id, once known. */
	public String scope(String server) {
		String id = skyblockProfile.get(server);
		return id == null ? server : server + "#" + id;
	}

	/** Gives every greenhouse saved under the plain server the SkyBlock profile's scope instead (same data, new key). */
	private void adopt(String server, String scope, Profile profile) {
		for (String key : List.copyOf(greenhouses)) {
			int bar = key.lastIndexOf('|');
			if (bar < 0 || !key.substring(0, bar).equals(server)) continue;
			String newKey = scope + key.substring(bar);
			if (greenhouses.contains(newKey)) continue;
			rekey(key, newKey);
			siteProfiles.put(newKey, profile.id);
		}
	}

	private void rekey(String key, String newKey) {
		greenhouses.remove(key);
		greenhouses.add(newKey);
		move(assignments, key, newKey);
		move(pendingRunners, key, newKey);
		move(pendingTrackers, key, newKey);
		move(notes, key, newKey);
		move(siteProfiles, key, newKey);
		move(lastSeen, key, newKey);
		Site site = sites.remove(key);
		if (site != null) sites.put(newKey, new Site(newKey, newKey.substring(0, newKey.lastIndexOf('|')), site.corner(), site.greenhouse()));
	}

	private static <V> void move(Map<String, V> map, String from, String to) {
		V value = map.remove(from);
		if (value != null) map.put(to, value);
	}

	// Greenhouses

	/** The greenhouse at {@code corner} in {@code scope}, created (and restored from the save) on first use. */
	public Site site(String scope, BlockPos corner) {
		String key = GardenGrid.key(scope, corner);
		Site existing = sites.get(key);
		if (existing != null) return existing;
		Greenhouse greenhouse = new Greenhouse(table, inventory);
		JsonObject tracker = pendingTrackers.remove(key);
		if (tracker != null) greenhouse.loadState(tracker);
		Site site = new Site(key, scope, corner, greenhouse);
		sites.put(key, site);
		applyAssignment(site);
		return site;
	}

	public @Nullable Site siteByKey(String key) {
		return sites.get(key);
	}

	/** Every greenhouse seen, the ones confirmed as greenhouses (a Carpenter stands in the plot) first. */
	public List<Site> sites() {
		List<Site> list = new ArrayList<>();
		for (Site site : sites.values()) if (greenhouses.contains(site.key())) list.add(site);
		return list;
	}

	/** Every greenhouse on a server, whatever its profile (they all grow on that server's clock). */
	public List<Site> sites(String server) {
		return sites().stream().filter(site -> site.server().equals(server)).toList();
	}

	public List<Site> sites(Profile profile) {
		return sites().stream().filter(site -> profileOf(site) == profile).toList();
	}

	public boolean isGreenhouse(String key) {
		return greenhouses.contains(key);
	}

	public void markGreenhouse(String key) {
		if (greenhouses.add(key)) dirty = true;
	}

	/** The watcher read this greenhouse now. */
	public void seen(Site site, long now) {
		lastSeen.put(site.key(), now);
	}

	/** When the greenhouse was last read, or 0 if not since this was recorded. */
	public long lastSeen(Site site) {
		return lastSeen.getOrDefault(site.key(), 0L);
	}

	/** A greenhouse that follows no flow plot: nothing is tracked for it that a visit wouldn't bring back. */
	public boolean inactive(Site site) {
		return site.greenhouse().runner() == null;
	}

	/**
	 * Forgets a greenhouse: its stages, flow position, plot, note and profile. Walking into it again adds it back once
	 * its Carpenter is seen.
	 */
	public void forget(Site site) {
		String key = site.key();
		sites.remove(key);
		greenhouses.remove(key);
		assignments.remove(key);
		pendingRunners.remove(key);
		pendingTrackers.remove(key);
		notes.remove(key);
		siteProfiles.remove(key);
		lastSeen.remove(key);
		dirty = true;
		DebugStream.emit("forgotten", event -> event.addProperty("siteKey", key));
	}

	// Flow files

	public List<FlowFile> library() {
		return List.copyOf(library);
	}

	private int indexOf(@Nullable String name) {
		if (name == null) return -1;
		for (int i = 0; i < library.size(); i++) if (library.get(i).name().equals(name)) return i;
		return -1;
	}

	/** The flows the viewed profile follows. */
	public @Nullable Flows flows() {
		return viewedProfile().flows;
	}

	public @Nullable Flows flows(Site site) {
		return profileOf(site).flows;
	}

	/** Checks of the viewed profile's flows. */
	public List<FlowCheck.Problem> problems() {
		return viewedProfile().problems;
	}

	/** The viewed profile's library file, by index, or -1. */
	public int activeIndex() {
		return indexOf(viewedProfile().flowName);
	}

	public @Nullable FlowFile activeFile() {
		int index = activeIndex();
		return index < 0 ? null : library.get(index);
	}

	/** Every profile that follows library file {@code index}. */
	public List<Profile> usersOf(int index) {
		if (index < 0 || index >= library.size()) return List.of();
		String name = library.get(index).name();
		return profiles.stream().filter(profile -> name.equals(profile.flowName)).toList();
	}

	/**
	 * Reads pasted flows (exported JSON or layout links), adds them to the library under {@code name} (replacing a
	 * file of the same name), writes them to their file at once, and makes them the viewed profile's flows. Throws
	 * with a readable message when it can't.
	 */
	public Flows importFlows(String text, String name) {
		Flows parsed = Flows.parse(text);
		int index = put(text, name);
		Profile profile = viewedProfile();
		for (Profile other : usersOf(index)) if (other != profile) refreshKeeping(other);
		profile.flowName = library.get(index).name();
		refresh(profile, true);
		save();
		return parsed;
	}

	/**
	 * Adds flows to the library without switching to them (e.g. a leaderboard's Ironman flow), written to their file
	 * at once. Throws when unreadable.
	 */
	public void addToLibrary(String text, String name) {
		Flows.parse(text);
		int index = put(text, name);
		for (Profile other : usersOf(index)) refreshKeeping(other);
		save();
	}

	/** Puts the text in the library (replacing a file of that name) and writes its file; returns its index. */
	private int put(String text, String name) {
		int index = indexOf(name);
		FlowFile flowFile = new FlowFile(name, text.strip(), System.currentTimeMillis(), index >= 0 ? library.get(index).file() : fileNameFor(name, text));
		if (index >= 0) library.set(index, flowFile);
		else {
			library.add(flowFile);
			index = library.size() - 1;
		}
		writeFile(flowFile);
		dirty = true;
		return index;
	}

	/**
	 * Replaces the text of the viewed profile's flows (an edited exit amount) and uses it again, keeping every
	 * greenhouse's plot, step and progress, in every profile that follows the same file.
	 */
	public void editActiveFlow(String text) {
		int index = activeIndex();
		if (index < 0) return;
		Flows.parse(text);
		FlowFile old = library.get(index);
		FlowFile edited = new FlowFile(old.name(), text.strip(), old.importedMs(), old.file());
		library.set(index, edited);
		writeFile(edited);
		for (Profile profile : usersOf(index)) refreshKeeping(profile);
		save();
	}

	/** Makes the viewed profile follow library file {@code index}; {@code restart} starts its greenhouses at their plot's start. */
	public void activate(int index, boolean restart) {
		if (index < 0 || index >= library.size()) return;
		Profile profile = viewedProfile();
		profile.flowName = library.get(index).name();
		refresh(profile, restart);
		save();
	}

	/** Parses the profile's flows again, keeping its greenhouses' flow positions. */
	private void refreshKeeping(Profile profile) {
		for (Site site : sites(profile)) {
			if (site.greenhouse().runner() != null) pendingRunners.put(site.key(), site.greenhouse().runner().toJson());
		}
		refresh(profile, false);
	}

	/** Parses the profile's flows file and re-applies its greenhouses' plots; {@code restart} starts them over. */
	private void refresh(Profile profile, boolean restart) {
		int index = indexOf(profile.flowName);
		Flows parsed = null;
		if (index >= 0) {
			try {
				parsed = Flows.parse(library.get(index).text());
			} catch (RuntimeException e) {
				GreenhouseCompanion.LOGGER.error("[GHC] Could not read flows \"{}\" for profile {}", profile.flowName, profile.name, e);
			}
		}
		profile.flows = parsed;
		profile.problems = parsed == null ? List.of() : FlowCheck.check(parsed, plants);
		Flows used = parsed;
		DebugStream.emit("flows", event -> {
			event.addProperty("profile", profile.name);
			event.addProperty("active", profile.flowName);
			event.addProperty("plots", used == null ? 0 : used.plots().size());
			event.addProperty("problems", profile.problems.size());
		});
		for (Site site : List.copyOf(sites.values())) {
			if (profileOf(site) != profile) continue;
			if (restart) pendingRunners.remove(site.key());
			// A plot the new flows don't have is dropped
			Assignment assignment = assignments.get(site.key());
			if (assignment != null && (used == null || used.plots().stream().noneMatch(plot -> plot.id() == assignment.plotId()))) assignments.remove(site.key());
			if (restart && site.greenhouse().runner() != null) site.greenhouse().follow(null);
			applyAssignment(site);
		}
		dirty = true;
	}

	public void rename(int index, String name) {
		if (index < 0 || index >= library.size() || name.isBlank()) return;
		FlowFile old = library.get(index);
		String newName = name.strip();
		if (newName.equals(old.name())) return;
		if (indexOf(newName) >= 0) newName = uniqueName(newName);
		String newFile = fileNameFor(newName, old.text());
		FlowFile renamed = new FlowFile(newName, old.text(), old.importedMs(), newFile);
		try {
			Files.createDirectories(flowDir);
			if (Files.exists(flowDir.resolve(old.file()))) Files.move(flowDir.resolve(old.file()), flowDir.resolve(newFile), StandardCopyOption.REPLACE_EXISTING);
			else writeFile(renamed);
		} catch (IOException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not rename {} to {}", old.file(), newFile, e);
			writeFile(renamed);
		}
		library.set(index, renamed);
		for (Profile profile : profiles) if (old.name().equals(profile.flowName)) profile.flowName = newName;
		save();
	}

	/** Deletes library file {@code index} (and its file); profiles that followed it follow nothing now. */
	public void remove(int index) {
		if (index < 0 || index >= library.size()) return;
		FlowFile removed = library.remove(index);
		try {
			Files.deleteIfExists(flowDir.resolve(removed.file()));
		} catch (IOException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not delete {}", removed.file(), e);
		}
		for (Profile profile : profiles) {
			if (!removed.name().equals(profile.flowName)) continue;
			profile.flowName = null;
			for (Site site : sites(profile)) assignments.remove(site.key());
			refresh(profile, true);
		}
		save();
	}

	/** A name {@link #fileNameFor} could have made: safe characters only, so it stays inside the flows folder. */
	static boolean plainFileName(String name) {
		return name.matches("[A-Za-z0-9 ._()+,'-]{1,100}") && !name.startsWith(".");
	}

	/** A file name for a flows file: the name made safe, .json for an export and .txt for links, not used by another entry. */
	private String fileNameFor(String name, String text) {
		String base = name.strip().replaceAll("[^A-Za-z0-9 ._()+,'-]", "_").replaceAll("\\s+", " ").strip();
		if (base.isEmpty() || base.startsWith(".")) base = "flows" + base;
		if (base.length() > 80) base = base.substring(0, 80).strip();
		String ext = text.strip().startsWith("{") ? ".json" : ".txt";
		Set<String> taken = new HashSet<>();
		for (FlowFile flowFile : library) taken.add(flowFile.file().toLowerCase(Locale.ROOT));
		String candidate = base + ext;
		for (int n = 2; taken.contains(candidate.toLowerCase(Locale.ROOT)); n++) candidate = base + " (" + n + ")" + ext;
		return candidate;
	}

	private String uniqueName(String name) {
		String candidate = name;
		for (int n = 2; indexOf(candidate) >= 0; n++) candidate = name + " (" + n + ")";
		return candidate;
	}

	private void writeFile(FlowFile flowFile) {
		try {
			Files.createDirectories(flowDir);
			Path target = flowDir.resolve(flowFile.file());
			Path temp = target.resolveSibling(flowFile.file() + ".tmp");
			Files.writeString(temp, flowFile.text() + "\n", StandardCharsets.UTF_8);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not write {}", flowFile.file(), e);
		}
	}

	// Assignments

	public @Nullable Assignment assignment(Site site) {
		return assignments.get(site.key());
	}

	/** Makes the greenhouse follow flow plot {@code plotId} from its start, or stop following when {@code null}. */
	public void assign(Site site, @Nullable Integer plotId, boolean auto) {
		Assignment previous = assignments.get(site.key());
		assign(site, plotId, auto, previous == null ? 0 : previous.rotation());
	}

	public void assign(Site site, @Nullable Integer plotId, boolean auto, int rotation) {
		if (plotId == null) assignments.remove(site.key());
		else assignments.put(site.key(), new Assignment(plotId, auto, Math.floorMod(rotation, 4)));
		pendingRunners.remove(site.key());
		applyAssignment(site);
		dirty = true;
	}

	/**
	 * The flow plots of the greenhouse's profile that no other greenhouse of that profile, on the same SkyBlock profile,
	 * follows yet, in id order.
	 */
	public List<Flows.Plot> freePlots(Site site) {
		Profile profile = profileOf(site);
		if (profile.flows == null) return List.of();
		List<Flows.Plot> free = new ArrayList<>();
		for (Flows.Plot plot : profile.flows.plots()) {
			boolean taken = false;
			for (Site other : sites(profile)) {
				if (other == site || !other.scope().equals(site.scope())) continue;
				Assignment assignment = assignments.get(other.key());
				if (assignment != null && assignment.plotId() == plot.id()) taken = true;
			}
			if (!taken) free.add(plot);
		}
		return free;
	}

	/** Flow plot {@code id} of the greenhouse's profile's flows. */
	public Flows.@Nullable Plot plot(Site site, int id) {
		Flows flows = flows(site);
		if (flows == null) return null;
		for (Flows.Plot plot : flows.plots()) if (plot.id() == id) return plot;
		return null;
	}

	/** The greenhouse is planted turned {@code rotation} quarter turns from the flow's layouts. */
	public void setRotation(Site site, int rotation) {
		Assignment assignment = assignments.get(site.key());
		if (assignment != null) assignments.put(site.key(), new Assignment(assignment.plotId(), assignment.auto(), Math.floorMod(rotation, 4)));
		site.greenhouse().setRotation(rotation);
		dirty = true;
	}

	private void applyAssignment(Site site) {
		Assignment assignment = assignments.get(site.key());
		site.greenhouse().setRotation(assignment == null ? 0 : assignment.rotation());
		Flows.Plot plot = assignment == null ? null : plot(site, assignment.plotId());
		boolean same = plot != null && site.greenhouse().runner() != null && site.greenhouse().runner().plot().equals(plot);
		if (same) return;
		site.greenhouse().follow(plot);
		JsonObject saved = pendingRunners.remove(site.key());
		if (saved != null && site.greenhouse().runner() != null) {
			site.greenhouse().runner().load(saved);
			site.greenhouse().swapDone();
		}
	}

	// Saving

	public void markDirty() {
		dirty = true;
	}

	public void saveIfDirty() {
		if (dirty) save();
	}

	public void save() {
		dirty = false;
		JsonObject root = new JsonObject();
		root.addProperty("version", 3);
		JsonArray libraryJson = new JsonArray();
		for (FlowFile flowFile : library) {
			JsonObject json = new JsonObject();
			json.addProperty("name", flowFile.name());
			json.addProperty("file", flowFile.file());
			json.addProperty("importedMs", flowFile.importedMs());
			// Kept here too while the file can't be written, so nothing is lost
			if (!Files.exists(flowDir.resolve(flowFile.file()))) json.addProperty("text", flowFile.text());
			libraryJson.add(json);
		}
		root.add("library", libraryJson);
		JsonArray profileJson = new JsonArray();
		for (Profile profile : profiles) {
			JsonObject json = new JsonObject();
			json.addProperty("id", profile.id);
			json.addProperty("name", profile.name);
			json.addProperty("flow", profile.flowName);
			JsonArray scopes = new JsonArray();
			profile.scopes.forEach(scopes::add);
			json.add("scopes", scopes);
			profileJson.add(json);
		}
		root.add("profiles", profileJson);
		root.addProperty("viewedProfile", viewed);
		JsonObject siteProfileJson = new JsonObject();
		siteProfiles.forEach((key, id) -> {
			if (greenhouses.contains(key)) siteProfileJson.addProperty(key, id);
		});
		root.add("siteProfiles", siteProfileJson);
		JsonObject assignmentJson = new JsonObject();
		assignments.forEach((key, assignment) -> {
			JsonObject json = new JsonObject();
			json.addProperty("plot", assignment.plotId());
			json.addProperty("auto", assignment.auto());
			json.addProperty("rotation", assignment.rotation());
			assignmentJson.add(key, json);
		});
		root.add("assignments", assignmentJson);
		JsonArray greenhouseJson = new JsonArray();
		greenhouses.forEach(greenhouseJson::add);
		root.add("greenhouses", greenhouseJson);
		JsonObject runners = new JsonObject();
		pendingRunners.forEach(runners::add);
		JsonObject trackers = new JsonObject();
		pendingTrackers.forEach(trackers::add);
		for (Site site : sites.values()) {
			if (site.greenhouse().runner() != null) runners.add(site.key(), site.greenhouse().runner().toJson());
			trackers.add(site.key(), site.greenhouse().saveState());
		}
		root.add("runners", runners);
		root.add("trackers", trackers);
		JsonObject seen = new JsonObject();
		lastSeen.forEach(seen::addProperty);
		root.add("lastSeen", seen);
		JsonObject clockJson = new JsonObject();
		clocks.forEach((server, clock) -> clockJson.add(server, clock.toJson()));
		root.add("clocks", clockJson);
		root.add("inventory", inventory.toJson());
		String text = GSON.toJson(root);
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, text, StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Could not save {}", file, e);
		}
	}
}
