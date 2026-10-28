package dev.ilyapple.ghc.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import dev.ilyapple.ghc.core.Diagnosis;
import dev.ilyapple.ghc.core.Plants;

/**
 * Reads two kinds of SkyBlock menus while they are open:
 * <ul>
 * <li>a <b>sack</b> (any container whose title mentions "Sack"): every item named after a crop or mutation with a
 *     {@code Stored: 1,234/20k} lore line sets that item's sack count;</li>
 * <li>the <b>Crop Diagnostics</b> menu, if the player opens it anyway: the reading overrides the plant's stage, origin
 *     (placed when "Uncollectable") and dry/stopped state ({@link Diagnosis}), and its "Next Stage" countdown syncs
 *     the growth clock. The tool is never required.</li>
 * </ul>
 * The sack wording is a guess based on how other SkyBlock mods read sacks; if counts don't show up, the slot text
 * has to be checked in game.
 */
final class MenuReader {
	private static final Pattern STORED = Pattern.compile("(?i)stored\\s*:\\s*([\\d,.]+)\\s*([km]?)");
	private static final Pattern NEXT_STAGE = Pattern.compile("(?i)^\\s*next\\s*stage\\s*:\\s*(.+)$");
	private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([dhms])");

	private final ModState state;
	private @Nullable Screen screen;
	private int stableTicks;
	private String signature = "";
	private boolean done;

	MenuReader(ModState state) {
		this.state = state;
	}

	void tick(Minecraft client) {
		Screen current = dev.ilyapple.ghc.mod.compat.Mc.screen();
		if (!(current instanceof AbstractContainerScreen<?> container)) {
			screen = null;
			return;
		}
		if (current != screen) {
			screen = current;
			stableTicks = 0;
			signature = "";
			done = false;
		}
		String title = Fingerprints.textOf(container.getTitle()).toLowerCase(Locale.ROOT);
		boolean sack = title.contains("sack");
		boolean diagnostics = title.contains("diagnostic");
		if (!sack && !diagnostics) return;

		List<Slot> slots = new ArrayList<>();
		StringBuilder builder = new StringBuilder();
		for (Slot slot : container.getMenu().slots) {
			if (slot.container instanceof Inventory || slot.getItem().isEmpty()) continue;
			slots.add(slot);
			builder.append(slot.index).append(slot.getItem().getHoverName().getString()).append(lore(slot.getItem()));
		}
		// Wait until the menu has stopped filling in; a sack re-reads whenever its page changes
		if (builder.toString().equals(signature)) stableTicks++;
		else {
			signature = builder.toString();
			stableTicks = 0;
			done = false;
		}
		if (done || stableTicks < 3 || slots.isEmpty()) return;
		done = true;

		long now = System.currentTimeMillis();
		if (sack) {
			int read = 0;
			for (Slot slot : slots) {
				Plants.Plant plant = state.plants().byName(Fingerprints.textOf(slot.getItem().getHoverName()));
				if (plant == null) continue;
				for (String line : lore(slot.getItem())) {
					Matcher matcher = STORED.matcher(line);
					if (!matcher.find()) continue;
					state.inventory().setSack(plant.id(), amount(matcher.group(1), matcher.group(2)), now);
					read++;
					break;
				}
			}
			if (read > 0) state.markDirty();
			int sacks = read;
			DebugStream.emit("sacks", event -> event.addProperty("read", sacks));
		}
		if (diagnostics) {
			// The whole reading: plant, stage, placed or not, dry or stopped (it overrides the tracker for that plant)
			String head = null;
			List<String> lines = new ArrayList<>();
			for (Slot slot : slots) {
				if (slot.index == 4) head = Fingerprints.textOf(slot.getItem().getHoverName());
				lines.addAll(lore(slot.getItem()));
			}
			Diagnosis reading = Diagnosis.parse(head, lines);
			// Every line of the menu to the debug stream: it also states the plant's water (the alpha water tests)
			String menuHead = head;
			var target = GreenhouseCompanion.diagnosedCell(now);
			DebugStream.emit("diagnosticsMenu", event -> {
				if (target != null) event.addProperty("cell", target.cell());
				if (target != null) event.addProperty("siteKey", target.siteKey());
				event.addProperty("head", menuHead);
				com.google.gson.JsonArray all = new com.google.gson.JsonArray();
				lines.forEach(all::add);
				event.add("lines", all);
			});
			if (reading != null) GreenhouseCompanion.applyDiagnosis(client, reading, now);
			for (String line : lines) {
				Matcher matcher = NEXT_STAGE.matcher(line);
				if (!matcher.find()) continue;
				long remaining = durationMs(matcher.group(1));
				if (remaining <= 0) continue;
				String server = GardenGrid.server(client);
				int ticks = state.clock(server).onCountdown(now, remaining);
				DebugStream.emit("countdown", event -> {
					event.addProperty("nextStageInMs", remaining);
					event.addProperty("ticksApplied", ticks);
				});
				if (ticks > 0) GreenhouseCompanion.applyTicks(server, ticks);
				state.markDirty();
				return;
			}
		}
	}

	private static List<String> lore(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return List.of();
		return lore.lines().stream().map(Fingerprints::textOf).toList();
	}

	static int amount(String number, String suffix) {
		double value = Double.parseDouble(number.replace(",", ""));
		if (suffix.equalsIgnoreCase("k")) value *= 1_000;
		if (suffix.equalsIgnoreCase("m")) value *= 1_000_000;
		return (int) Math.round(value);
	}

	static long durationMs(String text) {
		Matcher parts = DURATION.matcher(text);
		long total = 0;
		while (parts.find()) {
			long value = Long.parseLong(parts.group(1));
			total += switch (parts.group(2)) {
				case "d" -> value * 86_400_000L;
				case "h" -> value * 3_600_000L;
				case "m" -> value * 60_000L;
				default -> value * 1000L;
			};
		}
		return total;
	}
}
