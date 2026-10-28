package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.ilyapple.ghc.core.replay.Replay;
import dev.ilyapple.ghc.core.replay.ReplayReport;

/**
 * Replays the stage recorder's real files and writes {@code replay-report.md}. Skipped when {@code GHF_RECORDINGS}
 * (set by the build to the Prism instance's recorder folder) doesn't hold the three files.
 */
class ReplayTest {
	@Test
	void replayRealRecording() throws IOException {
		String folder = System.getenv("GHF_RECORDINGS");
		assumeTrue(folder != null && Files.exists(Path.of(folder, "changes.jsonl")) && Files.exists(Path.of(folder, "diagnostics.jsonl"))
				&& Files.exists(Path.of(folder, "calibration.json")), "no recordings at " + folder);
		Path dir = Path.of(folder);
		Plants plants = Plants.defaults();
		LookTable table = LookTable.fromCalibration(plants, dir.resolve("calibration.json"));
		Replay replay = Replay.load(dir, table, GrowthClock.DEFAULT_PERIOD_MS);

		List<Replay.Result> inSample = replay.run(table);
		List<Replay.Result> leaveOneOut = replay.runHoldingOut(table,
				result -> observation -> result.time().equals(observation.time()),
				Replay.Result::time);
		List<Replay.Result> leaveStageOut = replay.runHoldingOut(table,
				result -> observation -> observation.plantId().equals(result.plant()) && !result.truth().intersect(StageSet.of(observation.stage())).isEmpty(),
				result -> result.plant() + "@" + result.truth());

		// The same three runs with unknown looks left wide, to show what narrowing them to unread stages buys
		Replay wide = replay.unknownLooksAreUnread(false);
		List<Replay.Result> wideStageOut = wide.runHoldingOut(table,
				result -> observation -> observation.plantId().equals(result.plant()) && !result.truth().intersect(StageSet.of(observation.stage())).isEmpty(),
				result -> result.plant() + "@" + result.truth());

		String report = ReplayReport.markdown(replay, table, List.of(
				new ReplayReport.Run("All readings", "every reading", inSample),
				new ReplayReport.Run("Leave one out", "every reading but the one scored", leaveOneOut),
				new ReplayReport.Run("Unread stage", "nothing at the scored reading's plant and stage, as if it had never been read", leaveStageOut),
				new ReplayReport.Run("All readings, wide unknowns", "every reading; unknown looks not narrowed to unread stages", wide.run(table)),
				new ReplayReport.Run("Unread stage, wide unknowns", "as Unread stage; unknown looks not narrowed", wideStageOut)),
				folder);
		String out = System.getenv("GHF_REPORT");
		if (out != null) Files.writeString(Path.of(out), report, StandardCharsets.UTF_8);
		System.out.println(report.lines().limit(20).reduce("", (a, b) -> a + b + "\n"));

		long contained = inSample.stream().filter(Replay.Result::contains).count();
		assertTrue(contained > inSample.size() * 0.5, "the tracker should contain most readings: " + contained + "/" + inSample.size());
	}
}
