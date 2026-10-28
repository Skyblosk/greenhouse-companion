package dev.ilyapple.ghc.mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;

import dev.ilyapple.ghc.core.GrowthClock;
import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.replay.Replay;
import dev.ilyapple.ghc.mod.ui.CompanionScreen;

/**
 * Development only, off unless {@code -Dghc.demo=<recorder folder>} is set (the {@code runDemo} task does): replays
 * a real recording into one greenhouse, follows a flow file with it, opens the window on the title screen and saves
 * a screenshot of each page to {@code run/screenshots}. A dev client can't join Hypixel, so this is how the window
 * gets looked at outside the game.
 */
final class DevDemo {
	private static final String[] SHOTS = {"overview", "flow-live", "flow-layout", "flows", "inventory", "stages", "settings", "leaderboard", "profiles", "leaderboard-flow",
			"profit", "inventory-typed", "flows-links"};
	private static int frames;
	private static int shot;
	private static int linger = -1;
	private static ModState.Site demoSite;
	private static boolean opened;

	private DevDemo() {}

	static void install(ModState state) {
		String folder = System.getProperty("ghc.demo");
		if (folder == null) return;
		String flowFile = System.getProperty("ghc.demo.flow");
		try {
			Replay replay = Replay.load(Path.of(folder), state.table(), GrowthClock.DEFAULT_PERIOD_MS);
			Map<String, StageTracker> trackers = replay.finalTrackers(state.table());
			// The greenhouse with the most Jellybeans in the recording, unless one is named
			String siteKey = System.getProperty("ghc.demo.site");
			if (siteKey == null) {
				long best = -1;
				for (Map.Entry<String, StageTracker> entry : trackers.entrySet()) {
					long beans = entry.getValue().all().stream().filter(tracked -> tracked.plant().id().equals("magic_jellybean")).count();
					if (beans > best) {
						best = beans;
						siteKey = entry.getKey();
					}
				}
			}
			StageTracker tracker = trackers.get(siteKey);
			String server = siteKey.substring(0, siteKey.indexOf('|'));
			String[] xz = siteKey.substring(siteKey.indexOf('|') + 1).split(",");
			ModState.Site site = state.site(server, new BlockPos(Integer.parseInt(xz[0]), GardenGrid.FLOOR_Y, Integer.parseInt(xz[1])));
			state.markGreenhouse(site.key());
			demoSite = site;
			if (tracker != null) site.greenhouse().tracker().load(tracker.toJson());
			if (flowFile != null) state.importFlows(Files.readString(Path.of(flowFile)), ModState.TEST_FLOW_NAME);
			// A second profile for the dropdown's screenshot
			if (state.profiles().stream().noneMatch(profile -> profile.name().equals("Ironman"))) state.createProfile("Ironman");
			// The same identification as walking into the greenhouse
			GreenhouseCompanion.identify(Minecraft.getInstance(), site, true);
			GreenhouseCompanion.LOGGER.info("[GHC] Demo: {} → {}", site.name(), state.note(site));
			long now = System.currentTimeMillis();
			state.clock(server).onCountdown(now, 41 * 60_000L);
			state.inventory().setSack("magic_jellybean", 1_284, now - 6 * 60_000L);
			state.inventory().setSack("shadevine", 3_310, now - 6 * 60_000L);
			state.inventory().setSack("zombud", 4, now - 6 * 60_000L);
			state.inventory().setSack("cindershade", 9, now - 6 * 60_000L);
			state.inventory().adjust("magic_jellybean", 40);
		} catch (Exception e) {
			GreenhouseCompanion.LOGGER.error("[GHC] Demo setup failed", e);
		}
		ClientTickEvents.END_CLIENT_TICK.register(DevDemo::tick);
	}

	/** Made-up harvests for the Drops page's screenshot (this session's book only), and water that's had a few ticks. */
	private static void seedDropsAndWater() {
		dev.ilyapple.ghc.core.drops.DropBook book = GreenhouseCompanion.drops().session();
		String[][] harvests = {{"shadevine", "180"}, {"ashwreath", "64"}, {"magic_jellybean", "16"}, {"zombud", "22"}, {"wheat", "420"}, {"wild_rose", "96"}};
		for (String[] harvest : harvests) {
			for (int i = 0; i < Integer.parseInt(harvest[1]); i++) book.broke(harvest[0], dev.ilyapple.ghc.core.drops.DropBook.Kind.HARVESTED);
		}
		book.broke("magic_jellybean", dev.ilyapple.ghc.core.drops.DropBook.Kind.PICKED_UP);
		book.drop("magic_jellybean", dev.ilyapple.ghc.core.drops.DropBook.Kind.PICKED_UP, "MAGIC_JELLYBEAN", 1);
		book.drop("shadevine", dev.ilyapple.ghc.core.drops.DropBook.Kind.HARVESTED, "SUGAR_CANE", 180L * 1_440);
		book.drop("ashwreath", dev.ilyapple.ghc.core.drops.DropBook.Kind.HARVESTED, "NETHER_STALK", 64L * 1_800);
		book.drop("magic_jellybean", dev.ilyapple.ghc.core.drops.DropBook.Kind.HARVESTED, "MOONFLOWER", 16L * 6_000);
		book.drop("magic_jellybean", dev.ilyapple.ghc.core.drops.DropBook.Kind.HARVESTED, "DOUBLE_PLANT", 16L * 6_000);
		book.drop("magic_jellybean", dev.ilyapple.ghc.core.drops.DropBook.Kind.HARVESTED, "FERMENTO", 1);
		book.drop("zombud", dev.ilyapple.ghc.core.drops.DropBook.Kind.HARVESTED, "ROTTEN_FLESH", 22L * 640);
		book.drop("wheat", dev.ilyapple.ghc.core.drops.DropBook.Kind.HARVESTED, "WHEAT", 420L * 90);
		book.drop("wheat", dev.ilyapple.ghc.core.drops.DropBook.Kind.HARVESTED, "HELIANTHUS", 2);
		book.drop("wheat", dev.ilyapple.ghc.core.drops.DropBook.Kind.HARVESTED, "FERMENTO", 1);
		book.mixed("FERMENTO", 1);
		if (demoSite != null) {
			// Watered to 100 a few stages ago, a different number for each crop
			for (dev.ilyapple.ghc.core.flow.Census.Plant plant : demoSite.greenhouse().context().census().plants()) {
				if (plant.stages().isEmpty() || !dev.ilyapple.ghc.core.Water.consumes(plant.kind(), plant.origin())) continue;
				int anchor = plant.cells().getFirst();
				demoSite.greenhouse().water().read(plant, 100, Math.max(1, plant.stages().hi() - anchor % 9));
			}
		}
	}

	private static void tick(Minecraft client) {
		// The first menu after loading: the title screen, or the accessibility screen on a fresh run folder
		if (!opened && client.getOverlay() == null && client.screen != null && !(client.screen instanceof CompanionScreen)) {
			opened = true;
			// With the debug stream on, identify again now that it is listening, so the run shows a live event
			if (DebugStream.on() && demoSite != null) GreenhouseCompanion.identify(client, demoSite, true);
			seedDropsAndWater();
			client.setScreen(new CompanionScreen(client.screen instanceof TitleScreen ? client.screen : new TitleScreen(), CompanionScreen.Page.OVERVIEW));
			frames = 0;
			return;
		}
		if (!(client.screen instanceof CompanionScreen screen)) return;
		frames++;
		if (frames % 40 != 0) return;
		if (shot < SHOTS.length) {
			Screenshot.grab(client.gameDirectory, "ghc-" + SHOTS[shot] + ".png", client.getMainRenderTarget(), 1, message -> {});
			shot++;
			if (shot < SHOTS.length) screen.demo(shot);
		} else if (Boolean.getBoolean("ghc.demo.quit")) {
			// -Dghc.demo.linger=N keeps the client up N more seconds, e.g. to read the debug stream
			if (linger < 0) linger = Integer.getInteger("ghc.demo.linger", 0) * 20 / 40;
			if (linger-- <= 0) client.stop();
		}
	}
}
