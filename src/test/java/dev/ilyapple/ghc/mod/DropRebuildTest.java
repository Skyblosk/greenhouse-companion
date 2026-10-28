package dev.ilyapple.ghc.mod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.drops.DropBook;
import dev.ilyapple.ghc.core.drops.LootTable;

/** Rebuilds the all-time book from the user's real drop logs (read only) under today's rules. */
class DropRebuildTest {
	/** Your drop logs, from {@code minecraftDir} in local.properties (build.gradle); skipped without them. */
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
