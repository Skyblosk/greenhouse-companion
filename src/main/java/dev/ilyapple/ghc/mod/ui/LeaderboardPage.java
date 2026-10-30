package dev.ilyapple.ghc.mod.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import dev.ilyapple.ghc.core.leaderboard.Leaderboard;
import dev.ilyapple.ghc.mod.GreenhouseCompanion;

/** Page content and its local view state; the screen owns input and shared controls. */
final class LeaderboardPage {
	private final CompanionScreen screen;
	private int boardScroll;
	private int boardDetailScroll;
	private Leaderboard.Mode boardMode = Leaderboard.Mode.FLOW;
	private Leaderboard.Sort boardSort = Leaderboard.Sort.COINS;
	private int boardInterval = 1;
	/** The board's numbers for this many plots (the simulations ran on one; 3 by default, like the user's greenhouse). */
	private int boardPlots = 3;
	private @Nullable String boardSelected;
	private String boardStatus = "";

	LeaderboardPage(CompanionScreen screen) {
		this.screen = screen;
	}

	void scroll(double mouseX, int delta) {
		if (mouseX < screen.contentX() + screen.contentW() * 0.56) boardScroll += delta;
		else boardDetailScroll = Math.max(0, boardDetailScroll + delta * 12);
	}

	void demo() {
		boardMode = Leaderboard.Mode.FLOW;
		boardInterval = 4;
		boardSelected = "zombud";
	}


	private static @Nullable Leaderboard board;
	private static boolean boardLoaded;

	/** The bundled leaderboard, read once. */
	static @Nullable Leaderboard board() {
		if (!boardLoaded) {
			boardLoaded = true;
			try (var in = CompanionScreen.class.getResourceAsStream("/assets/greenhouse-companion/leaderboard/leaderboard.json")) {
				if (in != null) board = Leaderboard.read(in);
			} catch (Exception e) {
				GreenhouseCompanion.LOGGER.error("[GHC] Could not read the leaderboard", e);
			}
		}
		return board;
	}

	private static String coins(double value) {
		if (Math.abs(value) >= 1e6) return String.format(Locale.ROOT, "%.1fM", value / 1e6);
		if (Math.abs(value) >= 1e3) return String.format(Locale.ROOT, "%.0fk", value / 1e3);
		return String.format(Locale.ROOT, "%.0f", value);
	}

	private static String items(double value) {
		return value >= 100 ? String.format(Locale.ROOT, "%.0f", value) : value >= 10 ? String.format(Locale.ROOT, "%.1f", value) : String.format(Locale.ROOT, "%.2f", value);
	}

	private static int rarityColor(String rarity) {
		return switch (rarity) {
			case "uncommon" -> 0xFF4ADE80;
			case "rare" -> 0xFF60A5FA;
			case "epic" -> 0xFFC084FC;
			case "legendary" -> 0xFFFBBF24;
			default -> Theme.TEXT;
		};
	}

	void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = screen.contentX();
		int w = screen.contentW();
		int top = screen.contentTop();
		Leaderboard lb = board();
		if (lb == null) {
			screen.wrap(g, "No leaderboard data in this build: its leaderboard.json is missing or unreadable.", x, top, w, Theme.MUTED);
			return;
		}
		// Ironman: each mutation's tuned Ironman flow as simulated, measured against its profit share; Normal: inputs in stock
		boolean ironman = boardMode != Leaderboard.Mode.NORMAL;
		int px = x;
		px += screen.pill(g, mouseX, mouseY, "Ironman", px, top, ironman, Theme.accent(), () -> {
			boardMode = Leaderboard.Mode.FLOW;
			boardScroll = 0;
		});
		px += screen.pill(g, mouseX, mouseY, "Normal", px, top, !ironman, Theme.accent(), () -> {
			boardMode = Leaderboard.Mode.NORMAL;
			if (boardSort == Leaderboard.Sort.UPTIME) boardSort = Leaderboard.Sort.COINS;
			boardScroll = 0;
		});
		px += 10;
		Theme.small(g, screen.font(), "SORT", px, top + 5, Theme.FAINT);
		px += 20;
		px += screen.pill(g, mouseX, mouseY, "Coins", px, top, boardSort == Leaderboard.Sort.COINS, Theme.WARNING, () -> boardSort = Leaderboard.Sort.COINS);
		px += screen.pill(g, mouseX, mouseY, "Items", px, top, boardSort == Leaderboard.Sort.ITEMS, Theme.WARNING, () -> boardSort = Leaderboard.Sort.ITEMS);
		if (ironman) px += screen.pill(g, mouseX, mouseY, "Uptime", px, top, boardSort == Leaderboard.Sort.UPTIME, Theme.WARNING, () -> boardSort = Leaderboard.Sort.UPTIME);
		px += 10;
		px += screen.pill(g, mouseX, mouseY, "3 plots", px, top, boardPlots == 3, Theme.INFO, () -> boardPlots = 3);
		px += screen.pill(g, mouseX, mouseY, "1 plot", px, top, boardPlots == 1, Theme.INFO, () -> boardPlots = 1);
		// Harvest interval: online every N growth stages
		String every = "Every " + boardInterval;
		int ex = x + w - screen.font().width(every) - 44;
		screen.stepper(g, mouseX, mouseY, "−", ex, top, event -> boardInterval = Math.max(1, boardInterval - 1));
		g.text(screen.font(), every, ex + 20, top + 3, Theme.TEXT_BRIGHT, false);
		screen.stepper(g, mouseX, mouseY, "+", ex + 24 + screen.font().width(every), top, event -> boardInterval = Math.min(14, boardInterval + 1));
		if (mouseX >= ex && mouseX < x + w && mouseY >= top && mouseY < top + 14) {
			screen.tooltip(g, List.of(Component.literal("Harvest interval: you're online every " + boardInterval + " growth stage" + (boardInterval == 1 ? "" : "s"))), mouseX, mouseY);
		}

		int listTop = top + 22;
		int listW = (int) (w * 0.56);
		String plots = boardPlots == 3 ? "3 plots" : "1 plot";
		String head = ironman ? "Ironman: each mutation's Ironman flow run from an empty inventory, " + plots + ", once it's running; uptime = main items a day"
				+ " vs plots that always have their inputs, beside its profit share and how much of it the flow loses. NPC coins a day"
				: "Normal: " + plots + " running full time with inputs in stock: NPC coins + mutations made − inputs used, mutations at Bazaar instant-sell (" + dev.ilyapple.ghc.mod.Prices.status() + ")";
		Theme.small(g, screen.font(), Theme.fit(screen.font(), head, w * 2), x, listTop, Theme.MUTED);
		Theme.small(g, screen.font(), Theme.fit(screen.font(), disclaimer(lb), w * 2), x, listTop + 7, Theme.FAINT);
		listTop += 18;
		dev.ilyapple.ghc.mod.Prices.refresh();
		List<Leaderboard.Ranked> rows = lb.rank(boardMode, boardInterval, boardSort, "", LeaderboardPage::inputPrice);
		int rowH = 20;
		int visible = Math.max(1, (screen.height - listTop - Theme.PAD) / rowH);
		boardScroll = Math.max(0, Math.min(boardScroll, Math.max(0, rows.size() - visible)));
		if (boardSelected == null && !rows.isEmpty()) boardSelected = rows.getFirst().entry().mutation();
		g.enableScissor(x, listTop, x + listW, screen.height - Theme.PAD);
		Leaderboard.Ranked chosen = null;
		for (int i = 0; i < rows.size(); i++) {
			Leaderboard.Ranked row = rows.get(i);
			if (row.entry().mutation().equals(boardSelected)) chosen = row;
			int vi = i - boardScroll;
			if (vi < 0 || vi > visible) continue;
			int ry = listTop + vi * rowH;
			boolean selected = row.entry().mutation().equals(boardSelected);
			boolean over = mouseX >= x && mouseX < x + listW && mouseY >= ry && mouseY < ry + rowH - 2;
			Theme.card(g, x, ry, listW, rowH - 2, selected ? Theme.alpha(Theme.accent(), 0x22) : over ? Theme.CARD_HOVER : Theme.CARD,
					selected ? Theme.alpha(Theme.accent(), 0x99) : Theme.BORDER);
			int rankColor = row.rank() == 1 ? 0xFFFBBF24 : row.rank() == 2 ? 0xFFCBD5E1 : row.rank() == 3 ? 0xFFD97706 : Theme.MUTED;
			g.text(screen.font(), "#" + row.rank(), x + 4, ry + 5, rankColor, false);
			Theme.cropIcon(g, row.entry().mutation(), x + 26, ry + 2, 14, 255);
			int valueW = ironman ? 190 : 92;
			g.text(screen.font(), Theme.fit(screen.font(), row.entry().name(), listW - 44 - valueW), x + 44, ry + 5, rarityColor(row.entry().rarity()), false);
			Leaderboard.Numbers numbers = row.numbers();
			if (!numbers.possible()) {
				Theme.small(g, screen.font(), ironman ? "never reaches its main step" : "an input nobody makes", x + listW - 80, ry + 7, Theme.DANGER);
			} else {
				String c = coins(numbers.coins() * boardPlots);
				String it = items(numbers.made() * boardPlots) + "/d";
				int cx = x + listW - 6 - screen.font().width(c);
				g.text(screen.font(), c, cx, ry + 5, boardSort == Leaderboard.Sort.COINS ? Theme.WARNING : Theme.TEXT, false);
				int ix = cx - 8 - screen.font().width(it);
				g.text(screen.font(), it, ix, ry + 5, boardSort == Leaderboard.Sort.ITEMS ? Theme.WARNING : Theme.DIM, false);
				if (ironman) {
					// The flow's uptime, the profit share beside it, and the share of the profit share it loses
					int ux = ix - 8;
					Leaderboard.Sim sim = row.entry().sim(boardInterval);
					if (sim != null && sim.lostVsProfitShare() != null) {
						String lost = lostLabel(sim.lostVsProfitShare());
						int lw = screen.font().width(lost) / 2;
						Theme.small(g, screen.font(), lost, ux - lw, ry + 7, lostColor(sim.lostVsProfitShare()));
						ux -= lw + 5;
					}
					Leaderboard.Numbers formula = row.entry().at(Leaderboard.Mode.PROFIT_SHARE, boardInterval);
					if (formula != null && formula.possible()) {
						String of = "of " + Math.round(formula.uptime() * 100) + "%";
						int ow = screen.font().width(of) / 2;
						Theme.small(g, screen.font(), of, ux - ow, ry + 7, Theme.FAINT);
						ux -= ow + 4;
					}
					String up = Math.round(numbers.uptime() * 100) + "%";
					g.text(screen.font(), up, ux - screen.font().width(up), ry + 5, boardSort == Leaderboard.Sort.UPTIME ? Theme.WARNING : uptimeColor(numbers.uptime()), false);
				}
			}
			String key = row.entry().mutation();
			screen.addHotspot(new CompanionScreen.Hotspot(x, Math.max(ry, listTop), listW, rowH - 2, event -> {
				boardSelected = key;
				boardDetailScroll = 0;
				boardStatus = "";
			}));
		}
		g.disableScissor();
		if (chosen != null) boardDetail(g, mouseX, mouseY, chosen, x + listW + Theme.GAP, top + 22, w - listW - Theme.GAP);
	}

	/** "−3% lost", or "+1% over" when the simulation beat the formula (within its noise). */
	private static String lostLabel(double lost) {
		long pct = Math.round(lost * 100);
		return pct > 0 ? "−" + pct + "% lost" : pct < 0 ? "+" + (-pct) + "% over" : "±0%";
	}

	private static int lostColor(double lost) {
		return lost <= 0.02 ? Theme.SUCCESS : lost <= 0.10 ? Theme.WARNING : Theme.DANGER;
	}

	/** What the numbers assume: the player stats they were simulated with, and how many plots. */
	private String disclaimer(Leaderboard lb) {
		Map<String, String> stats = lb.stats();
		String fortune = stats.getOrDefault("farmingFortune", "?");
		if (fortune.endsWith(".0")) fortune = fortune.substring(0, fortune.length() - 2);
		boolean ironman = boardMode != Leaderboard.Mode.NORMAL;
		return "Simulated at Farming Fortune " + fortune + ", Overbloom " + stats.getOrDefault("overbloom", "?") + ", Crop Growth "
				+ stats.getOrDefault("cropGrowth", "?") + " (wiki max), harvest every " + boardInterval + (ironman ? ", on 3 plots for 656 days" : ", on 1 plot")
				+ " and shown for " + boardPlots + (boardPlots == 1 ? " plot" : " plots") + "; your numbers scale with your own stats";
	}

	private static int uptimeColor(double uptime) {
		return uptime >= 0.8 ? Theme.SUCCESS : uptime >= 0.4 ? Theme.WARNING : Theme.DANGER;
	}

	private void boardDetail(GuiGraphicsExtractor g, int mouseX, int mouseY, Leaderboard.Ranked row, int x, int y, int w) {
		Leaderboard.Entry e = row.entry();
		boolean ironman = boardMode != Leaderboard.Mode.NORMAL;
		int bottom = screen.height - Theme.PAD;
		Theme.card(g, x, y, w, bottom - y, Theme.glass(Theme.SURFACE), Theme.BORDER);
		int buttonsY = bottom - Theme.BUTTON_H - 6;
		g.enableScissor(x + 1, y + 1, x + w - 1, buttonsY - 4);
		int cy = y + 8 - boardDetailScroll;
		int tx = x + 8;
		int tw = w - 16;
		Theme.cropIcon(g, e.mutation(), tx, cy, 16, 255);
		g.text(screen.font(), Theme.fit(screen.font(), "#" + row.rank() + "  " + e.name(), tw - 20), tx + 20, cy + 4, rarityColor(e.rarity()), false);
		cy += 22;
		String plots = boardPlots == 3 ? "3 plots" : "1 plot";
		if (ironman) {
			cy = ironmanDetail(g, e, tx, cy, tw, plots);
		} else {
			Leaderboard.Numbers normal = e.at(Leaderboard.Mode.NORMAL, boardInterval);
			Theme.small(g, screen.font(), "EVERY " + boardInterval + " · NORMAL · " + plots.toUpperCase(Locale.ROOT) + ", INPUTS IN STOCK", tx, cy, Theme.FAINT);
			cy += 8;
			if (normal != null) {
				cy = screen.wrap(g, items(normal.made() * boardPlots) + " " + e.name() + " a day, " + coins(normal.coins() * boardPlots) + " NPC coins a day", tx, cy, tw, Theme.TEXT);
				List<String> unpriced = new ArrayList<>();
				if (inputPrice(e.mutation()) <= 0) unpriced.add(e.name());
				if (!normal.makes().isEmpty()) cy = screen.wrap(g, "Also makes a day: " + itemList(normal.makes(), unpriced), tx, cy, tw, Theme.MUTED);
				if (!normal.uses().isEmpty()) cy = screen.wrap(g, "Uses a day: " + itemList(normal.uses(), unpriced), tx, cy, tw, Theme.MUTED);
				double made = normal.madeWorth(e.mutation(), LeaderboardPage::inputPrice) * boardPlots;
				double cost = normal.inputCost(LeaderboardPage::inputPrice) * boardPlots;
				cy = screen.wrap(g, "At Bazaar: +" + coins(made) + " mutations made" + (cost > 0 ? ", −" + coins(cost) + " inputs" : "") + " → profit "
						+ coins(normal.profit(e.mutation(), LeaderboardPage::inputPrice) * boardPlots) + " a day", tx, cy, tw, Theme.WARNING);
				if (!unpriced.isEmpty()) cy = screen.wrap(g, "No Bazaar price yet for " + String.join(", ", unpriced) + " (counted as 0)", tx, cy, tw, Theme.FAINT);
			}
			cy = screen.wrap(g, "The normal flow puts this layout on all 3 plots, with nothing else: your inputs come from stock.", tx, cy + 2, tw, Theme.MUTED);
			if (e.fillCells() > 0) cy = filledNote(g, e, tx, cy + 2, tw);
		}
		g.disableScissor();

		// Each board adds its own flow: the Ironman board the Ironman flow, the Normal board the normal one
		int bx = x + 8;
		if (e.layout() != null) bx += screen.button(g, mouseX, mouseY, "Copy link", bx, buttonsY, Theme.INFO, () -> {
			Minecraft.getInstance().keyboardHandler.setClipboard("https://greenhouse.skyshards.com/designer?layout=" + e.layout());
			boardStatus = "Designer link copied";
		}) + 4;
		if (ironman && e.ironmanFlow() != null) bx += screen.button(g, mouseX, mouseY, "Add Ironman flow", bx, buttonsY, Theme.SUCCESS, () -> {
			try {
				screen.state().addToLibrary(e.ironmanFlow(), "Ironman: " + e.name());
				boardStatus = "Added to Flows as \"Ironman: " + e.name() + "\"";
			} catch (RuntimeException ex) {
				boardStatus = "Couldn't add it: " + ex.getMessage();
			}
		}) + 4;
		if (!ironman && e.normalFlow() != null) bx += screen.button(g, mouseX, mouseY, "Add normal flow", bx, buttonsY, Theme.SUCCESS, () -> {
			try {
				screen.state().addToLibrary(e.normalFlow(), "Normal: " + e.name());
				boardStatus = "Added to Flows as \"Normal: " + e.name() + "\"";
			} catch (RuntimeException ex) {
				boardStatus = "Couldn't add it: " + ex.getMessage();
			}
		}) + 4;
		if (!boardStatus.isEmpty()) Theme.small(g, screen.font(), Theme.fit(screen.font(), boardStatus, (x + w - bx - 6) * 2), bx + 2, buttonsY + 6, Theme.SUCCESS);
	}

	/** The Ironman board's detail: the simulated flow at this interval, what it loses, then the profit share it's measured against. */
	private int ironmanDetail(GuiGraphicsExtractor g, Leaderboard.Entry e, int tx, int cy, int tw, String plots) {
		Leaderboard.Sim sim = e.sim(boardInterval);
		Leaderboard.Numbers flow = e.at(Leaderboard.Mode.FLOW, boardInterval);
		Leaderboard.Numbers share = e.at(Leaderboard.Mode.PROFIT_SHARE, boardInterval);
		Theme.small(g, screen.font(), "EVERY " + boardInterval + " · IRONMAN FLOW · FROM AN EMPTY INVENTORY", tx, cy, Theme.FAINT);
		cy += 8;
		if (sim == null || flow == null) {
			cy = screen.wrap(g, "Not simulated at this interval.", tx, cy, tw, Theme.MUTED);
		} else if (sim.daysToMain() == null) {
			cy = screen.wrap(g, "Never reached its main step in " + Math.round(sim.days()) + " days.", tx, cy, tw, Theme.DANGER);
		} else {
			cy = screen.wrap(g, items(flow.made() * boardPlots) + " " + e.name() + " and " + coins(flow.coins() * boardPlots) + " NPC coins a day from " + plots
					+ " once it's running: " + Math.round(flow.uptime() * 100) + "% uptime.", tx, cy, tw, Theme.TEXT);
			if (sim.lostVsProfitShare() != null && share != null && share.possible()) {
				double lost = sim.lostVsProfitShare();
				String text = lost > 0.005 ? "Loses " + String.format(Locale.ROOT, "%.1f%%", lost * 100) + " of the profit share (" + items(share.made() * boardPlots)
						+ " a day by the formula, " + Math.round(share.uptime() * 100) + "% uptime): plots regrow after swaps and stock runs out before the plots notice."
						: lost < -0.005 ? "Makes " + String.format(Locale.ROOT, "%.1f%%", -lost * 100) + " more than the profit share (" + items(share.made() * boardPlots)
						+ " a day by the formula): within the simulation's noise, it loses nothing."
						: "Matches the profit share (" + items(share.made() * boardPlots) + " a day by the formula).";
				cy = screen.wrap(g, text, tx, cy + 2, tw, lostColor(lost));
			}
			String first = "Main step after " + String.format(Locale.ROOT, "%.1f", sim.daysToMain()) + " days from nothing";
			String swaps = sim.swapsPerPlotDay() == null ? "" : sim.swapsPerPlotDay() < 0.005 ? ", then no swaps" : ", then a swap every " + swapEvery(sim.swapsPerPlotDay()) + " per plot";
			cy = screen.wrap(g, first + swaps + " (simulated " + Math.round(sim.days()) + " days, online every " + boardInterval + ").", tx, cy + 2, tw, Theme.DIM);
		}
		if (e.ironmanFlow() != null && !e.targets().isEmpty()) {
			cy += 4;
			String round = e.roundDays() <= 0 ? "about two days" : (e.roundDays() == Math.rint(e.roundDays()) ? Integer.toString((int) e.roundDays()) : Double.toString(e.roundDays()))
					+ (e.roundDays() == 1 ? " day" : " days");
			String roles = switch (e.dedicated()) {
				case 1 -> " Plot 1 stays on the main step once it runs (its layout never regrows); plots 2 and 3 make the inputs and run the main step in between.";
				case 2 -> " Plots 1 and 2 stay on the main step once it runs (their layouts never regrow); plot 3 makes the inputs and runs the main step in between.";
				default -> " All 3 plots run it.";
			};
			cy = screen.wrap(g, "Each plot first makes the inputs, deepest first; each step ends once the stock is enough for " + round + " of the main"
					+ " plot (inventory rule), and the main step goes back when an input runs low." + roles + " Plot roles and round length are tuned"
					+ " so layouts grow as long as they can without a swap.", tx, cy, tw, Theme.MUTED);
			for (Leaderboard.Target target : e.targets()) {
				cy = screen.wrap(g, "Make " + screen.name(target.item()) + " to " + target.high() + (target.low() == null ? "" : ", back when under " + target.low()), tx + 4, cy, tw - 4, Theme.DIM);
			}
		} else {
			cy = screen.wrap(g, "Only base crops needed: the layout alone is the flow.", tx, cy + 2, tw, Theme.MUTED);
		}
		if (e.fillCells() > 0) cy = filledNote(g, e, tx, cy + 2, tw);

		// The profit share it's measured against, as before
		cy += 6;
		Theme.small(g, screen.font(), "PROFIT SHARE · EVERY " + boardInterval + " · PLOT TIME TO KEEP IT RUNNING", tx, cy, Theme.FAINT);
		cy += 8;
		if (share == null || !share.possible()) {
			cy = screen.wrap(g, "Can't run: one of its inputs has no plot that makes it.", tx, cy, tw, Theme.DANGER);
		} else {
			cy = screen.wrap(g, "Profit share " + Math.round(share.uptime() * 100) + "%: " + items(share.made() * boardPlots) + " " + e.name() + " and "
					+ coins(share.coins() * boardPlots) + " coins a day from " + plots + " shared across the chain", tx, cy, tw, Theme.TEXT);
			cy += 2;
			List<Map.Entry<String, Double>> shares = new ArrayList<>(share.shares().entrySet());
			shares.sort(Map.Entry.<String, Double>comparingByValue().reversed());
			for (Map.Entry<String, Double> part : shares) {
				boolean main = part.getKey().equals(e.mutation());
				String label = (main ? "★ " : "") + plotName(part.getKey());
				int barX = tx + 96;
				int barW = Math.max(10, tw - 96 - 34);
				g.text(screen.font(), Theme.fit(screen.font(), label, 92), tx, cy, main ? Theme.TEXT_BRIGHT : Theme.DIM, false);
				g.fill(barX, cy + 2, barX + barW, cy + 6, Theme.CARD_SUNK);
				g.fill(barX, cy + 2, barX + (int) Math.round(barW * part.getValue()), cy + 6, main ? Theme.accent() : Theme.INFO);
				String pct = String.format(Locale.ROOT, "%.1f%%", part.getValue() * 100);
				g.text(screen.font(), pct, tx + tw - screen.font().width(pct), cy, main ? Theme.accent() : Theme.DIM, false);
				cy += 11;
			}
		}
		return cy;
	}

	private int filledNote(GuiGraphicsExtractor g, Leaderboard.Entry e, int tx, int cy, int tw) {
		return screen.wrap(g, "Filled: " + e.fillCells() + " free cells hold base crops and spawn spots for commons (its inputs' first), out of reach of every"
				+ " spot's ring and buffs.", tx, cy, tw, Theme.MUTED);
	}

	/** "~2.5 days", "~9 hours" between swaps at {@code perDay} swaps a day. */
	private static String swapEvery(double perDay) {
		if (perDay <= 0) return "never";
		double days = 1 / perDay;
		return days >= 1.5 ? String.format(Locale.ROOT, "~%.1f days", days) : String.format(Locale.ROOT, "~%.0f hours", days * 24);
	}

	private String plotName(String key) {
		return switch (key) {
			case "shellfruit_loop_plot" -> "Shellfruit loop";
			case "replant_converter" -> "Replant converter";
			default -> screen.name(key);
		};
	}

	/** "4.6 Dustgrain, 47 Shadevine" for the plots shown; names with no Bazaar price are added to {@code unpriced}. */
	private String itemList(Map<String, Double> perPlot, List<String> unpriced) {
		StringBuilder text = new StringBuilder();
		perPlot.forEach((id, v) -> {
			if (!text.isEmpty()) text.append(", ");
			text.append(items(v * boardPlots)).append(' ').append(screen.name(id));
			if (inputPrice(id) <= 0 && !unpriced.contains(screen.name(id))) unpriced.add(screen.name(id));
		});
		return text.toString();
	}

	/** One mutation (simulator id) at the Bazaar's instant-sell price, 0 when it has none. */
	private static double inputPrice(String id) {
		Double price = dev.ilyapple.ghc.mod.Prices.price(dev.ilyapple.ghc.core.drops.Items.itemOf(id), dev.ilyapple.ghc.mod.Prices.Source.BAZAAR);
		return price == null ? 0 : price;
	}

}
