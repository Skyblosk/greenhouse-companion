package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.ilyapple.ghc.core.flow.Flows;
import dev.ilyapple.ghc.core.leaderboard.Leaderboard;

/** The bundled leaderboard: every mutation ranked in both modes, and every Ironman flow importable. */
class LeaderboardTest {
	private static Leaderboard board() throws IOException {
		try (InputStream in = Files.newInputStream(Path.of("src/main/resources/assets/greenhouse-companion/leaderboard/leaderboard.json"))) {
			return Leaderboard.read(in);
		}
	}

	@Test
	void everyMutationIsRankedInBothModes() throws IOException {
		Leaderboard board = board();
		// Every mutation but Lonelily, which is only kept as an input (27 Oct)
		assertEquals(38, board.entries().size());
		assertTrue(board.entries().stream().noneMatch(e -> e.mutation().equals("lonelily")));
		for (Leaderboard.Mode mode : Leaderboard.Mode.values()) {
			for (int n = 1; n <= 14; n++) {
				List<Leaderboard.Ranked> ranked = board.rank(mode, n, Leaderboard.Sort.COINS, "");
				assertEquals(38, ranked.size(), mode + " every " + n);
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
			assertEquals(38, ranked.size(), "every entry's flow is simulated at every " + n);
			for (Leaderboard.Ranked row : ranked) {
				assertTrue(row.numbers().possible(), row.entry().mutation() + " reaches its main step at every " + n);
				// Devourer from nothing takes 130-220 days to reach its main step, so it can sit at a few percent
				assertTrue(row.numbers().uptime() > 0.005 && row.numbers().uptime() < 1.1, row.entry().mutation() + " uptime " + row.numbers().uptime());
				Leaderboard.Sim sim = row.entry().sim(n);
				assertNotNull(sim, row.entry().mutation());
				// How much of the profit share the flow loses, next to the profit share it's measured against
				assertNotNull(sim.lostVsProfitShare(), row.entry().mutation() + " every " + n);
				assertNotNull(row.entry().at(Leaderboard.Mode.PROFIT_SHARE, n));
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
			assertEquals("main", plot.steps().stream().filter(step -> step.id().startsWith("main")).findFirst().orElseThrow().id().substring(0, 4));
			// Base-crop-only plots (the commons) need no inputs: their flow is the layout alone
			if (entry.targets().isEmpty()) assertEquals(1, plot.steps().size(), entry.mutation());
			else withInputs++;
			assertNotNull(entry.sim(), entry.mutation());
			assertNotNull(entry.sim().daysToMain(), entry.mutation() + " reaches its main step from nothing");
		}
		assertEquals(38, withFlows);
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
