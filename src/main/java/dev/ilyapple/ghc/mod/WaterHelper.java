package dev.ilyapple.ghc.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;


import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import dev.ilyapple.ghc.core.Water;
import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.mod.ui.Theme;

/**
 * Crops' water (after MagicAddons' {@code WaterIndicator} and {@code GreenhouseWatering}): what Highlight Crops That Need
 * Water outlines ({@link #need}, drawn by {@link dev.ilyapple.ghc.mod.render.Highlight}) and Water Above Crops writes.
 * <ul>
 * <li>Each crop's water is kept like its stage ({@link Water}): known from a new crop (0), the bars Hypixel shows while
 *     a Hydro Can or Aquamaster is held (and for {@link #WINDOW_MS} after one is used), or Crop Diagnostics, then 20 less
 *     for every stage it grows (times its effects).</li>
 * <li>"Before you're back" is the harvest interval in stages: 14 for once a day, 7 for twice, 5 for three times, 4 for
 *     four. A crop needs water when it would reach −100 within that many stages and before it is fully grown (yellow);
 *     one at −100 has stopped growing (red); one whose water isn't known yet counts as needing it (MagicAddons does the
 *     same).</li>
 * </ul>
 */
public final class WaterHelper {
	/** A label's colour while the crop has water enough. */
	public static final int COLOR = 0xFF22D3EE;
	private static final long WINDOW_MS = 5_000;
	private static final Set<String> CANS = Set.of("HYDRO_CAN_1000", "HYDRO_CAN_TURBO_2000", "HYDRO_CAN_ULTRA_3000", "AQUAMASTER_X", "AQUAMASTER_HYDROMAX");

	private static final Set<String> OTHER_BARS = Set.of("fleshtrap", "thunderling");

	private static long windowUntil;
	private static long tick;
	private static volatile List<JellybeanView.Label> labels = List.of();

	private WaterHelper() {}

	/** A right click with something in hand: a watering can opens the window for reading bars. */
	static void used(ItemStack held) {
		if (isCan(held)) windowUntil = System.currentTimeMillis() + WINDOW_MS;
	}

	static void tick(Minecraft client) {
		tick++;
		Settings settings = GreenhouseCompanion.settings();
		if (!settings.highlightWater && !settings.waterAboveCrops || client.level == null) {
			labels = List.of();
			return;
		}
		ModState.Site site = GreenhouseCompanion.currentSite();
		BlockPos corner = GreenhouseCompanion.watcherCorner();
		// Hypixel shows water bars while a Hydro Can is held (the wiki) and just after one is used (MagicAddons)
		boolean bars = System.currentTimeMillis() < windowUntil || client.player != null && isCan(client.player.getMainHandItem());
		if (site != null && corner != null && bars && tick % 4 == 0) readBars(client, site, corner);
		if (tick % 20 == 0) refresh();
	}

	private static boolean isCan(ItemStack held) {
		if (held.isEmpty()) return false;
		String id = DropRecorder.skyblockId(held);
		String name = Fingerprints.textOf(held.getHoverName()).toLowerCase(Locale.ROOT);
		return id != null && CANS.contains(id) || name.contains("hydro can") || name.contains("hydrocan") || name.contains("aquamaster") || name.contains("watering can");
	}

	/** The last bar logged per greenhouse cell, so the debug stream gets each new reading once. */
	private static final java.util.Map<String, String> logged = new java.util.HashMap<>();

	/** Reads every water bar in the greenhouse: blue notches are water, red ones below zero. */
	private static void readBars(Minecraft client, ModState.Site site, BlockPos corner) {
		Census census = site.greenhouse().context().census();
		boolean changed = false;
		for (Entity entity : client.level.getEntities((Entity) null, GardenGrid.scanBox(corner), entity -> entity instanceof ArmorStand && entity.getCustomName() != null)) {
			Bar bar = bar(entity.getCustomName());
			if (bar == null) continue;
			int cell = GardenGrid.cellAt(corner, entity.position());
			for (Census.Plant plant : census.plants()) {
				if (!plant.cells().contains(cell)) continue;
				// Every bar goes to the debug stream (the alpha water tests read them), used or not
				String key = site.key() + "/" + cell;
				String text = bar.filled() + "/" + bar.negative() + "/" + bar.total();
				if (!text.equals(logged.put(key, text))) {
					DebugStream.emit("waterBar", event -> {
						DebugStream.site(event, site);
						event.addProperty("cell", cell);
						event.addProperty("plant", plant.id());
						event.addProperty("origin", plant.origin().name());
						DebugStream.stages(event, plant.stages());
						event.addProperty("blue", bar.filled());
						event.addProperty("red", bar.negative());
						event.addProperty("notches", bar.total());
						event.addProperty("waterLo", bar.lo());
						event.addProperty("waterHi", bar.hi());
						event.addProperty("raw", entity.getCustomName().getString());
					});
				}
				// Their hunger and charge bars look like water bars
				if (!Water.consumes(plant.kind(), plant.origin()) || OTHER_BARS.contains(plant.id())) break;
				// A bar shows whole notches: take the low end, the safe side
				site.greenhouse().water().read(plant, bar.lo(), stageOf(plant));
				changed = true;
				break;
			}
		}
		if (changed) GreenhouseCompanion.state().markDirty();
	}

	/** A water bar: {@code filled} blue notches, {@code negative} red ones, of {@code total}; the water it means. */
	record Bar(int filled, int negative, int total, double lo, double hi) {}

	/**
	 * A water bar's range: {@code n} of {@code total} notches blue means n/total of 100 up to one notch more; red ones
	 * count down from 0. {@code null} for any other name (other bars have other colours).
	 */
	static @Nullable Bar bar(Component name) {
		int[] counts = new int[4];
		int blue = ChatFormatting.BLUE.getColor();
		int red = ChatFormatting.RED.getColor();
		Set<Integer> empty = Set.of(ChatFormatting.WHITE.getColor(), ChatFormatting.GRAY.getColor(), ChatFormatting.DARK_GRAY.getColor());
		name.visit((Style style, String text) -> {
			int notches = 0;
			for (char c : text.toCharArray()) if (c == '|') notches++;
			if (notches == 0) return Optional.empty();
			TextColor color = style.getColor();
			int value = color == null ? ChatFormatting.WHITE.getColor() : color.getValue();
			if (value == blue) counts[0] += notches;
			else if (value == red) counts[1] += notches;
			else if (empty.contains(value)) counts[2] += notches;
			else counts[3] += notches;
			return Optional.empty();
		}, Style.EMPTY);
		int total = counts[0] + counts[1] + counts[2] + counts[3];
		if (total < 3 || counts[3] > 0) return null;
		double notch = 100.0 / total;
		if (counts[1] > 0) return new Bar(counts[0], counts[1], total, Math.max(-100, -counts[1] * notch - notch), -counts[1] * notch);
		return new Bar(counts[0], counts[1], total, counts[0] * notch, Math.min(100, counts[0] * notch + notch));
	}

	// The overlay

	/** The stage a crop's water is counted at: the highest it can be, the safe side. */
	private static int stageOf(Census.Plant plant) {
		return plant.stages().isEmpty() ? 1 : plant.stages().hi();
	}

	/** A crop's water now, or {@code null} when not known. */
	public static @Nullable Double water(ModState.Site site, Census.Plant plant, double factor) {
		return site.greenhouse().water().water(plant, stageOf(plant), factor);
	}

	/** What {@code plant}'s water asks for, {@code factors} from {@link Water#factors}, the harvest interval from the settings. */
	public static Water.Need need(ModState.Site site, Census.Plant plant, java.util.Map<Census.Plant, Double> factors) {
		if (plant.dead() || !Water.consumes(plant.kind(), plant.origin()) || !plant.kind().grows()) return Water.Need.NONE;
		double factor = factors.getOrDefault(plant, 1.0);
		int stage = plant.stages().isEmpty() ? 1 : plant.stages().lo();
		return Water.need(water(site, plant, factor), stage, plant.kind().readyStage(), factor, Water.stagesPerVisit(GreenhouseCompanion.settings().waterVisitsPerDay));
	}

	/** The colour for a need: the highlight's yellow or red, cyan while there's enough. */
	public static int color(Water.Need need) {
		Settings settings = GreenhouseCompanion.settings();
		return switch (need) {
			case SOON -> settings.needsWaterColor;
			case DRY -> settings.driedColor;
			case NONE -> COLOR;
		};
	}

	/** Rebuilds the water labels (Water Above Crops) for the greenhouse you stand in. */
	public static void refresh() {
		ModState.Site site = GreenhouseCompanion.currentSite();
		BlockPos corner = GreenhouseCompanion.watcherCorner();
		Settings settings = GreenhouseCompanion.settings();
		if (site == null || corner == null || !settings.waterAboveCrops) {
			labels = List.of();
			return;
		}
		List<JellybeanView.Label> text = new ArrayList<>();
		List<Census.Plant> plants = site.greenhouse().context().census().plants();
		var factors = site.greenhouse().water().factors(plants);
		java.util.Set<List<Integer>> done = new java.util.HashSet<>();
		for (Census.Plant plant : plants) {
			if (plant.dead() || !Water.consumes(plant.kind(), plant.origin()) || !plant.kind().grows() || !done.add(plant.cells())) continue;
			int stage = plant.stages().isEmpty() ? 1 : plant.stages().lo();
			if (stage >= plant.kind().readyStage()) continue;
			Double water = water(site, plant, factors.getOrDefault(plant, 1.0));
			Water.Need need = need(site, plant, factors);
			int anchor = plant.cells().stream().mapToInt(Integer::intValue).min().orElse(0);
			int size = plant.kind().size();
			double x = corner.getX() + GardenGrid.cellX(anchor);
			double z = corner.getZ() + GardenGrid.cellZ(anchor);
			String value = water == null ? "?" : Long.toString(Math.round(water));
			text.add(new JellybeanView.Label(new Vec3(x + size / 2.0, GardenGrid.FLOOR_Y + 1.25, z + size / 2.0), "☔ " + value,
					need == Water.Need.NONE ? Theme.alpha(COLOR, 0xB0) : color(need)));
		}
		labels = List.copyOf(text);
	}

	public static List<JellybeanView.Label> labels() {
		return labels;
	}
}
