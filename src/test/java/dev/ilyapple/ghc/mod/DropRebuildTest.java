package dev.ilyapple.ghc.mod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;

import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.drops.DropBook;
import dev.ilyapple.ghc.core.drops.LootTable;

/** Rebuilds the all-time book from the user's real drop logs (read only) under today's rules. */
class DropRebuildTest {
	@Test
	void upgradingWithoutReplayLogsPreservesTheSavedAllTimeBook(@TempDir Path config) throws IOException {
		Path drops = config.resolve("greenhouse-companion/drops");
		Files.createDirectories(drops);
		DropBook original = new DropBook(123);
		original.broke("dustgrain", DropBook.Kind.HARVESTED);
		original.drop("dustgrain", DropBook.Kind.HARVESTED, "HELIANTHUS", 1);
		com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.addProperty("version", 4);
		root.add("allTime", original.toJson());
		Files.writeString(drops.resolve("drops.json"), root.toString());
		DropRecorder recorder = new DropRecorder(new Settings(), config);
		assertEquals(original.toJson(), recorder.allTime().toJson(), "missing replay logs must not erase the saved totals");
		assertEquals(root, JsonParser.parseString(Files.readString(drops.resolve("drops.json"))));
	}

	@Test
	void upgradingWithReplayLogsReattributesAndSavesTheNewFormat(@TempDir Path config) throws IOException {
		Path drops = config.resolve("greenhouse-companion/drops");
		Files.createDirectories(drops);
		DropBook old = new DropBook(650);
		old.broke("dustgrain", DropBook.Kind.HARVESTED);
		old.unmatched("HELIANTHUS", 1);
		com.google.gson.JsonObject root = new com.google.gson.JsonObject();
		root.addProperty("version", 4);
		root.add("allTime", old.toJson());
		Files.writeString(drops.resolve("drops.json"), root.toString());
		Files.writeString(drops.resolve("log-2026-10-29.jsonl"), """
				{"t":650,"type":"harvest","plant":"dustgrain","origin":"spawned"}
				{"t":900,"type":"drop","item":"HELIANTHUS","count":1,"source":"chat","lagTicks":8}
				""");
		DropRecorder recorder = new DropRecorder(new Settings(), config);
		assertEquals(1, recorder.allTime().lines().get("dustgrain").drop("HELIANTHUS"));
		assertTrue(recorder.allTime().unmatched().isEmpty());
		assertEquals(5, JsonParser.parseString(Files.readString(drops.resolve("drops.json"))).getAsJsonObject().get("version").getAsInt());
	}

	@Test
	void oldRejectedChatDropsAreReattributedWithoutCountingSacksOrInventoryTwice(@TempDir Path dir) throws IOException {
		// Actual 29 Oct failure: a Helianthus line rejected despite a nearby common mutation harvest.
		Files.writeString(dir.resolve("log-2026-10-29.jsonl"), """
				{"t":650,"type":"harvest","plant":"dustgrain","origin":"spawned"}
				{"t":900,"type":"drop","item":"HELIANTHUS","count":1,"source":"chat","lagTicks":8,"verdict":"unmatched","shares":{},"candidates":["dustgrain"]}
				{"t":950,"type":"drop","item":"HELIANTHUS","count":1,"source":"inventory"}
				{"t":1000,"type":"drop","item":"HELIANTHUS","count":1,"source":"sacks"}
				""");
		String original = Files.readString(dir.resolve("log-2026-10-29.jsonl"));
		LootTable loot = new LootTable(java.util.Map.of("dustgrain", java.util.Map.of("WHEAT", 72.0)), java.util.Set.of("dustgrain"));
		DropBook book = DropRecorder.rebuild(dir, loot);
		assertEquals(1, book.lines().get("dustgrain").drop("HELIANTHUS"));
		assertEquals(1, book.lines().get("dustgrain").harvested());
		assertTrue(book.unmatched().isEmpty());
		assertEquals(original, Files.readString(dir.resolve("log-2026-10-29.jsonl")));
	}

	@Test
	void replayIncludesAHarvestAtTheLastEligibleTickAndHonorsResets(@TempDir Path dir) throws IOException {
		Path log = dir.resolve("log-2026-10-29.jsonl");
		Files.writeString(log, """
				{"t":900,"type":"drop","item":"HELIANTHUS","count":1,"source":"chat","lagTicks":8}
				{"t":900,"type":"harvest","plant":"shadevine","origin":"spawned"}
				""");
		LootTable loot = new LootTable(java.util.Map.of("shadevine", java.util.Map.of("CACTUS", 78.0)), java.util.Set.of("shadevine"));
		DropBook book = DropRecorder.rebuild(dir, loot);
		assertEquals(1, book.lines().get("shadevine").drop("HELIANTHUS"));
		// A reset must also discard pending drops and harvests from the preceding book.
		Files.writeString(log, """
				{"t":750,"type":"reset","book":"allTime"}
				""", java.nio.file.StandardOpenOption.APPEND);
		assertTrue(DropRecorder.rebuild(dir, loot).isEmpty());
	}

	/** Your drop logs, from {@code minecraftDir} in local.properties (build.gradle.kts); skipped without them. */
	private static final String LOGS_DIR = System.getenv("GHC_DROP_LOGS");

	@Test
	void theUsersLogsRebuildWithoutBreaksThatYieldedNothing() throws IOException {
		assumeTrue(LOGS_DIR != null && !LOGS_DIR.isBlank() && Files.isDirectory(Path.of(LOGS_DIR)), "no drop logs on this machine");
		Path logs = Path.of(LOGS_DIR);
		LootTable loot;
		try (var in = DropRebuildTest.class.getResourceAsStream(Plants.RESOURCE)) {
			loot = LootTable.of(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
		}
		DropBook book = DropRecorder.rebuild(logs, loot);
		book.lines().forEach((plant, line) -> System.out.println("[rebuild] " + plant + ": " + line.harvested() + " harvested, " + line.drops()));
		System.out.println("[rebuild] mixed " + book.mixed() + ", unmatched " + book.unmatched());
		// The placed Zombud and Cindershade the user broke yielded nothing
		assertFalse(book.lines().containsKey("zombud"));
		assertFalse(book.lines().containsKey("cindershade"));
	}
}
