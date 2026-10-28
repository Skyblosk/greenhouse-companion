package dev.ilyapple.ghc.mod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import dev.ilyapple.ghc.core.Greenhouse;
import dev.ilyapple.ghc.core.Growth;
import dev.ilyapple.ghc.core.LookKeys;
import dev.ilyapple.ghc.core.StageSet;
import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.flow.Estimator;
import dev.ilyapple.ghc.core.flow.ExitEvaluator;
import dev.ilyapple.ghc.core.flow.FlowRunner;
import dev.ilyapple.ghc.mod.debug.EventHub;

/**
 * The mod's side of the local debug stream ({@link EventHub}): events from what the mod sees and decides, and the
 * {@code /state} snapshot. Off unless "Local debug stream" is on (or {@code -Dghc.debug.stream=true}). It only
 * reads the client: nothing is sent to any server, and the stream is reachable from this machine only.
 *
 * <p>Event types: {@code location}, {@code greenhouse} (entered or left), {@code confirmed} (Carpenter seen),
 * {@code scan} (first full read after arriving), {@code look} (a cell's look applied to the tracker, with where its
 * stage came from), {@code tick}, {@code identify}, {@code paste}, {@code blocked}, {@code swap}, {@code sacks},
 * {@code countdown}, {@code flows}.
 */
public final class DebugStream {
	private static final EventHub HUB = new EventHub();
	private static final Gson GSON = new Gson();

	private DebugStream() {}

	public static EventHub hub() {
		return HUB;
	}

	public static boolean on() {
		return HUB.running();
	}

	/** Starts or stops the stream to match the setting. */
	public static void apply(Settings settings) {
		boolean wanted = settings.debugStream || Boolean.getBoolean("ghc.debug.stream");
		Minecraft client = Minecraft.getInstance();
		if (wanted && !HUB.running()) {
			Path folder = FabricLoader.getInstance().getConfigDir().resolve("greenhouse-companion").resolve("debug");
			try {
				HUB.start(settings.debugPort, folder, query -> client.submit(() -> snapshot(query)));
				GreenhouseCompanion.LOGGER.info("[GHC] Debug stream on http://127.0.0.1:{}/events, writing {}", HUB.port(), HUB.file());
				tell("Debug stream on: http://127.0.0.1:" + HUB.port() + "/events and /state (this machine only)");
			} catch (IOException e) {
				GreenhouseCompanion.LOGGER.error("[GHC] Debug stream couldn't start on port {}", settings.debugPort, e);
				tell("Debug stream couldn't start on port " + settings.debugPort + ": " + e.getMessage());
			}
		} else if (!wanted && HUB.running()) {
			HUB.stop();
			tell("Debug stream off.");
		}
	}

	private static void tell(String text) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) client.player.sendSystemMessage(GreenhouseCompanion.prefix().append(Component.literal(text).withColor(0xFFE2E8F0)));
	}

	/** Sends an event of {@code type}; {@code fill} only runs while the stream is on. */
	public static void emit(String type, Consumer<JsonObject> fill) {
		if (!HUB.running()) return;
		try {
			JsonObject event = new JsonObject();
			event.addProperty("type", type);
			fill.accept(event);
			HUB.publish(event);
		} catch (RuntimeException e) {
			GreenhouseCompanion.LOGGER.warn("[GHC] Debug event {} failed", type, e);
		}
	}

	// Pieces events and the snapshot share

	static void site(JsonObject json, ModState.@Nullable Site site) {
		if (site == null) return;
		json.addProperty("site", site.name());
		json.addProperty("siteKey", site.key());
	}

	static JsonArray corner(BlockPos corner) {
		JsonArray array = new JsonArray();
		array.add(corner.getX());
		array.add(corner.getY());
		array.add(corner.getZ());
		return array;
	}

	static void stages(JsonObject json, StageSet stages) {
		json.addProperty("stages", stages.toString());
		if (!stages.isEmpty()) {
			json.addProperty("lo", stages.lo());
			json.addProperty("hi", stages.hi());
		}
	}

	/** Where a look's stages came from: the Jellybean's shape, Aloe's hologram, the recorder, the model book, or nothing. */
	static String source(dev.ilyapple.ghc.core.Plants.Plant plant, JsonObject print, LookKeys.Keys keys) {
		if (plant.id().equals(Growth.ALOE) && keys.hologramStage() != null) return "hologram";
		return GreenhouseCompanion.state().table().source(plant, print, keys.key());
	}

	/** The {@code /state} answer, built on the game thread. */
	static JsonObject snapshot(Map<String, String> query) {
		Minecraft client = Minecraft.getInstance();
		ModState state = GreenhouseCompanion.state();
		JsonObject root = new JsonObject();
		root.addProperty("t", System.currentTimeMillis());
		root.addProperty("inGarden", GreenhouseCompanion.location().inGarden());
		root.addProperty("locationSource", GreenhouseCompanion.location().source());
		String server = GardenGrid.server(client);
		root.addProperty("server", server);

		var clock = state.clock(server);
		JsonObject clockJson = new JsonObject();
		clockJson.addProperty("synced", clock.synced());
		clockJson.addProperty("periodMs", clock.periodMs());
		if (clock.synced()) clockJson.addProperty("nextTickInMs", clock.nextTickMs() - System.currentTimeMillis());
		root.add("clock", clockJson);

		JsonObject flows = new JsonObject();
		ModState.FlowFile active = state.activeFile();
		flows.addProperty("active", active == null ? null : active.name());
		flows.addProperty("plots", state.flows() == null ? 0 : state.flows().plots().size());
		flows.addProperty("problems", state.problems().size());
		flows.addProperty("profile", state.viewedProfile().name());
		root.add("flows", flows);
		JsonArray profiles = new JsonArray();
		for (ModState.Profile profile : state.profiles()) {
			JsonObject json = new JsonObject();
			json.addProperty("id", profile.id());
			json.addProperty("name", profile.name());
			json.addProperty("flow", profile.flowName());
			json.addProperty("greenhouses", state.sites(profile).size());
			JsonArray scopes = new JsonArray();
			profile.scopes().forEach(scopes::add);
			json.add("scopes", scopes);
			profiles.add(json);
		}
		root.add("profiles", profiles);

		JsonArray marks = new JsonArray();
		for (dev.ilyapple.ghc.core.overlay.PlotOverlay.Mark mark : dev.ilyapple.ghc.mod.render.LayoutOverlay.marks()) {
			JsonObject json = new JsonObject();
			json.addProperty("action", mark.action().name());
			json.addProperty("cell", mark.anchor());
			json.addProperty("x", mark.anchor() / 10);
			json.addProperty("z", mark.anchor() % 10);
			json.addProperty("size", mark.size());
			json.addProperty("want", mark.want());
			json.addProperty("present", mark.present());
			json.addProperty("label", mark.label());
			marks.add(json);
		}
		root.add("overlay", marks);

		JsonArray greenhouses = new JsonArray();
		for (ModState.Site known : state.sites()) {
			JsonObject json = new JsonObject();
			site(json, known);
			ModState.Assignment assignment = state.assignment(known);
			if (assignment != null) {
				json.addProperty("flowPlot", assignment.plotId());
				json.addProperty("auto", assignment.auto());
			}
			FlowRunner runner = known.greenhouse().runner();
			if (runner != null) json.addProperty("step", runner.step().label());
			json.addProperty("swapPending", known.greenhouse().swapPending());
			greenhouses.add(json);
		}
		root.add("greenhouses", greenhouses);

		ModState.Site here = GreenhouseCompanion.currentSite();
		BlockPos corner = GreenhouseCompanion.watcherCorner();
		if (here != null && corner != null) root.add("here", here(here, corner, "1".equals(query.get("prints"))));
		root.add("settings", GSON.toJsonTree(GreenhouseCompanion.settings()));
		return root;
	}

	private static JsonObject here(ModState.Site site, BlockPos corner, boolean prints) {
		ModState state = GreenhouseCompanion.state();
		JsonObject json = new JsonObject();
		site(json, site);
		json.add("corner", corner(corner));
		ModState.Assignment assignment = state.assignment(site);
		if (assignment != null) {
			json.addProperty("flowPlot", assignment.plotId());
			json.addProperty("auto", assignment.auto());
		}
		json.addProperty("note", state.note(site));

		Greenhouse.Status status = site.greenhouse().status(4);
		FlowRunner runner = status.runner();
		if (runner != null) {
			JsonObject flow = new JsonObject();
			flow.addProperty("stepIndex", runner.stepIndex());
			flow.addProperty("step", runner.step().label());
			flow.addProperty("cyclesInStep", runner.cyclesInStep());
			flow.addProperty("finished", runner.finished());
			flow.addProperty("swapPending", status.swapPending());
			JsonArray exits = new JsonArray();
			for (List<ExitEvaluator.Verdict> verdicts : status.verdicts()) {
				JsonArray exit = new JsonArray();
				for (ExitEvaluator.Verdict verdict : verdicts) {
					JsonObject v = new JsonObject();
					v.addProperty("text", verdict.text());
					v.addProperty("value", verdict.value());
					v.addProperty("assumed", verdict.assumed());
					v.addProperty("supported", verdict.supported());
					v.addProperty("detail", verdict.detail());
					exit.add(v);
				}
				exits.add(exit);
			}
			flow.add("exits", exits);
			JsonArray plan = new JsonArray();
			for (Estimator.StepEstimate estimate : status.plan()) {
				JsonObject e = new JsonObject();
				e.addProperty("step", estimate.label());
				e.addProperty("cycles", Double.isFinite(estimate.cycles()) ? estimate.cycles() : -1);
				e.addProperty("reason", estimate.reason());
				plan.add(e);
			}
			flow.add("plan", plan);
			json.add("flow", flow);
		}

		JsonObject[] latest = GreenhouseCompanion.latestPrints();
		// What the census makes of each cell: the origin it counts (placed by default when grown off a spot) and
		// whether the harvest highlight takes it
		dev.ilyapple.ghc.core.flow.Census census = site.greenhouse().context().census();
		dev.ilyapple.ghc.core.schematic.Schematic layout = site.greenhouse().layout();
		java.util.Map<Integer, dev.ilyapple.ghc.core.flow.Census.Plant> plantAt = new java.util.HashMap<>();
		for (dev.ilyapple.ghc.core.flow.Census.Plant plant : census.plants()) for (int c : plant.cells()) plantAt.put(c, plant);
		json.addProperty("readyToHarvest", dev.ilyapple.ghc.mod.render.Highlight.plants());
		if (layout != null) json.addProperty("rotation", site.greenhouse().rotation());
		JsonArray cells = new JsonArray();
		for (StageTracker.Tracked tracked : site.greenhouse().tracker().all()) {
			JsonObject cell = new JsonObject();
			cell.addProperty("cell", tracked.cell());
			cell.addProperty("x", tracked.x());
			cell.addProperty("z", tracked.z());
			cell.addProperty("plant", tracked.plant().id());
			stages(cell, tracked.stages());
			cell.addProperty("origin", tracked.origin().name());
			dev.ilyapple.ghc.core.flow.Census.Plant counted = plantAt.get(tracked.cell());
			if (counted != null) {
				cell.addProperty("counted", counted.origin().name());
				cell.addProperty("ready", dev.ilyapple.ghc.core.overlay.Harvest.ready(counted, layout, GreenhouseCompanion.settings().harvestBaseCrops));
			} else if (census.clickBlock(tracked.cell())) {
				cell.addProperty("counted", "CLICK_BLOCK");
			}
			cell.addProperty("exact", tracked.exact());
			cell.addProperty("lookKnown", tracked.lookKnown());
			cell.addProperty("mayStall", tracked.mayStall());
			cell.addProperty("look", LookKeys.shortId(tracked.lookKey()));
			JsonObject print = latest == null ? null : latest[tracked.cell()];
			if (print != null) {
				LookKeys.Keys keys = LookKeys.keysOf(print);
				cell.addProperty("source", source(tracked.plant(), print, keys));
			}
			cells.add(cell);
		}
		json.add("cells", cells);

		JsonArray beans = new JsonArray();
		for (int cell = 0; cell < GardenGrid.SIZE * GardenGrid.SIZE; cell++) {
			JellybeanView.Bean bean = GreenhouseCompanion.jellybeans().bean(cell);
			if (bean == null) continue;
			JsonObject b = new JsonObject();
			b.addProperty("cell", cell);
			b.addProperty("tier", bean.shape().tier());
			stages(b, bean.stages());
			b.addProperty("fullyGrown", bean.fullyGrown());
			b.addProperty("placed", bean.placed());
			b.addProperty("label", JellybeanView.text(bean));
			beans.add(b);
		}
		json.add("jellybeans", beans);

		if (prints && latest != null) {
			JsonObject printJson = new JsonObject();
			for (int cell = 0; cell < latest.length; cell++) if (latest[cell] != null) printJson.add(Integer.toString(cell), latest[cell]);
			json.add("prints", printJson);
		}
		return json;
	}

	/** For tests and the demo: a snapshot without the HTTP round trip. */
	static CompletableFuture<JsonObject> snapshotNow(Map<String, String> query) {
		return Minecraft.getInstance().submit(() -> snapshot(query));
	}
}
