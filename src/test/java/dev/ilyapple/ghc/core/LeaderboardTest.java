package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.leaderboard.Leaderboard;

/** The bundled leaderboard: every mutation ranked in both modes, and every Ironman flow importable. */
class LeaderboardTest {
	private static Leaderboard board() throws IOException {
		return board(Leaderboard.NpcPrices.MAIN);
	}

	private static Leaderboard board(Leaderboard.NpcPrices prices) throws IOException {
		try (InputStream in = Files.newInputStream(Path.of("src/main/resources/assets/greenhouse-companion/leaderboard/leaderboard.json"))) {
			return Leaderboard.read(in, prices);
		}
	}

	@Test
	void npcPricesChangeRevenueAndRankingWithoutChangingProductionOrFlows() throws IOException {
		Leaderboard main = board(Leaderboard.NpcPrices.MAIN);
		Leaderboard alpha = board(Leaderboard.NpcPrices.ALPHA);
		boolean rankingChanged = false;
		for (Leaderboard.Mode mode : Leaderboard.Mode.values()) {
			for (int n = 1; n <= 14; n++) {
				List<Leaderboard.Ranked> mainRows = main.rank(mode, n, Leaderboard.Sort.COINS, "");
				List<Leaderboard.Ranked> alphaRows = alpha.rank(mode, n, Leaderboard.Sort.COINS, "");
				assertEquals(mainRows.size(), alphaRows.size());
				for (Leaderboard.Entry m : main.entries()) {
					if (m.defaultFlow() && mode == Leaderboard.Mode.PROFIT_SHARE) continue;
					Leaderboard.Entry a = alpha.entries().stream().filter(e -> e.mutation().equals(m.mutation())).findFirst().orElseThrow();
					Leaderboard.Numbers mn = m.at(mode, n), an = a.at(mode, n);
					assertEquals(mn.made(), an.made());
					assertEquals(mn.uptime(), an.uptime());
					assertEquals(mn.shares(), an.shares());
					assertEquals(mn.uses(), an.uses());
					assertEquals(mn.makes(), an.makes());
					assertEquals(m.normalFlow(), a.normalFlow());
					assertEquals(m.ironmanFlow(), a.ironmanFlow());
					assertTrue(mn.coins() > an.coins(), m.mutation() + " " + mode + " every " + n);
				}
				if (!mainRows.stream().map(r -> r.entry().mutation()).toList().equals(alphaRows.stream().map(r -> r.entry().mutation()).toList())) rankingChanged = true;
			}
		}
		assertTrue(rankingChanged, "price selection must rerank, not just relabel the board");
	}

	@Test
	void defaultFlowIsTheBundledThreePlotFlowWithJellybeanIconAndNoPrivateAddresses() throws IOException {
		String bundled = Files.readString(Path.of("src/main/resources/assets/greenhouse-companion/flows/hybrid-shadevine.json"));
		Leaderboard.Entry entry = board().entries().stream().filter(Leaderboard.Entry::defaultFlow).findFirst().orElseThrow();
		assertEquals("Jellybean + Shadevine (Default)", entry.name());
		assertEquals("magic_jellybean", entry.icon());
		assertEquals("magic_jellybean", entry.outputItem());
		assertEquals(JsonParser.parseString(bundled), JsonParser.parseString(entry.normalFlow()));
		assertEquals(entry.normalFlow(), entry.ironmanFlow());
		assertTrue(entry.profitShare().isEmpty(), "the mixed flow must not be assigned a dedicated-plot formula");
		JsonObject raw = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/greenhouse-companion/leaderboard/leaderboard.json"))).getAsJsonObject();
		assertTrue(!raw.has("sourceUrl"));
		assertTrue(!raw.getAsJsonObject("engine").has("site"));
		assertTrue(raw.getAsJsonObject("sourcePlots").entrySet().stream().noneMatch(e -> e.getValue().getAsJsonObject().has("url")));
		assertTrue(raw.getAsJsonArray("entries").asList().stream().noneMatch(e -> e.getAsJsonObject().has("sourceUrl")));
	}

	@Test
	void allFlowButtonsUseTheSubmittedPlotsIncludingInputSteps() throws IOException {
		JsonObject root = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/greenhouse-companion/leaderboard/leaderboard.json"))).getAsJsonObject();
		JsonObject sources = root.getAsJsonObject("sourcePlots");
		assertEquals(40, sources.size(), "39 producers plus the default mixed flow");
		Set<String> codes = new HashSet<>();
		for (var source : sources.entrySet()) for (var code : source.getValue().getAsJsonObject().getAsJsonArray("layoutCodes")) codes.add(code.getAsString());
		for (Leaderboard.Entry e : board().entries()) {
			assertEquals(e.layout(), Flows.parse(e.normalFlow()).plots().getFirst().steps().getFirst().layoutCode());
			for (String text : List.of(e.normalFlow(), e.ironmanFlow())) {
				for (Flows.Plot plot : Flows.parse(text).plots()) {
					for (Flows.Step step : plot.steps()) {
						assertTrue(codes.contains(step.layoutCode()), e.mutation() + " has an old layout in " + step.id());
						for (Flows.Exit exit : step.exits()) if (exit.to() != null) assertTrue(plot.indexOf(exit.to()) >= 0, step.id() + " missing exit " + exit.to());
					}
				}
			}
		}
	}

	@Test
	void everyMutationIsRankedInBothModes() throws IOException {
		Leaderboard board = board();
		// Every mutation but Lonelily, which is only kept as an input (27 Oct)
		assertEquals(39, board.entries().size());
		assertTrue(board.entries().stream().noneMatch(e -> e.mutation().equals("lonelily")));
		for (Leaderboard.Mode mode : Leaderboard.Mode.values()) {
			for (int n = 1; n <= 14; n++) {
				List<Leaderboard.Ranked> ranked = board.rank(mode, n, Leaderboard.Sort.COINS, "");
				assertEquals(mode == Leaderboard.Mode.PROFIT_SHARE ? 38 : 39, ranked.size(), mode + " every " + n);
				for (int i = 1; i < ranked.size(); i++) {
					if (!ranked.get(i).numbers().possible()) continue;
					assertTrue(ranked.get(i - 1).numbers().coins() >= ranked.get(i).numbers().coins(), mode + " every " + n);
				}
			}
		}
	}

	@Test
	void theIronmanBoardIsTheSimulatedFlowsAtEveryInterval() throws IOException {
		Leaderboard board = board();
		for (int n = 1; n <= 14; n++) {
			List<Leaderboard.Ranked> ranked = board.rank(Leaderboard.Mode.FLOW, n, Leaderboard.Sort.UPTIME, "");
			assertEquals(39, ranked.size(), "every entry's flow is simulated at every " + n);
			for (Leaderboard.Ranked row : ranked) {
				assertTrue(row.numbers().possible(), row.entry().mutation() + " reaches its main step at every " + n);
				// Devourer from nothing takes 130-220 days to reach its main step, so it can sit at a few percent
				assertTrue(row.numbers().uptime() > 0.005 && row.numbers().uptime() < 1.1, row.entry().mutation() + " uptime " + row.numbers().uptime());
				Leaderboard.Sim sim = row.entry().sim(n);
				assertNotNull(sim, row.entry().mutation());
				// How much of the profit share the flow loses, next to the profit share it's measured against
				if (!row.entry().defaultFlow()) {
					assertNotNull(sim.lostVsProfitShare(), row.entry().mutation() + " every " + n);
					assertNotNull(row.entry().at(Leaderboard.Mode.PROFIT_SHARE, n));
				}
			}
		}
		// Zombud's flow keeps two plots on Zombud and loses little of its profit share online every cycle
		Leaderboard.Entry zombud = board.entries().stream().filter(e -> e.mutation().equals("zombud")).findFirst().orElseThrow();
		assertEquals(2, zombud.dedicated());
		assertTrue(zombud.sim(1).lostVsProfitShare() < 0.05, "lost " + zombud.sim(1).lostVsProfitShare());
	}

	@Test
	void theNormalBoardCountsMutationsMadeAndUsedAtTheBazaar() throws IOException {
		Leaderboard board = board();
		Leaderboard.Entry bean = board.entries().stream().filter(e -> e.mutation().equals("magic_jellybean")).findFirst().orElseThrow();
		Leaderboard.Numbers raw = bean.at(Leaderboard.Mode.NORMAL, 1);
		assertTrue(raw.uses().containsKey("duskbloom"), "a Jellybean plot uses Duskbloom");
		assertTrue(raw.makes().containsKey("shadevine"), "its filled cells grow Shadevine");
		// Jellybean 20k, Shadevine 500, Duskbloom 100k: NPC coins + what it makes − what it uses
		java.util.function.ToDoubleFunction<String> price = id -> switch (id) {
			case "magic_jellybean" -> 20_000;
			case "shadevine" -> 500;
			case "duskbloom" -> 100_000;
			default -> 0;
		};
		double expected = raw.coins() + raw.made() * 20_000 + raw.makes().get("shadevine") * 500 - raw.uses().get("duskbloom") * 100_000;
		Leaderboard.Ranked row = board.rank(Leaderboard.Mode.NORMAL, 1, Leaderboard.Sort.COINS, "", price).stream()
				.filter(r -> r.entry().mutation().equals("magic_jellybean")).findFirst().orElseThrow();
		assertEquals(expected, row.numbers().coins(), 1e-6);
		// The other boards stay NPC coins
		Leaderboard.Ranked share = board.rank(Leaderboard.Mode.PROFIT_SHARE, 1, Leaderboard.Sort.COINS, "", price).stream()
				.filter(r -> r.entry().mutation().equals("magic_jellybean")).findFirst().orElseThrow();
		assertEquals(bean.at(Leaderboard.Mode.PROFIT_SHARE, 1).coins(), share.numbers().coins(), 1e-6);
	}

	@Test
	void profitShareCountsTheInputPlots() throws IOException {
		Leaderboard.Entry devourer = board().entries().stream().filter(e -> e.mutation().equals("devourer")).findFirst().orElseThrow();
		Leaderboard.Numbers share = devourer.at(Leaderboard.Mode.PROFIT_SHARE, 1);
		assertNotNull(share);
		// The root script's Devourer: about 17% of the chain's time on the Devourer plot, the rest on its inputs
		assertTrue(share.uptime() > 0.12 && share.uptime() < 0.25, "uptime " + share.uptime());
		assertEquals(1.0, share.shares().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-3);
		assertTrue(share.shares().containsKey("puffercloud") && share.shares().containsKey("zombud"));
		Leaderboard.Numbers normal = devourer.at(Leaderboard.Mode.NORMAL, 1);
		assertTrue(normal.coins() > share.coins() * 0.5, "a chain plot-day is worth less than the main plot alone, but not by that much");
		assertTrue(normal.made() > share.made());
	}

	@Test
	void ironmanFlowsAreValidThreePlotFlows() throws IOException {
		int withFlows = 0;
		int withInputs = 0;
		for (Leaderboard.Entry entry : board().entries()) {
			if (entry.ironmanFlow() == null) continue;
			withFlows++;
			Flows flows = Flows.parse(entry.ironmanFlow());
			assertEquals(3, flows.plots().size(), entry.mutation());
			Flows.Plot plot = flows.plots().getFirst();
			if (!entry.defaultFlow()) assertEquals("main", plot.steps().stream().filter(step -> step.id().startsWith("main")).findFirst().orElseThrow().id().substring(0, 4));
			// Base-crop-only plots (the commons) need no inputs: their flow is the layout alone
			if (entry.targets().isEmpty() && !entry.defaultFlow()) assertEquals(1, plot.steps().size(), entry.mutation());
			else withInputs++;
			assertNotNull(entry.sim(), entry.mutation());
			assertNotNull(entry.sim().daysToMain(), entry.mutation() + " reaches its main step from nothing");
		}
		assertEquals(39, withFlows);
		for (Leaderboard.Entry entry : board().entries()) {
			// "Add normal flow": the plot's layout on all 3 plots, nothing else
			Flows normal = Flows.parse(entry.normalFlow());
			assertEquals(3, normal.plots().size(), entry.mutation());
		}
		assertEquals("186", board().stats().get("overbloom"));
		assertEquals("3059.2", board().stats().get("farmingFortune"));
		assertTrue(withInputs >= 25, "chains with inputs: " + withInputs);
	}
}
