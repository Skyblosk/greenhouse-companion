package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class JellybeanTest {
	private static final Path CALIBRATION = Path.of("src/main/resources/assets/greenhouse-companion/calibration.json");

	/** Every Jellybean reading the stage recorder made: the shape alone must allow the stage the menu said. */
	@Test
	void shapeContainsEveryRecordedStage() throws IOException {
		Plants plants = Plants.defaults();
		List<String> wrong = new ArrayList<>();
		int checked = 0;
		for (LookTable.Observation observation : LookTable.readCalibration(plants, CALIBRATION)) {
			if (!observation.plantId().equals(Growth.JELLYBEAN)) continue;
			checked++;
			Jellybean.Look look = Jellybean.read(observation.fingerprint());
			int stage = observation.fullyGrown() ? Jellybean.MAX_STAGE : observation.stage();
			if (look == null || !look.stages().contains(stage)) wrong.add(stage + " read as " + (look == null ? "nothing" : look.stages()));
		}
		assertTrue(checked > 50, "expected the recorder's Jellybean readings, found " + checked);
		assertTrue(wrong.isEmpty(), wrong.size() + " of " + checked + " wrong: " + wrong);
	}

	@Test
	void tenCanesWithoutStemIsFullyGrown() {
		Jellybean.Look look = Jellybean.read(column(10, -1, 10, null));
		assertNotNull(look);
		assertTrue(look.fullyGrown());
		assertEquals(StageSet.of(120), look.stages());
	}

	@Test
	void phasesFromStemAndCap() {
		assertEquals(StageSet.range(48, 50), Jellybean.read(column(4, 3, 4, null)).stages());
		assertEquals(StageSet.range(51, 52), Jellybean.read(column(4, 5, 4, 0.3125)).stages());
		assertEquals(StageSet.range(53, 54), Jellybean.read(column(4, 7, 4, 0.625)).stages());
		assertEquals(StageSet.range(55, 56), Jellybean.read(column(4, 7, 4, 0.6875)).stages());
		assertEquals(StageSet.range(57, 58), Jellybean.read(column(4, 6, 4, 0.8125)).stages());
		assertEquals(StageSet.of(59), Jellybean.read(column(4, 6, 5, null)).stages());
		// A young one: no canes yet
		assertEquals(StageSet.range(1, 2), Jellybean.read(column(0, 3, 0, null)).stages());
	}

	@Test
	void lookTableTreatsEveryJellybeanShapeAsKnown() {
		Plants plants = Plants.defaults();
		LookTable empty = LookTable.of(plants, List.of());
		LookTable.Match match = empty.match(plants.byId(Growth.JELLYBEAN), column(7, 7, 7, 0.6875));
		assertTrue(match.known());
		assertEquals(StageSet.range(91, 92), match.stages());
	}

	/** A Jellybean column: {@code canes} sugar cane blocks, a melon stem of {@code stemAge} (or none), segments and a cap. */
	static JsonObject column(int canes, int stemAge, int segments, Double cap) {
		JsonArray entities = new JsonArray();
		for (int i = 0; i < segments; i++) entities.add(stand(-0.1875 + i, false, Jellybean.CANE_TEXTURE, "magicjellybean1"));
		if (cap != null) entities.add(stand(canes + cap, true, Jellybean.MELON_TEXTURE, "magicjellybean0"));
		JsonArray blocks = new JsonArray();
		for (int i = 0; i < canes; i++) blocks.add("minecraft:sugar_cane[age=0]");
		if (stemAge >= 0) blocks.add("minecraft:melon_stem[age=" + stemAge + "]");
		JsonObject print = new JsonObject();
		print.add("entities", entities);
		print.add("blocks", blocks);
		return print;
	}

	private static JsonObject stand(double dy, boolean small, String tex, String head) {
		JsonObject stand = new JsonObject();
		stand.addProperty("dy", dy);
		stand.addProperty("kind", "armor_stand");
		stand.addProperty("head", head);
		stand.addProperty("tex", tex);
		stand.addProperty("small", small);
		stand.addProperty("invisible", true);
		return stand;
	}
}
