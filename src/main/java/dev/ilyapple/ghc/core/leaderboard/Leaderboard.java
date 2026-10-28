package dev.ilyapple.ghc.core.leaderboard;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The Leaderboard tab: one dedicated plot per mutation (the user's max layouts and profit-share picks), ranked from
 * simulator results made offline with the SkyShards engine and shipped with the mod.
 * <ul>
 * <li><b>Normal</b>: the plot running full time with its inputs in stock; its coins are NPC coins plus the mutations
 *     it makes at the Bazaar, less the inputs it uses at the Bazaar ({@link #rank(Mode, int, Sort, String, ToDoubleFunction)}).</li>
 * <li><b>Profit share</b>: the same plot once the plots that grow its inputs are counted (the user's formula): its
 *     uptime, every input plot's uptime, and items and NPC coins per plot-day of the whole chain.</li>
 * <li>Each entry also carries an Ironman bootstrap flow: steps that make the inputs (each ending on an inventory rule)
 *     before the main step, run in the simulator from an empty inventory.</li>
 * </ul>
 * Plain Java, so the ranking is tested.
 */
public final class Leaderboard {
	/**
	 * {@code FLOW}: the Ironman board, each mutation's tuned Ironman flow simulated from an empty inventory at that harvest
	 * interval, once it's running; {@code PROFIT_SHARE}: the formula it's measured against (input plots counted as
	 * fractions of a plot), shown with it; {@code NORMAL}: one plot with its inputs in stock.
	 */
	public enum Mode { PROFIT_SHARE, NORMAL, FLOW }

	public enum Sort { COINS, ITEMS, UPTIME }

	/**
	 * One interval's numbers. In profit-share mode {@code made} and {@code coins} are per chain plot-day. {@code coins}
	 * are NPC coins (mutation items count 0); {@code uses} the mutations used a day, {@code makes} the other mutations
	 * made a day beyond what the plot uses (Normal only).
	 */
	public record Numbers(int n, boolean possible, double made, double coins, double uptime, Map<String, Double> shares, Map<String, Double> uses,
			Map<String, Double> makes) {
		/** What the inputs used a day are worth at {@code price} (per item, by simulator id; 0 when unknown). */
		public double inputCost(ToDoubleFunction<String> price) {
			return worth(uses, price);
		}

		/** What the mutations made a day are worth at {@code price}: {@code main} ({@link #made}) and every by-product. */
		public double madeWorth(String main, ToDoubleFunction<String> price) {
			return made * price.applyAsDouble(main) + worth(makes, price);
		}

		/** NPC coins, plus the mutations made, less the inputs used, all a day. */
		public double profit(String main, ToDoubleFunction<String> price) {
			return coins + madeWorth(main, price) - inputCost(price);
		}

		/** The same numbers with {@link #profit} as the coins. */
		public Numbers atPrices(String main, ToDoubleFunction<String> price) {
			return new Numbers(n, possible, made, profit(main, price), uptime, shares, uses, makes);
		}

		private static double worth(Map<String, Double> items, ToDoubleFunction<String> price) {
			double total = 0;
			for (Map.Entry<String, Double> item : items.entrySet()) total += item.getValue() * price.applyAsDouble(item.getKey());
			return total;
		}
	}

	/**
	 * How the Ironman bootstrap flow did from nothing at harvest interval {@code n}: days until the main step first ran,
	 * its share of plot time (overall, and over the second half once it's running: {@code lateMainShare}), swaps per
	 * plot-day, {@code outputUptime} (main items a day once running over 3 stocked plots', the same quantity as the
	 * profit share), and {@code lostVsProfitShare}: the share of the profit share's items it doesn't make.
	 */
	public record Sim(int n, @Nullable Double daysToMain, double mainShare, double coinsPerDay, double madePerDay, @Nullable Double steadyCoinsPerDay,
			@Nullable Double steadyMadePerDay, double days, @Nullable Double lateMainShare, @Nullable Double swapsPerPlotDay,
			@Nullable Double outputUptime, @Nullable Double lateMadePerDay, @Nullable Double lateCoinsPerDay, @Nullable Double lostVsProfitShare) {}

	/** Mutations kept as inputs but not listed on the board (Lonelily's own numbers misled: it's only worth it as an input). */
	public static final java.util.Set<String> HIDDEN = java.util.Set.of("lonelily");

	public record Target(String item, int high, @Nullable Integer low) {}

	/**
	 * @param normalFlow  the plain flow: the plot's layout on all 3 plots, no input-making steps
	 * @param ironmanFlow the bootstrap flow: input-making steps with inventory rules, then the main step
	 * @param roundDays   the days of the main step the Ironman flow's input targets are sized for (tuned per mutation)
	 * @param fillCells   free cells of the layout given base crops and spawn spots for commons its chain needs (0: none)
	 * @param dedicated   plots (1..dedicated) of the Ironman flow that stay on the main step once it runs; the others make
	 *                    the inputs
	 * @param sims        the Ironman flow simulated at each harvest interval
	 * @param flow        the same per plot and interval as board numbers (once it's running; uptime as output uptime)
	 */
	public record Entry(String mutation, String name, String rarity, String producer, @Nullable String layout, boolean flowProducer,
			List<Numbers> normal, List<Numbers> profitShare, List<Target> targets, List<Sim> sims, @Nullable String ironmanFlow,
			@Nullable String normalFlow, double roundDays, int fillCells, int dedicated, List<Numbers> flow) {
		public @Nullable Numbers at(Mode mode, int n) {
			for (Numbers numbers : switch (mode) {
				case NORMAL -> normal;
				case PROFIT_SHARE -> profitShare;
				case FLOW -> flow;
			}) if (numbers.n() == n) return numbers;
			return null;
		}

		/** The Ironman flow's simulation at harvest interval {@code n}, or {@code null}. */
		public @Nullable Sim sim(int n) {
			for (Sim sim : sims) if (sim.n() == n) return sim;
			return null;
		}

		/** Online every cycle. */
		public @Nullable Sim sim() {
			return sim(1);
		}
	}

	/** An entry's place on the board. */
	public record Ranked(int rank, Entry entry, Numbers numbers) {}

	private final List<Entry> entries;
	private final String about;
	private final Map<String, String> stats;

	private Leaderboard(List<Entry> entries, String about, Map<String, String> stats) {
		this.entries = List.copyOf(entries);
		this.about = about;
		this.stats = Map.copyOf(stats);
	}

	/** The player stats every number was simulated with (farmingFortune, overbloom, cropGrowth…). */
	public Map<String, String> stats() {
		return stats;
	}

	public List<Entry> entries() {
		return entries;
	}

	public String about() {
		return about;
	}

	public static Leaderboard read(InputStream in) throws IOException {
		JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		List<Entry> entries = new ArrayList<>();
		for (JsonElement element : root.getAsJsonArray("entries")) {
			JsonObject e = element.getAsJsonObject();
			if (HIDDEN.contains(e.get("mutation").getAsString())) continue;
			List<Target> targets = new ArrayList<>();
			double roundDays = 0;
			int dedicated = 0;
			List<Sim> sims = new ArrayList<>();
			String flow = null;
			if (e.has("ironman") && e.get("ironman").isJsonObject()) {
				JsonObject ironman = e.getAsJsonObject("ironman");
				if (ironman.has("targets")) {
					for (Map.Entry<String, JsonElement> target : ironman.getAsJsonObject("targets").entrySet()) {
						JsonObject t = target.getValue().getAsJsonObject();
						targets.add(new Target(target.getKey(), t.get("high").getAsInt(), number(t, "low") == null ? null : number(t, "low").intValue()));
					}
				}
				// One simulation per harvest interval (older data: only the one online every cycle)
				List<JsonObject> runs = new ArrayList<>();
				if (ironman.has("simByN")) ironman.getAsJsonArray("simByN").forEach(run -> runs.add(run.getAsJsonObject()));
				else if (ironman.has("sim")) runs.add(ironman.getAsJsonObject("sim"));
				for (JsonObject s : runs) {
					sims.add(new Sim(s.has("n") ? s.get("n").getAsInt() : 1, number(s, "daysToMain"), orZero(s, "mainShare"), orZero(s, "coinsPerDay"),
							orZero(s, "madePerDay"), number(s, "steadyCoinsPerDay"), number(s, "steadyMadePerDay"), orZero(s, "days"), number(s, "lateMainShare"),
							// Once running, like lateMainShare
							number(s, "lateSwapsPerPlotDay") != null ? number(s, "lateSwapsPerPlotDay") : number(s, "swapsPerPlotDay"),
							number(s, "outputUptime"), number(s, "lateMadePerDay"), number(s, "lateCoinsPerDay"), number(s, "lostVsProfitShare")));
				}
				if (ironman.has("flow")) flow = ironman.get("flow").toString();
				roundDays = orZero(ironman, "roundDays");
				dedicated = (int) orZero(ironman, "dedicated");
			}
			int fillCells = e.has("fill") && e.getAsJsonObject("fill").has("cells") ? e.getAsJsonObject("fill").getAsJsonObject("cells").size() : 0;
			// The Ironman board: per plot (the board scales by plots), once running; uptime is main items a day over plots
			// that always have their inputs, the same quantity as the profit share's
			List<Numbers> flowNumbers = new ArrayList<>();
			for (Sim sim : sims) {
				double made = sim.lateMadePerDay() != null ? sim.lateMadePerDay() : sim.steadyMadePerDay() != null ? sim.steadyMadePerDay() : sim.madePerDay();
				double coins = sim.lateCoinsPerDay() != null ? sim.lateCoinsPerDay() : sim.steadyCoinsPerDay() != null ? sim.steadyCoinsPerDay() : sim.coinsPerDay();
				double uptime = sim.outputUptime() != null ? sim.outputUptime() : sim.lateMainShare() != null ? sim.lateMainShare() : sim.mainShare();
				flowNumbers.add(new Numbers(sim.n(), sim.daysToMain() != null, made / 3, coins / 3, uptime, Map.of(), Map.of(), Map.of()));
			}
			entries.add(new Entry(e.get("mutation").getAsString(), e.get("name").getAsString(), string(e, "rarity"), e.get("producer").getAsString(),
					string(e, "layout"), e.has("flowProducer") && e.get("flowProducer").getAsBoolean(), numbers(e, "normal"), numbers(e, "profitShare"),
					targets, List.copyOf(sims), flow, e.has("normalFlow") ? e.get("normalFlow").toString() : null, roundDays, fillCells, dedicated,
					List.copyOf(flowNumbers)));
		}
		Map<String, String> stats = new LinkedHashMap<>();
		if (root.has("stats")) for (Map.Entry<String, JsonElement> stat : root.getAsJsonObject("stats").entrySet()) stats.put(stat.getKey(), stat.getValue().getAsString());
		return new Leaderboard(entries, root.has("about") ? root.get("about").getAsString() : "", stats);
	}

	private static List<Numbers> numbers(JsonObject entry, String key) {
		List<Numbers> list = new ArrayList<>();
		if (!entry.has(key)) return list;
		for (JsonElement element : entry.getAsJsonArray(key)) {
			JsonObject o = element.getAsJsonObject();
			boolean possible = !o.has("possible") || o.get("possible").getAsBoolean();
			list.add(new Numbers(o.get("n").getAsInt(), possible, orZero(o, "made"), orZero(o, "coins"), o.has("uptime") ? o.get("uptime").getAsDouble() : 1.0,
					map(o, "shares"), map(o, "uses"), map(o, "makes")));
		}
		return list;
	}

	private static Map<String, Double> map(JsonObject o, String key) {
		Map<String, Double> out = new LinkedHashMap<>();
		if (!o.has(key)) return out;
		for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) out.put(e.getKey(), e.getValue().getAsDouble());
		return out;
	}

	private static @Nullable Double number(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : null;
	}

	private static double orZero(JsonObject o, String key) {
		Double value = number(o, key);
		return value == null ? 0 : value;
	}

	private static String string(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	/**
	 * The board for one mode and harvest interval, best first. Entries that can't run (an input nobody makes) go last;
	 * {@code filter} keeps entries whose name contains it (case-insensitive), empty for all.
	 */
	public List<Ranked> rank(Mode mode, int n, Sort sort, String filter) {
		return rank(mode, n, sort, filter, item -> 0);
	}

	/**
	 * As {@link #rank(Mode, int, Sort, String)}, the Normal board's coins at {@code price} (per mutation, by simulator
	 * id): NPC coins plus the mutations it makes, less the inputs it uses from stock. The other boards are NPC coins.
	 */
	public List<Ranked> rank(Mode mode, int n, Sort sort, String filter, ToDoubleFunction<String> price) {
		String needle = filter.strip().toLowerCase(java.util.Locale.ROOT);
		List<Ranked> rows = new ArrayList<>();
		for (Entry entry : entries) {
			if (!needle.isEmpty() && !entry.name().toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
			Numbers numbers = entry.at(mode, n);
			if (numbers != null && mode == Mode.NORMAL) numbers = numbers.atPrices(entry.mutation(), price);
			if (numbers != null) rows.add(new Ranked(0, entry, numbers));
		}
		Comparator<Ranked> by = switch (sort) {
			case COINS -> Comparator.comparingDouble((Ranked r) -> r.numbers().coins());
			case ITEMS -> Comparator.comparingDouble((Ranked r) -> r.numbers().made());
			case UPTIME -> Comparator.comparingDouble((Ranked r) -> r.numbers().uptime());
		};
		rows.sort(Comparator.comparing((Ranked r) -> !r.numbers().possible()).thenComparing(by.reversed()).thenComparing(r -> r.entry().name()));
		List<Ranked> ranked = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) ranked.add(new Ranked(i + 1, rows.get(i).entry(), rows.get(i).numbers()));
		return ranked;
	}
}
