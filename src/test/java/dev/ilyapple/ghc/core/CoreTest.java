package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class CoreTest {
	/** The core has to run without Minecraft, so the replay and these tests can use it as plain Java. */
	@Test
	void coreUsesNoMinecraftClasses() throws IOException {
		try (Stream<Path> files = Files.walk(Path.of("src/main/java/dev/ilyapple/ghc/core"))) {
			List<Path> offenders = files.filter(path -> path.toString().endsWith(".java")).filter(path -> {
				try {
					String text = Files.readString(path);
					return text.contains("import net.minecraft") || text.contains("import com.mojang") || text.contains("import net.fabricmc");
				} catch (IOException e) {
					return true;
				}
			}).toList();
			assertTrue(offenders.isEmpty(), "core files importing Minecraft: " + offenders);
		}
	}

	@Test
	void stageSetsPrintAsRanges() {
		assertEquals("107–108", StageSet.range(107, 108).toString());
		assertEquals("{3, 8, 13}", StageSet.of(3, 8, 13).toString());
		assertEquals("{1–2, 5}", StageSet.of(1, 2, 5).toString());
		assertEquals(120, StageSet.of(120, 3).hi());
		assertEquals(StageSet.range(2, 4), StageSet.range(1, 3).offset(1));
	}

	@Test
	void theClockLearnsTicksFromGrowth() {
		long period = 6_000_000;
		GrowthClock clock = new GrowthClock(period);
		assertEquals(1, clock.onGrowthSeen(1_000));
		// More plants changing in the same tick
		assertEquals(0, clock.onGrowthSeen(5_000));
		// The next tick came a little late; the period is relearned
		assertEquals(1, clock.onGrowthSeen(1_000 + period + 60_000));
		assertTrue(clock.periodMs() > period);
		// Nothing changed look for a whole period: the tick is predicted
		assertEquals(0, clock.poll(clock.lastTickMs() + clock.periodMs()));
		assertEquals(1, clock.poll(clock.lastTickMs() + clock.periodMs() * 11 / 10));
		// The menu countdown sets the phase exactly
		long next = clock.lastTickMs() + clock.periodMs();
		assertEquals(0, clock.onCountdown(next - 1_000_000, 1_000_000 - 30_000));
		assertEquals(next - 30_000, clock.nextTickMs());
	}

	@Test
	void plantsComeFromTheSimulatorsData() {
		Plants plants = Plants.defaults();
		assertEquals(120, plants.byId("magic_jellybean").stages());
		assertEquals(14, plants.byId("all_in_aloe").readyStage());
		assertEquals(plants.byId("do_not_eat_shroom"), plants.byName("Do Not Eat Shroom"));
		assertEquals(plants.byId("all_in_aloe"), plants.byName("All-in Aloe"));
		assertEquals(3, plants.byId("snoozling").size());
		assertEquals(0, plants.byId("shadevine").stages());
	}
}
