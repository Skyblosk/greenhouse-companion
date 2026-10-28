package dev.ilyapple.ghc.core.drops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DropsTest {
	@Test
	void readsRareCropLines() {
		assertEquals(Map.entry("FERMENTO", 1L), Items.rareCrop("RARE CROP! Fermento (+97)"));
		assertEquals(Map.entry("HELIANTHUS", 1L), Items.rareCrop("RARE CROP! Helianthus (+115) (automatically donated)"));
		assertEquals(Map.entry("BURROWING_SPORES", 1L), Items.rareCrop("VERY RARE CROP! Burrowing Spores"));
		assertEquals(Map.entry("CROPIE", 2L), Items.rareCrop("RARE CROP! 2x Cropie (+97)"));
		assertNull(Items.rareCrop("You harvested a Fermento"));
	}

	@Test
	void sackReportsCountInBaseUnitsAndNetCompacting() {
		Map<String, Long> gains = Items.sackGains(List.of(
				"Added items:\n  +160 Enchanted Wheat (Agronomy Sack)\n  +12 Nether Wart (Agronomy Sack)\n  +1,200 Sugar Cane (Agronomy Sack)\n",
				"Removed items:\n  -320 Wheat (Agronomy Sack)\n"));
		assertEquals(160L * 160 - 320, gains.get("WHEAT"));
		assertEquals(12L, gains.get("NETHER_STALK"));
		assertEquals(1200L, gains.get("SUGAR_CANE"));
	}

	@Test
	void namesItems() {
		assertEquals("Cocoa Beans", Items.name("INK_SACK:3"));
		assertEquals("Magic Jellybean", Items.name("MAGIC_JELLYBEAN"));
		assertEquals("NETHER_STALK", Items.idForName("Nether Wart"));
		assertEquals(new Items.Unit("MELON", 25_600), Items.unit("ENCHANTED_MELON_BLOCK"));
	}

	private static DropMatcher.Drop chat(String item, long tick, boolean inGreenhouse) {
		return new DropMatcher.Drop(item, 1, DropMatcher.Source.CHAT, tick, tick * 50, inGreenhouse);
	}

	/** Shadevine drops 78 Cactus and 104 Sugar Cane, a Cactus 132 Cactus, a Sugar Cane 136 (the simulator's data). */
	private static final LootTable LOOT = new LootTable(Map.of(
			"shadevine", Map.of("CACTUS", 78.0, "SUGAR_CANE", 104.0, "SHADEVINE", 1.0),
			"cactus", Map.of("CACTUS", 132.0),
			"sugar_cane", Map.of("SUGAR_CANE", 136.0),
			"magic_jellybean", Map.of("SUGAR_CANE", 1200.0, "MAGIC_JELLYBEAN", 1.0),
			"wheat", Map.of("WHEAT", 72.0),
			"ashwreath", Map.of("NETHER_STALK", 180.0, "ASHWREATH", 1.0)), java.util.Set.of("shadevine", "magic_jellybean", "ashwreath"));

	private static DropMatcher matcher(DropBook book) {
		return new DropMatcher(List.of(book), LOOT);
	}

	@Test
	void aDropGoesToTheHarvestSeenInTheSameFewTicks() {
		DropBook book = new DropBook(0);
		DropMatcher matcher = matcher(book);
		matcher.harvest(new DropMatcher.Harvest("shadevine", 10, 500));
		matcher.harvest(new DropMatcher.Harvest("magic_jellybean", 30, 1500));
		matcher.drop(chat("FERMENTO", 11, true));
		matcher.drop(new DropMatcher.Drop("SUGAR_CANE", 1200, DropMatcher.Source.INVENTORY, 30, 1500, true));
		// Not settled until the window has passed
		assertTrue(matcher.tick(12, 600).isEmpty());
		List<DropMatcher.Outcome> outcomes = matcher.tick(40, 2000);
		assertEquals(2, outcomes.size());
		assertEquals(1, book.lines().get("shadevine").drop("FERMENTO"));
		assertEquals(1200, book.lines().get("magic_jellybean").drop("SUGAR_CANE"));
		assertEquals(1, book.lines().get("shadevine").harvested());
	}

	@Test
	void breaksThatYieldNothingArentCounted() {
		DropBook book = new DropBook(0);
		DropMatcher matcher = matcher(book);
		// Like the placed Zombud and Cindershade: seen to break, nothing came
		matcher.harvest(new DropMatcher.Harvest("zombud", 10, 500));
		matcher.harvest(new DropMatcher.Harvest("shadevine", 20, 1000));
		matcher.drop(new DropMatcher.Drop("SHADEVINE", 1, DropMatcher.Source.INVENTORY, 20, 1000, true));
		matcher.tick(30, 1500);
		assertEquals(1, book.lines().get("shadevine").harvested());
		assertNull(book.lines().get("zombud"));
		matcher.tick(5000, 1000 + DropMatcher.COUNT_WITHIN_MS);
		assertEquals(List.of("zombud"), matcher.yieldedNothing().stream().map(DropMatcher.Harvest::plant).toList());
		assertNull(book.lines().get("zombud"));
	}

	@Test
	void twoKindsAtOnceAreMixedOnlyWhenTheirLootCantTell() {
		DropBook book = new DropBook(0);
		DropMatcher matcher = matcher(book);
		matcher.harvest(new DropMatcher.Harvest("shadevine", 10, 500));
		matcher.harvest(new DropMatcher.Harvest("wheat", 10, 500));
		matcher.drop(chat("SYNTHESIS_GARDEN_CHIP", 10, true));
		// Wheat is only in the Wheat's loot table, so it isn't mixed
		matcher.drop(new DropMatcher.Drop("WHEAT", 72, DropMatcher.Source.INVENTORY, 10, 500, true));
		matcher.drop(chat("FERMENTO", 100, true));
		matcher.drop(chat("CROPIE", 100, false));
		matcher.drop(new DropMatcher.Drop("WHEAT", 5, DropMatcher.Source.INVENTORY, 100, 5000, true));
		List<DropMatcher.Outcome> outcomes = matcher.tick(200, 10_000);
		assertEquals(Map.of("SYNTHESIS_GARDEN_CHIP", 1L), book.mixed());
		assertEquals(72, book.lines().get("wheat").drop("WHEAT"));
		assertEquals(Map.of("FERMENTO", 1L), book.unmatched());
		// Outside a greenhouse a rare crop isn't a greenhouse drop; inventory gains with no harvest aren't drops
		assertEquals(2, outcomes.stream().filter(outcome -> outcome.verdict().equals("ignored")).count());
	}

	@Test
	void sackReportsAreSplitByLootTable() {
		DropBook book = new DropBook(0);
		DropMatcher matcher = matcher(book);
		// 10 Shadevines and 5 Cactus in the half minute before a report: Cactus by 10 x 78 against 5 x 132, Sugar Cane
		// all Shadevine's (no Sugar Cane was harvested)
		for (int i = 0; i < 10; i++) matcher.harvest(new DropMatcher.Harvest("shadevine", 10 + i, 1_000 + i * 100L));
		for (int i = 0; i < 5; i++) matcher.harvest(new DropMatcher.Harvest("cactus", 30 + i, 3_000 + i * 100L));
		matcher.drop(new DropMatcher.Drop("CACTUS", 1_440, DropMatcher.Source.SACKS, 400, 30_000, true));
		matcher.drop(new DropMatcher.Drop("SUGAR_CANE", 1_040, DropMatcher.Source.SACKS, 400, 30_000, true));
		matcher.tick(400, 30_000);
		assertEquals(780, book.lines().get("shadevine").drop("CACTUS"));
		assertEquals(660, book.lines().get("cactus").drop("CACTUS"));
		assertEquals(1_040, book.lines().get("shadevine").drop("SUGAR_CANE"));
		assertEquals(10, book.lines().get("shadevine").harvested());
		assertEquals(5, book.lines().get("cactus").harvested());
		// A report with no harvest of anything that drops it is mixed; with no harvest at all it's left out
		matcher.drop(new DropMatcher.Drop("NETHER_STALK", 10, DropMatcher.Source.SACKS, 401, 30_100, true));
		matcher.drop(new DropMatcher.Drop("NETHER_STALK", 10, DropMatcher.Source.SACKS, 9000, 500_000, true));
		List<DropMatcher.Outcome> outcomes = matcher.tick(9000, 500_000);
		assertEquals(Map.of("NETHER_STALK", 10L), book.mixed());
		assertEquals("ignored", outcomes.getLast().verdict());
	}

	@Test
	void rareCropsComeFromPlantsThatDropTheirCrops() {
		DropBook book = new DropBook(0);
		LootTable loot = new LootTable(Map.of("shadevine", Map.of("CACTUS", 78.0, "SUGAR_CANE", 104.0, "SHADEVINE", 1.0),
				"dustgrain", Map.of("WHEAT", 72.0, "DUSTGRAIN", 1.0), "cactus", Map.of("CACTUS", 132.0)), java.util.Set.of("shadevine", "dustgrain"));
		DropMatcher matcher = new DropMatcher(List.of(book), loot);
		// The user's Plot 9: a Fermento while a Shadevine and a Dustgrain broke goes to the Shadevine (Cactus, Sugar Cane)
		matcher.harvest(new DropMatcher.Harvest("dustgrain", 12, 600));
		matcher.harvest(new DropMatcher.Harvest("shadevine", 14, 700));
		matcher.drop(chat("FERMENTO", 11, true));
		// A Dustgrain alone can't give one
		matcher.harvest(new DropMatcher.Harvest("dustgrain", 100, 5000));
		matcher.drop(chat("FERMENTO", 98, true));
		// Ethereal Vine only from a mutation: the Shadevine, not the Cactus seen nearer the line
		matcher.harvest(new DropMatcher.Harvest("cactus", 202, 10_100));
		matcher.harvest(new DropMatcher.Harvest("shadevine", 206, 10_300));
		matcher.drop(chat("ETHEREAL_VINE", 200, true));
		matcher.tick(400, 20_000);
		assertEquals(1, book.lines().get("shadevine").drop("FERMENTO"));
		assertEquals(Map.of("FERMENTO", 1L), book.unmatched());
		assertEquals(1, book.lines().get("shadevine").drop("ETHEREAL_VINE"));
		assertNull(book.lines().get("cactus"));
	}

	@Test
	void theBreakCanShowAFewTicksAfterItsLine() {
		DropBook book = new DropBook(0);
		DropMatcher matcher = matcher(book);
		// The user's log: the Shadevine was seen to break 5 ticks after "RARE CROP! Ethereal Vine"
		matcher.drop(chat("ETHEREAL_VINE", 10, true));
		assertTrue(matcher.tick(14, 700).isEmpty());
		matcher.harvest(new DropMatcher.Harvest("shadevine", 15, 750));
		List<DropMatcher.Outcome> outcomes = matcher.tick(18, 900);
		assertEquals("matched", outcomes.getFirst().verdict());
		assertEquals(1, book.lines().get("shadevine").drop("ETHEREAL_VINE"));
	}

	@Test
	void splitsGiveWholeItems() {
		assertEquals(Map.of("a", 2L, "b", 1L), DropMatcher.split(3, new java.util.LinkedHashMap<>(Map.of("a", 2.0, "b", 1.0))));
		Map<String, Long> shares = DropMatcher.split(7, new java.util.LinkedHashMap<>(Map.of("a", 1.0, "b", 1.0)));
		assertEquals(7L, shares.values().stream().mapToLong(Long::longValue).sum());
	}

	@Test
	void bookSurvivesSaving() {
		DropBook book = new DropBook(123);
		book.broke("shadevine", DropBook.Kind.HARVESTED);
		book.broke("shadevine", DropBook.Kind.PICKED_UP);
		book.drop("shadevine", DropBook.Kind.HARVESTED, "FERMENTO", 2);
		book.drop("shadevine", DropBook.Kind.PICKED_UP, "SHADEVINE", 1);
		book.mixed("WHEAT", 4);
		DropBook back = DropBook.fromJson(book.toJson());
		assertEquals(123, back.startedMs());
		assertEquals(1, back.lines().get("shadevine").harvested());
		assertEquals(1, back.lines().get("shadevine").pickedUp());
		assertEquals(2, back.lines().get("shadevine").drop("FERMENTO"));
		assertEquals(1L, back.lines().get("shadevine").pickupDrops().get("SHADEVINE"));
		assertEquals(Map.of("WHEAT", 4L, "FERMENTO", 2L, "SHADEVINE", 1L), back.allDrops());
	}

	@Test
	void estimateTakesOffTheInputsANormalPlotUses() {
		DropBook book = new DropBook(0);
		for (int i = 0; i < 10; i++) book.broke("zombud", DropBook.Kind.HARVESTED);
		book.drop("zombud", DropBook.Kind.HARVESTED, "ROTTEN_FLESH", 100);
		book.mixed("WHEAT", 10);
		// The Normal profile: 2.74 Cindershade a plot-day while making 48 Zombud
		Map<String, Double> perHarvest = Estimate.perHarvest(Map.of("cindershade", 2.74), 48);
		assertEquals(2.74 / 48, perHarvest.get("cindershade"), 1e-12);
		Estimate.Price price = item -> switch (item) {
			case "ROTTEN_FLESH" -> 2.0;
			case "CINDERSHADE" -> 1000.0;
			case "WHEAT" -> 6.0;
			default -> null;
		};
		Estimate.Value value = Estimate.of(book, plant -> plant.equals("zombud") ? perHarvest : Map.of(), price);
		assertEquals(200 + 60, value.gross(), 1e-9);
		assertEquals(10 * 2.74 / 48 * 1000, value.inputs(), 1e-9);
		assertEquals(value.gross() - value.inputs(), value.net(), 1e-9);
		assertTrue(value.unpriced().isEmpty());
		assertTrue(Estimate.perHarvest(Map.of("cindershade", 1.0), 0).isEmpty());
	}
}
