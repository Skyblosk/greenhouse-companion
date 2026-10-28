package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The recorder's own key tests, run against this copy, plus every key of a real calibration file. */
class LookKeysTest {
	private static Map<String, JsonObject> sample() throws IOException {
		Map<String, JsonObject> records = new HashMap<>();
		try (InputStream in = LookKeysTest.class.getResourceAsStream("/diagnostics-sample.jsonl")) {
			new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).forEach(line -> {
				JsonObject record = JsonParser.parseString(line).getAsJsonObject();
				records.putIfAbsent(record.get("mutation").getAsString(), record);
			});
		}
		return records;
	}

	@Test
	void plantOnlyCellsKeepTheirSavedKey() throws IOException {
		for (String plant : new String[] {"Glasscorn", "Chorus Fruit"}) {
			JsonObject record = sample().get(plant);
			LookKeys.Keys keys = LookKeys.keysOf(record.getAsJsonObject("fingerprint"));
			assertEquals(record.get("key").getAsString().replaceAll(" pose=[-0-9.,]+", ""), keys.key(), plant);
			assertEquals(plant, keys.plant());
		}
	}

	@Test
	void aloeHologramGivesTheStage() throws IOException {
		LookKeys.Keys keys = LookKeys.keysOf(sample().get("All-in Aloe").getAsJsonObject("fingerprint"));
		assertEquals("AS dy=0.1875 head=allinaloe1 tex=955a5ebfc03404c361753d267f7d1664692a2da3ebc63fc3b74925015ab7171b invis"
				+ " | BLOCKS minecraft:melon_stem[age=7]/minecraft:melon_stem[age=4]", keys.key());
		assertTrue(keys.raw().contains("name=Stage 6"), keys.raw());
		assertEquals(6, keys.hologramStage());
		assertEquals("All-in Aloe", keys.plant());
	}

	@Test
	void petsAndBlankMarkersAreDropped() {
		JsonObject plantOnly = JsonParser.parseString("{\"entities\":[{\"dy\":0.0,\"kind\":\"armor_stand\",\"head\":\"snoozlingFlower2\",\"tex\":\"24c6\","
				+ "\"small\":false,\"invisible\":true,\"marker\":false,\"pose\":[0.0,0.0,0.0]}],\"blocks\":[]}").getAsJsonObject();
		JsonObject withNoise = JsonParser.parseString("{\"entities\":[{\"dy\":0.0,\"kind\":\"armor_stand\",\"head\":\"snoozlingFlower2\",\"tex\":\"24c6\","
				+ "\"small\":false,\"invisible\":true,\"marker\":false,\"pose\":[0.0,0.0,0.0]},"
				+ "{\"dy\":1.6875,\"kind\":\"armor_stand\",\"head\":\"\",\"small\":false,\"invisible\":true,\"marker\":true,\"pose\":[0.0,0.0,0.0]},"
				+ "{\"dy\":2.9375,\"kind\":\"armor_stand\",\"head\":\"\",\"small\":false,\"invisible\":true,\"marker\":true,\"pose\":[0.0,0.0,0.0],"
				+ "\"slots\":{\"mainhand\":{\"id\":\"minecraft:player_head\",\"name\":\"Player Head\",\"tex\":\"6343\"}},"
				+ "\"rightArm\":[-45.0,-135.0,0.0],\"leftArm\":[-10.0,0.0,-10.0]}],\"blocks\":[]}").getAsJsonObject();
		assertEquals(LookKeys.keysOf(plantOnly).key(), LookKeys.keysOf(withNoise).key());
		assertEquals("Snoozling", LookKeys.keysOf(withNoise).plant());
	}

	@Test
	void aBlockOnlyCellIsFlagged() throws IOException {
		LookKeys.Keys keys = LookKeys.keysOf(sample().get("Wheat").getAsJsonObject("fingerprint"));
		assertEquals("BLOCKS minecraft:wheat[age=5]", keys.key());
		assertTrue(keys.plantFromBlock());
		assertFalse(LookKeys.keysOf(sample().get("Glasscorn").getAsJsonObject("fingerprint")).plantFromBlock());
	}

	@Test
	void aCellWithOnlyAHologramIsEmpty() {
		JsonObject json = JsonParser.parseString("{\"entities\":[{\"dy\":2.0,\"kind\":\"armor_stand\",\"head\":\"\",\"small\":false,"
				+ "\"invisible\":true,\"marker\":true,\"pose\":[0.0,0.0,0.0],\"name\":\"104\"}],\"blocks\":[]}").getAsJsonObject();
		assertTrue(LookKeys.keysOf(json).isEmpty());
	}

	/** The recorder rebuilt every calibration key with its own keysOf; this copy must give the same keys. */
	@Test
	void everyCalibrationKeyMatchesTheRecorder() throws IOException {
		String folder = System.getenv("GHF_RECORDINGS");
		assumeTrue(folder != null && Files.exists(Path.of(folder, "calibration.json")), "no calibration.json");
		JsonObject root = JsonParser.parseString(Files.readString(Path.of(folder, "calibration.json"))).getAsJsonObject();
		int checked = 0;
		for (Map.Entry<String, JsonElement> plant : root.getAsJsonObject("mutations").entrySet()) {
			for (Map.Entry<String, JsonElement> look : plant.getValue().getAsJsonObject().entrySet()) {
				JsonObject fingerprint = look.getValue().getAsJsonObject().getAsJsonObject("fingerprint");
				assertEquals(look.getKey(), LookKeys.keysOf(fingerprint).key(), plant.getKey());
				checked++;
			}
		}
		assertTrue(checked > 100, "checked " + checked);
	}
}
