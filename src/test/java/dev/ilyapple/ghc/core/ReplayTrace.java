package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import dev.ilyapple.ghc.core.replay.Replay;

/** Prints one cell's replay: {@code GHF_TRACE=corner/x,z ./gradlew test --tests '*ReplayTrace'}. */
class ReplayTrace {
	@Test
	void trace() throws IOException {
		String spec = System.getenv("GHF_TRACE");
		String folder = System.getenv("GHF_RECORDINGS");
		assumeTrue(spec != null && !spec.isBlank() && folder != null, "GHF_TRACE not set");
		String[] parts = spec.split("/");
		String[] cell = parts[1].split(",");
		Path dir = Path.of(folder);
		LookTable table = LookTable.fromCalibration(Plants.defaults(), dir.resolve("calibration.json"));
		Replay replay = Replay.load(dir, table, GrowthClock.DEFAULT_PERIOD_MS);
		replay.run(table, parts[0], Integer.parseInt(cell[0]) * 10 + Integer.parseInt(cell[1]), line -> System.out.println("TRACE " + line));
	}
}
