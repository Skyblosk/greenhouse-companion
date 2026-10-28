package dev.ilyapple.ghc.mod.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.drops.DropBook;
import dev.ilyapple.ghc.core.drops.Estimate;
import dev.ilyapple.ghc.core.drops.Items;
import dev.ilyapple.ghc.core.leaderboard.Leaderboard;
import dev.ilyapple.ghc.mod.DropRecorder;
import dev.ilyapple.ghc.mod.GreenhouseCompanion;
import dev.ilyapple.ghc.mod.Prices;
import dev.ilyapple.ghc.mod.Settings;

/**
 * The drop recorder's view, in the window's style ({@link Theme}): mutations harvested, Fermento and Helianthus per
 * rarity (with the most each rate could be when none dropped), then every plant's harvests and drops, or every drop.
 * It is drawn in two places: the window's Drops page, and a panel on inventory, container and chat screens in the
 * Garden, which a Helianthus icon in a corner opens and closes (GreenEssentials draws its tracker on the same screens).
 */
public final class DropsPanel {
	/** Where a view puts its click areas: the window's hotspots, or the panel's own on other screens. */
	public interface Clicks {
		void add(int x, int y, int w, int h, Runnable action);
	}

	private record Area(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private static final int ICON = 20;
	private static final int MARGIN = 4;
	private static final String[] RARITIES = {"crop", "common", "uncommon", "rare", "epic", "legendary"};

	private static final List<Area> areas = new ArrayList<>();
	private static @Nullable Screen drawnOn;
	private static int panelX;
	private static int panelY;
	private static int panelW;
	private static int panelH;
	private static boolean open;
	private static boolean allTime;
	private static boolean byItem;
	/** Bazaar by default: the input mutations subtracted come from the Normal (Bazaar) profile. */
	private static Prices.Source priceSource = Prices.Source.BAZAAR;
	private static int scroll;
	private static long resetArmedMs;
	private static boolean resetArmedAll;

	private DropsPanel() {}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> || screen instanceof ChatScreen)) return;
			ScreenEvents.afterExtract(screen).register((active, g, mouseX, mouseY, delta) -> overlay(active, g, mouseX, mouseY));
			ScreenMouseEvents.allowMouseClick(screen).register((active, event) -> !click(active, event.x(), event.y(), event.button()));
			ScreenMouseEvents.allowMouseScroll(screen).register((active, mouseX, mouseY, horizontal, vertical) -> !scrolled(active, mouseX, mouseY, vertical));
			ScreenEvents.remove(screen).register(active -> {
				if (drawnOn == active) {
					drawnOn = null;
					areas.clear();
				}
			});
		});
	}

	private static boolean visible() {
		Settings settings = GreenhouseCompanion.settings();
		Minecraft client = Minecraft.getInstance();
		return settings.recordDrops && settings.dropsIcon && !client.options.hideGui && client.player != null && GreenhouseCompanion.location().inGarden();
	}

	// On other screens

	private static void overlay(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
		areas.clear();
		drawnOn = null;
		if (!visible()) return;
		drawnOn = screen;
		g.nextStratum();
		Font font = Minecraft.getInstance().font;
		Settings.Corner corner = GreenhouseCompanion.settings().dropsIconCorner;
		boolean right = corner == Settings.Corner.TOP_RIGHT || corner == Settings.Corner.BOTTOM_RIGHT;
		boolean bottom = corner == Settings.Corner.BOTTOM_LEFT || corner == Settings.Corner.BOTTOM_RIGHT;
		int ix = right ? g.guiWidth() - MARGIN - ICON : MARGIN;
		// Chat's input line sits at the bottom: keep clear of it
		int iy = bottom ? g.guiHeight() - MARGIN - ICON - (screen instanceof ChatScreen ? 16 : 0) : MARGIN;
		boolean over = mouseX >= ix && mouseX < ix + ICON && mouseY >= iy && mouseY < iy + ICON;
		Theme.card(g, ix, iy, ICON, ICON, open || over ? Theme.CARD_HOVER : 0xE00F1624, open ? Theme.alpha(Theme.accent(), 0xCC) : Theme.BORDER_STRONG);
		Theme.cropIcon(g, "helianthus", ix + 2, iy + 2, 16, 255);
		areas.add(new Area(ix, iy, ICON, ICON, () -> {
			open = !open;
			scroll = 0;
		}));
		if (open) {
			panelW = Math.min(330, g.guiWidth() - 2 * MARGIN);
			panelH = Math.min(320, g.guiHeight() - 3 * MARGIN - ICON - (screen instanceof ChatScreen ? 16 : 0));
			panelX = right ? g.guiWidth() - MARGIN - panelW : MARGIN;
			panelY = bottom ? iy - MARGIN - panelH : iy + ICON + MARGIN;
			Theme.card(g, panelX, panelY, panelW, panelH, 0xF20F1624, Theme.BORDER_STRONG);
			g.fill(panelX + 1, panelY + 1, panelX + 3, panelY + panelH - 1, Theme.accent());
			g.text(font, "Profit", panelX + 8, panelY + 6, Theme.TEXT_BRIGHT, false);
			render(g, font, panelX + 8, panelY + 18, panelW - 14, panelH - 22, mouseX, mouseY, (x, y, w, h, action) -> areas.add(new Area(x, y, w, h, action)), false);
		} else if (over) {
			DropBook book = GreenhouseCompanion.drops().session();
			g.setComponentTooltipForNextFrame(font, List.of(Component.literal("Profit").withColor(Theme.TEXT_BRIGHT),
					Component.literal(book.harvested(DropsPanel::isMutation) + " mutations harvested this session").withColor(Theme.MUTED),
					Component.literal("Click to open").withColor(Theme.accent())), mouseX, mouseY);
		}
	}

	private static boolean click(Screen screen, double mouseX, double mouseY, int button) {
		if (screen != drawnOn || button != 0) return false;
		for (int i = areas.size() - 1; i >= 0; i--) {
			if (!areas.get(i).contains(mouseX, mouseY)) continue;
			areas.get(i).action().run();
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			return true;
		}
		// The open panel takes its clicks, so nothing underneath (an inventory slot) gets them
		return open && insidePanel(mouseX, mouseY);
	}

	private static boolean scrolled(Screen screen, double mouseX, double mouseY, double vertical) {
		if (screen != drawnOn || !open || !insidePanel(mouseX, mouseY)) return false;
		scroll = Math.max(0, scroll + (vertical > 0 ? -16 : 16));
		return true;
	}

	private static boolean insidePanel(double mouseX, double mouseY) {
		return mouseX >= panelX && mouseX < panelX + panelW && mouseY >= panelY && mouseY < panelY + panelH;
	}

	/** The window's Drops page scrolls the same list. */
	public static void scroll(int delta) {
		scroll = Math.max(0, scroll + delta * 16);
	}

	// The view

	/**
	 * Draws the recorder into {@code x, y, w, h}. {@code inWindow}: on the window's Drops page (no close or open
	 * buttons, more room).
	 */
	public static void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, int mouseX, int mouseY, Clicks clicks, boolean inWindow) {
		DropRecorder recorder = GreenhouseCompanion.drops();
		DropBook book = allTime ? recorder.allTime() : recorder.session();
		int bottom = y + h;

		// Which book, which view; reset, and on other screens open the window or close
		int px = x;
		px += pill(g, font, mouseX, mouseY, "Session", px, y, !allTime, clicks, () -> {
			allTime = false;
			scroll = 0;
		});
		px += pill(g, font, mouseX, mouseY, "All time", px, y, allTime, clicks, () -> {
			allTime = true;
			scroll = 0;
		});
		px += 6;
		px += pill(g, font, mouseX, mouseY, "By plant", px, y, !byItem, clicks, () -> {
			byItem = false;
			scroll = 0;
		});
		pill(g, font, mouseX, mouseY, "All drops", px, y, byItem, clicks, () -> {
			byItem = true;
			scroll = 0;
		});
		int rx = x + w;
		if (!inWindow) {
			rx -= smallButton(g, font, mouseX, mouseY, "✕", rx - smallWidth(font, "✕"), y + 2, Theme.DIM, clicks, () -> open = false) + 3;
			rx -= smallButton(g, font, mouseX, mouseY, "Open ↗", rx - smallWidth(font, "Open ↗"), y + 2, Theme.INFO, clicks,
					() -> Minecraft.getInstance().setScreen(new CompanionScreen(null, CompanionScreen.Page.DROPS))) + 3;
		}
		boolean armed = resetArmedAll == allTime && System.currentTimeMillis() - resetArmedMs < 4_000;
		String resetLabel = armed ? "Click again: reset " + (allTime ? "all time" : "session") : "Reset";
		smallButton(g, font, mouseX, mouseY, resetLabel, rx - smallWidth(font, resetLabel), y + 2, Theme.DANGER, clicks, () -> {
			if (resetArmedAll == allTime && System.currentTimeMillis() - resetArmedMs < 4_000) {
				recorder.reset(allTime);
				resetArmedMs = 0;
			} else {
				resetArmedMs = System.currentTimeMillis();
				resetArmedAll = allTime;
			}
		});
		int cy = y + 17;

		// Prices: which ones, and where they came from
		Prices.refresh();
		Theme.small(g, font, "PRICES", x, cy + 4, Theme.MUTED);
		int sx = x + font.width("PRICES") / 2 + 6;
		sx += smallPill(g, font, mouseX, mouseY, "Bazaar", sx, cy + 1, priceSource == Prices.Source.BAZAAR, clicks, () -> priceSource = Prices.Source.BAZAAR);
		sx += smallPill(g, font, mouseX, mouseY, "NPC", sx, cy + 1, priceSource == Prices.Source.NPC, clicks, () -> priceSource = Prices.Source.NPC);
		Theme.small(g, font, Theme.fit(font, Prices.status() + " · inputs: Normal profile, harvest every " + harvestCycles() + " cycles", (x + w - sx - 2) * 2),
				sx + 2, cy + 4, Theme.FAINT);
		cy += 14;

		// Tiles: harvests, estimated value, Fermento, Helianthus
		long mutations = book.harvested(DropsPanel::isMutation);
		long base = book.harvested(id -> !isMutation(id));
		Estimate.Value value = Estimate.of(book, DropsPanel::inputsPerHarvest, DropsPanel::price);
		int tileW = (w - 12) / 4;
		tile(g, font, x, cy, tileW, "MUTATIONS HARVESTED", Long.toString(mutations),
				base + " base crops · since " + since(book.startedMs()), Theme.TEXT_BRIGHT);
		tile(g, font, x + tileW + 4, cy, tileW, "ESTIMATED VALUE", coins(value.net()),
				coins(value.gross()) + " drops − " + coins(value.inputs()) + " inputs" + (value.unpriced().isEmpty() ? "" : " · " + value.unpriced().size() + " unpriced"),
				value.net() >= 0 ? Theme.SUCCESS : Theme.DANGER);
		rareTile(g, font, x + 2 * (tileW + 4), cy, tileW, "FERMENTO", book, Items.FERMENTO, mutations);
		rareTile(g, font, x + 3 * (tileW + 4), cy, tileW, "HELIANTHUS", book, Items.HELIANTHUS, mutations);
		cy += 40;

		g.enableScissor(x, cy, x + w, bottom);
		int top = cy;
		int ly = cy - scroll;
		ly = rarityTable(g, font, x, ly, w, book);
		ly = byItem ? itemList(g, font, x, ly + 6, w, book) : plantList(g, font, x, ly + 6, w, book);
		if (book.isEmpty()) {
			Theme.small(g, font, Theme.fit(font, "Harvest in a greenhouse: every harvest and what dropped with it is recorded here.", w * 2), x, ly + 4, Theme.MUTED);
			ly += 12;
		}
		g.disableScissor();
		int total = ly + scroll - top;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, total - (bottom - top))));
	}

	/** Fermento and Helianthus per 100 harvests, by rarity, so a rarity that never drops them stands out. */
	private static int rarityTable(GuiGraphicsExtractor g, Font font, int x, int y, int w, DropBook book) {
		Theme.heading(g, font, "Per rarity", x, y + 2, w);
		y += 10;
		int c1 = x + w * 34 / 100;
		int c2 = x + w * 52 / 100;
		int c3 = x + w * 76 / 100;
		Theme.small(g, font, "HARVESTED", c1, y, Theme.FAINT);
		Theme.small(g, font, "FERMENTO", c2, y, Theme.FAINT);
		Theme.small(g, font, "HELIANTHUS", c3, y, Theme.FAINT);
		y += 7;
		for (String rarity : RARITIES) {
			Predicate<String> of = id -> rarity.equals(rarityOf(id));
			long harvested = book.harvested(of);
			int color = harvested == 0 ? Theme.FAINT : Theme.TEXT;
			Theme.small(g, font, rarity.equals("crop") ? "Base crops" : capital(rarity), x, y + 1, color);
			Theme.small(g, font, Long.toString(harvested), c1, y + 1, color);
			Theme.small(g, font, rate(book.dropped(Items.FERMENTO, of), harvested), c2, y + 1, rateColor(book.dropped(Items.FERMENTO, of), harvested));
			Theme.small(g, font, rate(book.dropped(Items.HELIANTHUS, of), harvested), c3, y + 1, rateColor(book.dropped(Items.HELIANTHUS, of), harvested));
			y += 8;
		}
		long mixedRare = book.mixed().getOrDefault(Items.FERMENTO, 0L) + book.mixed().getOrDefault(Items.HELIANTHUS, 0L);
		long unmatchedRare = book.unmatched().getOrDefault(Items.FERMENTO, 0L) + book.unmatched().getOrDefault(Items.HELIANTHUS, 0L);
		if (mixedRare + unmatchedRare > 0) {
			Theme.small(g, font, Theme.fit(font, "Not in the table: " + mixedRare + " came while harvesting two kinds at once, " + unmatchedRare
					+ " with no harvest near that can drop it (see All drops)", w * 2), x, y + 1, Theme.WARNING);
			y += 8;
		}
		return y;
	}

	private static int plantList(GuiGraphicsExtractor g, Font font, int x, int y, int w, DropBook book) {
		Theme.heading(g, font, "By plant", x, y + 2, w);
		y += 12;
		List<Map.Entry<String, DropBook.Line>> lines = new ArrayList<>(book.lines().entrySet());
		lines.sort(Comparator.comparing((Map.Entry<String, DropBook.Line> entry) -> !isMutation(entry.getKey()))
				.thenComparing(entry -> -entry.getValue().harvested()));
		for (Map.Entry<String, DropBook.Line> entry : lines) {
			String id = entry.getKey();
			DropBook.Line line = entry.getValue();
			Map<String, Double> inputs = inputsPerHarvest(id);
			Estimate.Value value = Estimate.of(line, inputs, DropsPanel::price);
			// Every item its harvests dropped, biggest first, then what its harvests used up
			List<Map.Entry<String, Long>> drops = new ArrayList<>(line.drops().entrySet());
			drops.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
			List<String> parts = new ArrayList<>();
			for (Map.Entry<String, Long> drop : drops) parts.add(Items.name(drop.getKey()) + " ×" + number(drop.getValue()));
			String dropText = parts.isEmpty() ? "No drops recorded" : String.join(" · ", parts);
			List<String> used = new ArrayList<>();
			inputs.forEach((input, each) -> used.add(String.format(Locale.ROOT, "%.3f", each) + " " + plantName(input)));
			String inputText = used.isEmpty() ? "" : "Inputs per harvest (Normal profile): " + String.join(", ", used) + " = −" + coins(value.inputs());
			List<net.minecraft.util.FormattedCharSequence> dropLines = font.split(Component.literal(dropText), Math.max(20, (w - 29) * 2));
			int h = 26 + dropLines.size() * 5 + (inputText.isEmpty() ? 0 : 5);

			Theme.card(g, x, y, w, h, Theme.CARD, Theme.BORDER);
			Theme.cropIcon(g, id, x + 3, y + 4, 16, 255);
			g.text(font, Theme.fit(font, plantName(id), w * 40 / 100), x + 23, y + 3, Theme.TEXT_BRIGHT, false);
			String counts = line.harvested() + " harvested";
			Theme.small(g, font, Theme.fit(font, counts, w * 80 / 100), x + 23, y + 13, Theme.MUTED);
			int rx = x + w - 6;
			for (String rare : new String[] {Items.HELIANTHUS, Items.FERMENTO}) {
				long count = line.drop(rare);
				String text = count + "";
				rx -= font.width(text);
				g.text(font, text, rx, y + 3, count > 0 ? Theme.WARNING : Theme.FAINT, false);
				rx -= 11;
				Theme.cropIcon(g, rare.toLowerCase(Locale.ROOT), rx, y + 2, 10, count > 0 ? 255 : 110);
				rx -= 6;
			}
			String worth = "≈ " + coins(value.net()) + (line.harvested() > 0 ? " · " + coins(value.net() / line.harvested()) + " each" : "");
			Theme.small(g, font, worth, x + w - 6 - font.width(worth) / 2, y + 14, value.net() >= 0 ? Theme.SUCCESS : Theme.DANGER);
			int ty = y + 21;
			for (net.minecraft.util.FormattedCharSequence dropLine : dropLines) {
				g.pose().pushMatrix();
				g.pose().translate(x + 23, ty);
				g.pose().scale(0.5f, 0.5f);
				g.text(font, dropLine, 0, 0, parts.isEmpty() ? Theme.FAINT : Theme.DIM, false);
				g.pose().popMatrix();
				ty += 5;
			}
			if (!inputText.isEmpty()) Theme.small(g, font, Theme.fit(font, inputText, (w - 29) * 2), x + 23, ty, Theme.MUTED);
			y += h + 2;
		}
		return y;
	}

	private static int itemList(GuiGraphicsExtractor g, Font font, int x, int y, int w, DropBook book) {
		Theme.heading(g, font, "All drops", x, y + 2, w);
		y += 10;
		Map<String, Long> all = book.allDrops();
		List<Map.Entry<String, Long>> items = new ArrayList<>(all.entrySet());
		items.sort(Comparator.comparing((Map.Entry<String, Long> entry) -> !Items.RARE.contains(entry.getKey())).thenComparing(Map.Entry.comparingByValue(Comparator.reverseOrder())));
		int c1 = x + w * 42 / 100;
		int c2 = x + w * 58 / 100;
		int c3 = x + w * 76 / 100;
		Theme.small(g, font, "TOTAL", c1, y, Theme.FAINT);
		Theme.small(g, font, "VALUE", c2, y, Theme.FAINT);
		Theme.small(g, font, "NOT PUT DOWN TO ONE PLANT", c3, y, Theme.FAINT);
		y += 8;
		for (Map.Entry<String, Long> entry : items) {
			String id = entry.getKey();
			String icon = iconFor(id);
			if (icon != null) Theme.cropIcon(g, icon, x, y, 9, 255);
			g.text(font, Theme.fit(font, Items.name(id), c1 - x - 16), x + 12, y + 1, Items.RARE.contains(id) ? Theme.WARNING : Theme.TEXT, false);
			g.text(font, number(entry.getValue()), c1, y + 1, Theme.TEXT_BRIGHT, false);
			Double each = price(id);
			g.text(font, each == null ? "?" : coins(each * entry.getValue()), c2, y + 1, each == null ? Theme.FAINT : Theme.SUCCESS, false);
			long mixed = book.mixed().getOrDefault(id, 0L);
			long unmatched = book.unmatched().getOrDefault(id, 0L);
			String apart = (mixed > 0 ? number(mixed) + " mixed" : "") + (mixed > 0 && unmatched > 0 ? " · " : "") + (unmatched > 0 ? number(unmatched) + " no harvest that drops it" : "");
			Theme.small(g, font, apart.isEmpty() ? "—" : apart, c3, y + 3, apart.isEmpty() ? Theme.FAINT : Theme.MUTED);
			y += 11;
		}
		return y;
	}

	private static void rareTile(GuiGraphicsExtractor g, Font font, int x, int y, int w, String label, DropBook book, String item, long mutations) {
		long fromMutations = book.dropped(item, DropsPanel::isMutation);
		long total = book.allDrops().getOrDefault(item, 0L);
		tile(g, font, x, y, w, label, total + (total == fromMutations ? "" : " (" + fromMutations + " from mutations)"),
				mutations == 0 ? "no mutation harvested yet" : rate(fromMutations, mutations) + " of mutation harvests", total > 0 ? Theme.WARNING : Theme.TEXT_BRIGHT);
		Theme.cropIcon(g, item.toLowerCase(Locale.ROOT), x + w - 14, y + 4, 10, 255);
	}

	private static void tile(GuiGraphicsExtractor g, Font font, int x, int y, int w, String label, String value, String detail, int valueColor) {
		Theme.card(g, x, y, w, 36, Theme.CARD, Theme.BORDER);
		Theme.small(g, font, label, x + 6, y + 5, Theme.MUTED);
		g.text(font, Theme.fit(font, value, w - 12), x + 6, y + 13, valueColor, false);
		Theme.small(g, font, Theme.fit(font, detail, (w - 12) * 2), x + 6, y + 26, Theme.FAINT);
	}

	// Numbers

	/**
	 * {@code 3 · 0.6%}, or for none {@code 0 · <2.3%}: with nothing in n harvests the true rate is below 3/n at 95%
	 * confidence (the rule of three).
	 */
	static String rate(long count, long harvested) {
		if (harvested == 0) return "—";
		if (count == 0) return "0 · <" + percent(3.0 / harvested);
		return count + " · " + percent((double) count / harvested);
	}

	private static int rateColor(long count, long harvested) {
		return harvested == 0 ? Theme.FAINT : count > 0 ? Theme.WARNING : Theme.DIM;
	}

	private static String percent(double fraction) {
		double value = Math.min(1, fraction) * 100;
		return value >= 10 ? String.format(Locale.ROOT, "%.0f%%", value) : value >= 1 ? String.format(Locale.ROOT, "%.1f%%", value) : String.format(Locale.ROOT, "%.2f%%", value);
	}

	private static String coins(double value) {
		double size = Math.abs(value);
		String text = size >= 1e9 ? String.format(Locale.ROOT, "%.2fB", size / 1e9) : size >= 1e6 ? String.format(Locale.ROOT, "%.2fM", size / 1e6)
				: size >= 1e3 ? String.format(Locale.ROOT, "%.1fk", size / 1e3) : String.format(Locale.ROOT, "%.0f", size);
		return (value < 0 ? "−" : "") + text;
	}

	// Value

	private static @Nullable Double price(String item) {
		return Prices.price(item, priceSource);
	}

	/** The leaderboard interval for your harvest interval (Water Above Crops' stages per visit), at most 14. */
	static int harvestCycles() {
		return Math.max(1, Math.min(14, dev.ilyapple.ghc.core.Water.stagesPerVisit(GreenhouseCompanion.settings().waterVisitsPerDay)));
	}

	/** Input mutations one harvest of {@code plant} uses, from the leaderboard's Normal profile at {@link #harvestCycles}. */
	static Map<String, Double> inputsPerHarvest(String plant) {
		Leaderboard board = CompanionScreen.board();
		if (board == null) return Map.of();
		for (Leaderboard.Entry entry : board.entries()) {
			if (!entry.mutation().equals(plant)) continue;
			Leaderboard.Numbers numbers = entry.at(Leaderboard.Mode.NORMAL, harvestCycles());
			return numbers == null ? Map.of() : Estimate.perHarvest(numbers.uses(), numbers.made());
		}
		return Map.of();
	}

	private static String number(long value) {
		return String.format(Locale.ROOT, "%,d", value);
	}

	private static String since(long ms) {
		return CompanionScreen.duration(System.currentTimeMillis() - ms) + " ago";
	}

	private static String capital(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	// Plants and items

	private static Plants.@Nullable Plant plant(String id) {
		return GreenhouseCompanion.state().plants().byId(id);
	}

	static boolean isMutation(String id) {
		Plants.Plant plant = plant(id);
		return plant != null && plant.mutation();
	}

	private static String rarityOf(String id) {
		Plants.Plant plant = plant(id);
		return plant == null ? "" : plant.mutation() ? plant.rarity() : "crop";
	}

	private static String plantName(String id) {
		Plants.Plant plant = plant(id);
		return plant == null ? id : plant.name();
	}

	private static final Map<String, java.util.Optional<String>> ICONS = new java.util.HashMap<>();

	/** The crop picture for a dropped item, or {@code null}. */
	private static @Nullable String iconFor(String item) {
		return ICONS.computeIfAbsent(item, key -> java.util.Optional.ofNullable(findIcon(key))).orElse(null);
	}

	private static @Nullable String findIcon(String item) {
		String id = switch (item) {
			case "NETHER_STALK" -> "nether_wart";
			case "INK_SACK:3" -> "cocoa_beans";
			case "CARROT_ITEM" -> "carrot";
			case "POTATO_ITEM" -> "potato";
			case "DOUBLE_PLANT" -> "sunflower";
			case "SEEDS" -> "wheat";
			default -> item.toLowerCase(Locale.ROOT);
		};
		return Minecraft.getInstance().getResourceManager().getResource(Theme.crop(id)).isPresent() ? id : null;
	}

	// Controls, the window's look

	private static int pill(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, String label, int x, int y, boolean active, Clicks clicks, Runnable action) {
		int pw = font.width(label) + 12;
		boolean over = mouseX >= x && mouseX < x + pw && mouseY >= y && mouseY < y + 14;
		int accent = Theme.accent();
		Theme.card(g, x, y, pw, 14, active ? Theme.alpha(accent, 0x33) : over ? Theme.CARD_HOVER : Theme.CARD, active ? Theme.alpha(accent, 0x99) : Theme.BORDER);
		g.text(font, label, x + 6, y + 3, active ? accent : Theme.TEXT, false);
		clicks.add(x, y, pw, 14, action);
		return pw + 4;
	}

	private static int smallPill(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, String label, int x, int y, boolean active, Clicks clicks, Runnable action) {
		int pw = font.width(label) / 2 + 10;
		boolean over = mouseX >= x && mouseX < x + pw && mouseY >= y && mouseY < y + 10;
		int accent = Theme.accent();
		Theme.card(g, x, y, pw, 10, active ? Theme.alpha(accent, 0x33) : over ? Theme.CARD_HOVER : Theme.CARD, active ? Theme.alpha(accent, 0x99) : Theme.BORDER);
		Theme.small(g, font, label, x + 5, y + 3, active ? accent : Theme.TEXT);
		clicks.add(x, y, pw, 10, action);
		return pw + 3;
	}

	private static int smallWidth(Font font, String label) {
		return font.width(label) / 2 + 8;
	}

	private static int smallButton(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, String label, int x, int y, int accent, Clicks clicks, Runnable action) {
		int w = smallWidth(font, label);
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 10;
		Theme.rounded(g, x, y, w, 10, Theme.alpha(accent, over ? 0x55 : 0x22));
		Theme.small(g, font, label, x + 4, y + 3, over ? Theme.TEXT_BRIGHT : accent);
		clicks.add(x, y, w, 10, action);
		return w;
	}
}
