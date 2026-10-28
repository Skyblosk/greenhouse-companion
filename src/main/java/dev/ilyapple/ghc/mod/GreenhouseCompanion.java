package dev.ilyapple.ghc.mod;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.google.gson.JsonObject;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import dev.ilyapple.ghc.core.Greenhouse;
import dev.ilyapple.ghc.core.LookTable;
import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.StageSet;
import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.core.flow.FlowCheck;
import dev.ilyapple.ghc.core.flow.FlowRunner;
import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.flow.LayoutMatch;
import dev.ilyapple.ghc.core.flow.MidStep;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;
import dev.ilyapple.ghc.mod.ui.CompanionHud;
import dev.ilyapple.ghc.mod.ui.CompanionScreen;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Greenhouse Companion: follows SkyShards simulator flows in the Hypixel SkyBlock Greenhouse.
 *
 * <ul>
 * <li>Import a flows export (or layout links); each greenhouse you visit is identified (which flow plot it runs,
 *     and which step, from what grows there) and followed from then on.</li>
 * <li>The current step's layout is drawn in the greenhouse you stand in, turned the way it is planted: red boxes on
 *     every cell that needs something done, ghosts of missing plants, and what to do written up close.</li>
 * <li>Plant stages come from how plants look and the shared growth clock; exits are checked live and you are told
 *     when to swap layouts.</li>
 * <li>Magic Jellybeans show their stage, can be drawn one block tall, and can't be broken before stage 120.</li>
 * </ul>
 */
public final class GreenhouseCompanion implements ClientModInitializer {
	public static final String MOD_ID = "greenhouse-companion";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static ModState state;
	private static Settings settings;
	private static Location location;
	private static Watcher watcher;
	private static MenuReader menus;
	private static JellybeanView jellybeans;
	private static Protection protection;
	private static DropRecorder drops;
	private static KeyMapping openKey;
	private static KeyMapping bypassKey;
	private static long tick;
	private static CompanionScreen.@Nullable Page openPage;
	private static final Map<String, Long> uses = new HashMap<>();
	/** Greenhouses identified on this visit, so a failed match isn't retried every second. */
	private static final Set<String> identifiedThisVisit = new HashSet<>();
	private static @Nullable String lastSiteKey;

	public static ModState state() {
		return state;
	}

	public static Settings settings() {
		return settings;
	}

	public static JellybeanView jellybeans() {
		return jellybeans;
	}

	public static Protection protection() {
		return protection;
	}

	public static DropRecorder drops() {
		return drops;
	}

	public static Location location() {
		return location;
	}

	public static KeyMapping bypassKey() {
		return bypassKey;
	}

	public static KeyMapping openKey() {
		return openKey;
	}

	static @Nullable BlockPos watcherCorner() {
		return watcher == null ? null : watcher.corner();
	}

	static JsonObject @Nullable [] latestPrints() {
		return watcher == null ? null : watcher.latest();
	}

	/** The greenhouse the player is in, or {@code null}. */
	public static ModState.@Nullable Site currentSite() {
		return watcher == null ? null : watcher.current();
	}

	public static MutableComponent prefix() {
		return Component.literal("[Greenhouse] ").withColor(0xFF34D399);
	}

	@Override
	public void onInitializeClient() {
		settings = Settings.load(FabricLoader.getInstance().getConfigDir());
		state = ModState.load(FabricLoader.getInstance().getConfigDir());
		location = new Location();
		location.register();
		jellybeans = new JellybeanView();
		watcher = new Watcher(state, location, jellybeans, settings);
		menus = new MenuReader(state);
		DevDemo.install(state);
		LOGGER.info("[GHC] Looks from the {}; models from {}", state.tableSource(), state.table().models() == null ? "nothing" : state.table().models().source());

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.greenhouse-companion.open", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
		bypassKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.greenhouse-companion.bypass", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, category));
		protection = new Protection(settings, bypassKey);
		drops = new DropRecorder(settings, FabricLoader.getInstance().getConfigDir());
		Prices.init(FabricLoader.getInstance().getConfigDir());
		dev.ilyapple.ghc.mod.ui.DropsPanel.register();

		HiddenBlockModel.register();
		dev.ilyapple.ghc.mod.render.Depthless.init();
		LevelRenderEvents.COLLECT_SUBMITS.register(WorldLabels::render);
		LevelRenderEvents.COLLECT_SUBMITS.register(dev.ilyapple.ghc.mod.render.LayoutOverlay::render);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(dev.ilyapple.ghc.mod.render.Highlight::render);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(dev.ilyapple.ghc.mod.render.LayoutOverlay::renderBoxes);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), new CompanionHud());

		ClientTickEvents.END_CLIENT_TICK.register(GreenhouseCompanion::tick);
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> DebugStream.apply(settings));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			state.save();
			drops.save();
			identifiedThisVisit.clear();
		});
		// Hypixel names the SkyBlock profile on every server join; greenhouses are kept per profile
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			onGameMessage(message);
			if (!overlay) drops.onChat(message);
		});
		ClientReceiveMessageEvents.GAME_CANCELED.register((message, overlay) -> {
			onGameMessage(message);
			if (!overlay) drops.onChat(message);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			DebugStream.hub().stop();
			state.save();
			settings.save();
			drops.save();
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(literal("ghc")
				.executes(c -> open(CompanionScreen.Page.OVERVIEW))
				.then(literal("import").executes(c -> importClipboard(c, null))
						.then(argument("name", StringArgumentType.greedyString()).executes(c -> importClipboard(c, StringArgumentType.getString(c, "name")))))
				.then(literal("follow")
						.then(literal("auto").executes(GreenhouseCompanion::reidentify))
						.then(argument("plot", IntegerArgumentType.integer(1)).executes(c -> follow(c, IntegerArgumentType.getInteger(c, "plot")))))
				.then(literal("unfollow").executes(c -> follow(c, null)))
				.then(literal("identify").executes(GreenhouseCompanion::reidentify))
				.then(literal("swapped").executes(GreenhouseCompanion::swapped))
				.then(literal("status").executes(GreenhouseCompanion::status))
				.then(literal("missing").executes(GreenhouseCompanion::missing))
				.then(literal("flows").executes(c -> open(CompanionScreen.Page.IMPORT)))
				.then(literal("leaderboard").executes(c -> open(CompanionScreen.Page.LEADERBOARD)))
				.then(literal("settings").executes(c -> open(CompanionScreen.Page.SETTINGS)))
				.then(literal("profit").executes(c -> open(CompanionScreen.Page.DROPS)))
				.then(literal("debug")
						.then(literal("stream")
								.then(literal("on").executes(c -> debugStream(c, true)))
								.then(literal("off").executes(c -> debugStream(c, false))))
						.then(literal("status").executes(GreenhouseCompanion::debugStatus)))
				.then(literal("hud").executes(c -> {
					settings.hud = !settings.hud;
					settings.save();
					c.getSource().sendFeedback(prefix().append("HUD " + (settings.hud ? "on" : "off")));
					return 1;
				}))));
	}

	private static void tick(Minecraft client) {
		tick++;
		if (openPage != null && client.screen == null) {
			client.setScreen(new CompanionScreen(null, openPage));
			openPage = null;
		}
		while (openKey.consumeClick()) {
			if (client.screen == null) client.setScreen(new CompanionScreen(null, CompanionScreen.Page.OVERVIEW));
		}
		watcher.tick(client);
		menus.tick(client);
		WaterHelper.tick(client);
		drops.tick(client);
		if (tick % 20 == 0 && client.player != null) {
			long now = System.currentTimeMillis();
			String server = GardenGrid.server(client);
			int ticks = state.clock(server).poll(now);
			if (ticks > 0) applyTicks(server, ticks);
			ModState.Site here = currentSite();
			for (ModState.Site site : state.sites(server)) {
				for (Greenhouse.Swap swap : site.greenhouse().update()) announce(client, site, swap);
				if (site.greenhouse().swapPending() && site.equals(here) && layoutInPlace(site)) site.greenhouse().swapDone();
			}
			String key = here == null ? null : here.key();
			if (key == null || !key.equals(lastSiteKey)) {
				lastSiteKey = key;
				if (key == null) identifiedThisVisit.clear();
			}
			if (here != null && watcher.ready() && settings.autoIdentify) identify(client, here, false);
		}
		if (tick % 600 == 0) state.saveIfDirty();
	}

	/** The growth clock moved on: every greenhouse on the server grows. */
	static void applyTicks(String server, int ticks) {
		for (ModState.Site site : state.sites(server)) site.greenhouse().tick(ticks, ticks);
		state.markDirty();
		DebugStream.emit("tick", event -> {
			event.addProperty("server", server);
			event.addProperty("ticks", ticks);
			event.addProperty("periodMs", state.clock(server).periodMs());
			event.addProperty("nextTickInMs", state.clock(server).nextTickMs() - System.currentTimeMillis());
		});
		Minecraft client = Minecraft.getInstance();
		if (settings.tickChime && client.player != null) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME, 1.4f));
			client.gui.setOverlayMessage(Component.literal("Greenhouse growth tick").withColor(0xFF34D399), false);
		}
	}

	// Identifying greenhouses

	/**
	 * Works out which flow plot and step a greenhouse runs from what grows there, once every cell has been read.
	 * <ul>
	 * <li>Following nothing (or {@code force}): the plot whose step layout best matches, moved to that step, else
	 *     the first plot no other greenhouse follows, at its start.</li>
	 * <li>Picked by the mod earlier: checked again once per visit, and moved only when what grows clearly matches a
	 *     different step, so progress on the current step isn't lost.</li>
	 * <li>Picked by the player: left alone.</li>
	 * </ul>
	 * Entering a matched step counts what the greenhouse already shows towards its exits ({@link MidStep}).
	 */
	static void identify(Minecraft client, ModState.Site site, boolean force) {
		Flows flows = state.flows(site);
		if (flows == null) return;
		ModState.Assignment previous = state.assignment(site);
		if (!force && (identifiedThisVisit.contains(site.key()) || previous != null && !previous.auto())) return;
		identifiedThisVisit.add(site.key());

		Map<Integer, String> plantByCell = new HashMap<>();
		Census census = site.greenhouse().context().census();
		for (Census.Plant plant : census.plants()) for (int cell : plant.cells()) plantByCell.put(cell, plant.id());
		LayoutMatch.Verdict verdict = LayoutMatch.identify(flows, plantByCell);

		if (!force && previous != null) {
			recheck(client, site, flows, previous, verdict, census);
			return;
		}

		List<Flows.Plot> free = new java.util.ArrayList<>(state.freePlots(site));
		if (previous != null) {
			Flows.Plot own = state.plot(site, previous.plotId());
			if (own != null && !free.contains(own)) free.add(own);
		}
		if (free.isEmpty()) {
			state.setNote(site, "Every flow plot of profile \"" + state.profileOf(site).name() + "\" is already followed by another greenhouse.");
			return;
		}
		LayoutMatch.Score chosen = null;
		if (verdict.confident() && verdict.best() != null) {
			Schematic bestLayout = LayoutMatch.layout(flows, verdict.best());
			for (LayoutMatch.Score score : LayoutMatch.rank(flows, plantByCell)) {
				if (score.value() < verdict.best().value() - 1e-9) break;
				boolean isFree = free.stream().anyMatch(plot -> plot.id() == score.plotId());
				if (isFree && (score.plotId() == verdict.best().plotId() || bestLayout != null && bestLayout.sameAs(LayoutMatch.layout(flows, score)))) {
					chosen = score;
					break;
				}
			}
		}
		int plotId = chosen != null ? chosen.plotId() : free.getFirst().id();
		state.assign(site, plotId, true, chosen != null ? chosen.rotation() : 0);
		String how;
		List<MidStep.Seeded> seeded = List.of();
		FlowRunner runner = site.greenhouse().runner();
		if (chosen != null && runner != null) {
			if (chosen.stepIndex() != runner.stepIndex()) runner.enter(chosen.stepIndex());
			seeded = MidStep.seed(runner, census, chosen.rotation());
			site.greenhouse().swapDone();
			how = "matched step \"" + chosen.stepLabel() + "\" (" + chosen.matched() + " of " + chosen.expected() + " plants in place" + turned(chosen.rotation()) + ")"
					+ progress(seeded);
		} else {
			how = plantByCell.isEmpty() ? "empty greenhouse, starting at the first step" : "nothing planted matched a step, starting at the first step";
		}
		identified(client, site, plotId, verdict, chosen, seeded, how, true);
	}

	/** A greenhouse the mod identified before: moves it only when it clearly runs another step now. */
	private static void recheck(Minecraft client, ModState.Site site, Flows flows, ModState.Assignment assignment, LayoutMatch.Verdict verdict, Census census) {
		FlowRunner runner = site.greenhouse().runner();
		if (runner == null || !verdict.confident() || verdict.best() == null) return;
		Schematic matched = LayoutMatch.layout(flows, verdict.best());
		if (matched == null || matched.sameAs(site.greenhouse().layout())) return;
		// The same layout in the plot it already follows (at whatever turn matched), else the matched plot when it is free
		Schematic drawn = matched.rotated(-verdict.best().rotation());
		int index = -1;
		for (int i = 0; i < runner.plot().steps().size(); i++) if (drawn.sameAs(runner.plot().steps().get(i).schematic())) index = i;
		if (index < 0) {
			identify(client, site, true);
			return;
		}
		if (index != runner.stepIndex()) runner.enter(index);
		state.setRotation(site, verdict.best().rotation());
		List<MidStep.Seeded> seeded = MidStep.seed(runner, census, verdict.best().rotation());
		site.greenhouse().swapDone();
		state.markDirty();
		String how = "now at step \"" + runner.step().label() + "\" (" + verdict.best().matched() + " of " + verdict.best().expected() + " plants in place"
				+ turned(verdict.best().rotation()) + ")" + progress(seeded);
		identified(client, site, assignment.plotId(), verdict, verdict.best(), seeded, how, true);
	}

	private static String turned(int rotation) {
		return switch (rotation) {
			case 1 -> ", planted turned a quarter clockwise";
			case 2 -> ", planted turned half way round";
			case 3 -> ", planted turned a quarter anticlockwise";
			default -> "";
		};
	}

	private static String progress(List<MidStep.Seeded> seeded) {
		if (seeded.isEmpty()) return "";
		List<String> parts = new java.util.ArrayList<>();
		for (MidStep.Seeded seed : seeded) {
			Plants.Plant plant = state.plants().byId(seed.id());
			parts.add(seed.count() + " " + (plant == null ? seed.id() : plant.name()) + " already " + seed.kind());
		}
		return "; counted " + String.join(", ", parts);
	}

	private static void identified(Minecraft client, ModState.Site site, int plotId, LayoutMatch.Verdict verdict, LayoutMatch.@Nullable Score picked,
			List<MidStep.Seeded> seeded, String how, boolean tell) {
		state.setNote(site, "Identified: " + how);
		state.markDirty();
		// The step or turn may have changed: drop what was drawn for the old one, the next scan plans it again
		dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
		DebugStream.emit("identify", event -> {
			DebugStream.site(event, site);
			event.addProperty("flowPlot", plotId);
			event.addProperty("confident", verdict.confident());
			if (picked != null) {
				event.addProperty("step", picked.stepLabel());
				event.addProperty("matched", picked.matched());
				event.addProperty("wrong", picked.wrong());
				event.addProperty("expected", picked.expected());
			}
			if (verdict.runnerUp() != null) event.addProperty("runnerUpPlot", verdict.runnerUp().plotId());
			if (picked != null) event.addProperty("rotation", picked.rotation());
			com.google.gson.JsonObject counted = new com.google.gson.JsonObject();
			for (MidStep.Seeded seed : seeded) counted.addProperty(seed.kind() + ":" + seed.id(), seed.count());
			event.add("counted", counted);
			event.addProperty("how", how);
		});
		if (tell && client.player != null) {
			client.player.sendSystemMessage(prefix().append(Component.literal(siteLabel(site) + " follows flow plot " + plotId + ": " + how + " ").withColor(0xFFE2E8F0))
					.append(Component.literal("[Change]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
							.withClickEvent(new ClickEvent.RunCommand("/ghc"))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the Greenhouse window to pick another plot"))))));
		}
	}


	/** A greenhouse's name, with its profile's when there is more than one profile. */
	public static String siteLabel(ModState.Site site) {
		return state.profiles().size() > 1 ? site.name() + " (" + state.profileOf(site).name() + ")" : site.name();
	}

	private static final java.util.regex.Pattern PROFILE_ID = java.util.regex.Pattern.compile("^Profile ID: ([0-9a-fA-F-]{32,36})$");
	private static final java.util.regex.Pattern PROFILE_NAME = java.util.regex.Pattern.compile("^You are playing on profile: ([A-Za-z]+)");
	private static @Nullable String lastProfileName;

	/** "Profile ID: …" (and "You are playing on profile: Apple") tell which SkyBlock profile's greenhouses these are. */
	private static void onGameMessage(Component message) {
		String text = ChatFormatting.stripFormatting(message.getString());
		if (text == null) return;
		text = text.strip();
		java.util.regex.Matcher name = PROFILE_NAME.matcher(text);
		if (name.find()) lastProfileName = name.group(1);
		java.util.regex.Matcher id = PROFILE_ID.matcher(text);
		if (!id.matches()) return;
		Minecraft client = Minecraft.getInstance();
		String server = GardenGrid.server(client);
		String profileName = lastProfileName != null ? lastProfileName : GardenGrid.tabProfileName(client);
		int before = state.profiles().size();
		String scope = state.onSkyblockProfile(server, id.group(1), profileName);
		identifiedThisVisit.clear();
		dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
		DebugStream.emit("profile", event -> {
			event.addProperty("server", server);
			event.addProperty("scope", scope);
			event.addProperty("profile", state.viewedProfile().name());
		});
		if (state.profiles().size() > before && client.player != null) {
			client.player.sendSystemMessage(prefix().append(Component.literal("New SkyBlock profile: its greenhouses are tracked in profile \""
					+ state.viewedProfile().name() + "\". ").withColor(0xFFE2E8F0))
					.append(Component.literal("[Open]").withStyle(style -> style.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand("/ghc")))));
		}
	}

	// Placements

	/** Called from the right-click mixin: remembers placements and plantings in the greenhouse. */
	public static void onUse(Minecraft client) {
		if (client.player == null || watcher == null) return;
		ModState.Site site = watcher.current();
		BlockPos corner = watcher.corner();
		if (site == null || corner == null) return;
		if (!client.player.getMainHandItem().isEmpty()) WaterHelper.used(client.player.getMainHandItem());
		int cell = GardenGrid.targetedCell(client, corner);
		if (cell < 0 || client.player.getMainHandItem().isEmpty()) return;
		long now = System.currentTimeMillis();
		String held = Fingerprints.textOf(client.player.getMainHandItem().getHoverName());
		if (held.toLowerCase(java.util.Locale.ROOT).contains("diagnostic")) {
			// The Plant Diagnostics Tool: the Crop Diagnostics menu that opens next is about this cell
			diagnosed = new Diagnosed(site.key(), cell, now);
			return;
		}
		site.greenhouse().use(cell, held, now);
		uses.put(site.key() + "/" + cell, now);
	}

	/** The cell the Plant Diagnostics Tool was last used on. */
	record Diagnosed(String siteKey, int cell, long ms) {}

	private static @Nullable Diagnosed diagnosed;

	/** The cell a Crop Diagnostics menu opened now is about: the tool was used on it within the last few seconds. */
	static @Nullable Diagnosed diagnosedCell(long now) {
		Diagnosed last = diagnosed;
		return last != null && now - last.ms() < 8_000 ? last : null;
	}

	/** A Crop Diagnostics reading: it overrides what the mod had worked out for that plant. */
	static void applyDiagnosis(Minecraft client, dev.ilyapple.ghc.core.Diagnosis reading, long now) {
		Diagnosed target = diagnosedCell(now);
		if (target == null) return;
		diagnosed = null;
		ModState.Site site = null;
		for (ModState.Site each : state.sites()) if (each.key().equals(target.siteKey())) site = each;
		if (site == null) return;
		List<dev.ilyapple.ghc.core.StageTracker.Update> updates = site.greenhouse().diagnose(target.cell(), reading, now);
		// The menu states the water exactly ("Water: -30/100"): that crop's water is known from here on
		if (reading.water() != null) {
			for (dev.ilyapple.ghc.core.flow.Census.Plant plant : site.greenhouse().context().census().plants()) {
				if (!plant.cells().contains(target.cell())) continue;
				if (dev.ilyapple.ghc.core.Water.consumes(plant.kind(), plant.origin())) {
					int stage = reading.stage() != null ? reading.stage() : plant.stages().isEmpty() ? 1 : plant.stages().hi();
					site.greenhouse().water().read(plant, reading.water(), stage);
					state.markDirty();
					WaterHelper.refresh();
				}
				break;
			}
		}
		if (updates.isEmpty()) return;
		state.markDirty();
		dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
		var after = updates.getFirst().after();
		ModState.Site at = site;
		DebugStream.emit("diagnosed", event -> {
			DebugStream.site(event, at);
			event.addProperty("cell", target.cell());
			event.addProperty("cells", updates.size());
			event.addProperty("menuPlant", reading.plant());
			event.addProperty("menuStage", reading.stage());
			event.addProperty("fullyGrown", reading.fullyGrown());
			event.addProperty("dead", reading.dead());
			event.addProperty("status", reading.status());
			if (after != null) {
				event.addProperty("plant", after.plant().id());
				event.addProperty("origin", after.origin().name());
				DebugStream.stages(event, after.stages());
			}
		});
		if (after != null && client.player != null) {
			String origin = switch (after.origin()) {
				case PLACED -> "placed";
				case SPAWNED -> "spawned";
				case PLANTED -> "planted";
				case UNKNOWN -> "origin unknown";
			};
			client.gui.setOverlayMessage(Component.literal(after.plant().name() + ": " + WorldLabels.stageText(after.stages(), after.plant()) + " · " + origin
					+ (reading.stalled() ? " · " + reading.status() : "") + " (from the Plant Diagnostics Tool)").withColor(0xFF34D399), false);
		}
	}

	static boolean usedRecently(ModState.Site site, int cell, long now) {
		Long used = uses.get(site.key() + "/" + cell);
		return used != null && now - used < dev.ilyapple.ghc.core.StageTracker.USE_WINDOW_MS;
	}

	/** Whether every plant the current step's layout places is there. */
	public static boolean layoutInPlace(ModState.Site site) {
		Schematic layout = site.greenhouse().layout();
		if (layout == null) return true;
		Census census = site.greenhouse().context().census();
		for (var placement : layout.placements().entrySet()) {
			SkyblockerCrops.Crop crop = SkyblockerCrops.byId(placement.getValue());
			var cell = census.cell(placement.getKey());
			if (crop == null || crop.simId() == null) continue;
			if (cell == null || !cell.plant().id().equals(crop.simId())) return false;
		}
		return true;
	}

	private static void announce(Minecraft client, ModState.Site site, Greenhouse.Swap swap) {
		if (client.player == null) return;
		String because = String.join(", ", swap.because().stream()
				.map(verdict -> verdict.text() + (verdict.assumed() ? " (assumed: " + verdict.detail() + ")" : "")).toList());
		MutableComponent message = prefix().append(Component.literal(siteLabel(site) + ": ").withColor(0xFFFBBF24));
		if (swap.finished()) {
			message.append(Component.literal("the flow is finished (" + because + ")").withColor(0xFF34D399));
		} else {
			message.append(Component.literal("swap to ").withColor(0xFFE2E8F0))
					.append(Component.literal(swap.to().label()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
					.append(Component.literal(" — " + because + " ").withColor(0xFF94A3B8))
					.append(Component.literal("[Open]").withStyle(style -> style.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent.RunCommand("/ghc"))));
			if (settings.alertTitle) {
				client.gui.setTitle(Component.literal("Swap to " + swap.to().label()).withColor(0xFFFBBF24));
				client.gui.setSubtitle(Component.literal(siteLabel(site)).withColor(0xFF94A3B8));
			}
		}
		// The chime and chat line are part of the swap alert (sub-settings of its title)
		if (settings.alertTitle && settings.alertChat) client.player.sendSystemMessage(message);
		DebugStream.emit("swap", event -> {
			DebugStream.site(event, site);
			event.addProperty("from", swap.from().label());
			event.addProperty("to", swap.to().label());
			event.addProperty("finished", swap.finished());
			event.addProperty("because", because);
		});
		if (settings.alertTitle && settings.alertSound) client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.2f));
		state.markDirty();
	}

	// Commands

	public static int open(CompanionScreen.Page page) {
		openPage = page;
		return 1;
	}

	/** A name for a pasted file: the flow's plots and the time. */
	public static String defaultName(String text) {
		String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d HH:mm"));
		try {
			Flows flows = Flows.parse(text);
			int steps = flows.plots().stream().mapToInt(plot -> plot.steps().size()).sum();
			return flows.plots().size() + " plot" + (flows.plots().size() == 1 ? "" : "s") + " · " + steps + " steps · " + time;
		} catch (RuntimeException e) {
			return "Flows · " + time;
		}
	}

	private static int importClipboard(CommandContext<FabricClientCommandSource> context, @Nullable String name) {
		String text = Minecraft.getInstance().keyboardHandler.getClipboard();
		try {
			Flows flows = state.importFlows(text, name == null ? defaultName(text) : name);
			identifiedThisVisit.clear();
			dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
			long errors = state.problems().stream().filter(problem -> problem.level() == FlowCheck.Level.ERROR).count();
			long warnings = state.problems().stream().filter(problem -> problem.level() == FlowCheck.Level.WARNING).count();
			context.getSource().sendFeedback(prefix().append(Component.literal("Imported " + flows.plots().size() + " plot"
					+ (flows.plots().size() == 1 ? "" : "s") + ". Walk into each greenhouse and it's identified and followed.").withColor(0xFF34D399))
					.append(errors + warnings == 0 ? Component.empty() : Component.literal(" " + errors + " problem" + (errors == 1 ? "" : "s") + ", " + warnings
							+ " warning" + (warnings == 1 ? "" : "s") + " [Show]").withStyle(style -> style.withColor(0xFFFBBF24).withClickEvent(new ClickEvent.RunCommand("/ghc flows")))));
		} catch (RuntimeException e) {
			context.getSource().sendFeedback(prefix().append(Component.literal("Couldn't read the clipboard: " + e.getMessage()).withColor(0xFFF87171)));
		}
		return 1;
	}

	private static int follow(CommandContext<FabricClientCommandSource> context, @Nullable Integer plot) {
		ModState.Site site = currentSite();
		if (site == null) {
			context.getSource().sendFeedback(prefix().append(Component.literal("Stand in the greenhouse first.").withColor(0xFFF87171)));
			return 0;
		}
		if (plot != null && state.plot(site, plot) == null) {
			context.getSource().sendFeedback(prefix().append(Component.literal("The flows of profile \"" + state.profileOf(site).name() + "\" have no plot " + plot + ".").withColor(0xFFF87171)));
			return 0;
		}
		state.assign(site, plot, false);
		state.setNote(site, plot == null ? null : "Picked by you");
		identifiedThisVisit.add(site.key());
		dev.ilyapple.ghc.mod.render.LayoutOverlay.clear();
		context.getSource().sendFeedback(prefix().append(plot == null ? site.name() + " follows nothing now." : site.name() + " follows flow plot " + plot + " from its start."));
		return 1;
	}

	private static int debugStream(CommandContext<FabricClientCommandSource> context, boolean on) {
		settings.debugStream = on;
		settings.save();
		DebugStream.apply(settings);
		return 1;
	}

	private static int debugStatus(CommandContext<FabricClientCommandSource> context) {
		var hub = DebugStream.hub();
		context.getSource().sendFeedback(prefix().append(hub.running()
				? "Debug stream on http://127.0.0.1:" + hub.port() + " (/events, /state), " + hub.subscribers() + " listening, writing " + hub.file()
				: "Debug stream off. /ghc debug stream on starts it (this machine only, read-only)."));
		return 1;
	}

	private static int reidentify(CommandContext<FabricClientCommandSource> context) {
		ModState.Site site = currentSite();
		if (site == null) {
			context.getSource().sendFeedback(prefix().append(Component.literal("Stand in the greenhouse first.").withColor(0xFFF87171)));
			return 0;
		}
		identify(Minecraft.getInstance(), site, true);
		return 1;
	}

	private static int swapped(CommandContext<FabricClientCommandSource> context) {
		ModState.Site site = currentSite();
		if (site != null) site.greenhouse().swapDone();
		context.getSource().sendFeedback(prefix().append("Noted."));
		return 1;
	}

	/** The window's Identify button: works out again what the greenhouse the player stands in runs. */
	public static void identifyHere() {
		ModState.Site site = currentSite();
		if (site != null) identify(Minecraft.getInstance(), site, true);
	}

	private static int status(CommandContext<FabricClientCommandSource> context) {
		ModState.Site site = currentSite();
		if (site == null) {
			context.getSource().sendFeedback(prefix().append("Not in a greenhouse (" + (location.inGarden() ? "in the Garden" : "not in the Garden") + ", from the " + location.source() + ")."));
			return 1;
		}
		Greenhouse.Status status = site.greenhouse().status(3);
		ModState.Assignment assignment = state.assignment(site);
		context.getSource().sendFeedback(prefix().append(site.name() + ": " + status.census().plants().size() + " plants"
				+ (assignment == null ? ", no flow" : ", flow plot " + assignment.plotId() + (assignment.auto() ? " (identified)" : ""))
				+ (status.step() == null ? "" : ", step " + status.step().label()) + ". Next tick in "
				+ CompanionScreen.duration(state.clock(site.server()).nextTickMs() - System.currentTimeMillis())));
		return 1;
	}

	/** Stages neither the recorder nor the model book covers: what's left to read with the stage recorder. */
	private static int missing(CommandContext<FabricClientCommandSource> context) {
		LookTable table = state.table();
		int shown = 0;
		for (Plants.Plant plant : state.plants().all()) {
			if (!plant.grows()) continue;
			StageSet left = table.uncovered(plant);
			if (left.isEmpty()) continue;
			shown++;
			context.getSource().sendFeedback(Component.literal(plant.name() + ": ").withColor(0xFFFBBF24)
					.append(Component.literal(left.toString().replace("{", "").replace("}", "")).withColor(0xFFE2E8F0)));
		}
		if (shown == 0) context.getSource().sendFeedback(prefix().append("Every stage of every plant has a look on record."));
		return 1;
	}
}
