package dev.ilyapple.ghc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ModelBookTest {
	private static final ModelBook BOOK = ModelBook.parse(JsonParser.parseString("""
			{"source": "test", "plants": {"thunderling": [
			  {"stages": [1, 1], "stands": [{"dy": 0.09375, "small": true, "tex": "aaa"}]},
			  {"stages": [2, 2], "stands": [{"dy": 0.09375, "small": true, "tex": "bbb"}]},
			  {"stages": [7, 7], "stands": [{"dy": 0.09375, "small": true, "tex": "bbb"}, {"dy": -0.71875, "small": false, "tex": "ccc"}]},
			  {"stages": [9, 9], "stands": [{"dy": 0.1, "small": true, "tex": "bbb"}], "blocks": [{"y": 1, "id": "minecraft:wheat", "props": {"age": "3"}}]}
			]}}""").getAsJsonObject());

	@Test
	void matchesByTextureHeightAndSize() {
		Plants.Plant thunderling = Plants.defaults().byId("thunderling");
		assertEquals(StageSet.of(1), BOOK.stages(thunderling, print(List.of(stand(0.125, true, "aaa")), List.of())));
		assertEquals(StageSet.of(2), BOOK.stages(thunderling, print(List.of(stand(0.0625, true, "bbb")), List.of())));
		// Rounded to 1/16 the recorder sees -0.6875 for -0.71875
		assertEquals(StageSet.of(7), BOOK.stages(thunderling, print(List.of(stand(0.125, true, "bbb"), stand(-0.6875, false, "ccc")), List.of())));
	}

	@Test
	void anotherOwnHeadOrAWrongBlockIsADifferentStage() {
		Plants.Plant thunderling = Plants.defaults().byId("thunderling");
		assertNull(BOOK.stages(thunderling, print(List.of(stand(0.125, true, "aaa"), stand(0.5, true, "bbb")), List.of())));
		assertEquals(StageSet.of(9), BOOK.stages(thunderling, print(List.of(stand(0.125, true, "bbb")), List.of("minecraft:wheat[age=3]"))));
		// Stage 2's model also fits a head-only look, but the block makes stage 9 more specific
		assertEquals(StageSet.of(2), BOOK.stages(thunderling, print(List.of(stand(0.125, true, "bbb")), List.of("minecraft:wheat[age=5]"))));
	}

	@Test
	void coverageAndLookTableFallback() {
		Plants plants = Plants.defaults();
		Plants.Plant thunderling = plants.byId("thunderling");
		assertEquals(StageSet.of(1, 2, 7, 9), BOOK.covered(thunderling));
		LookTable table = LookTable.of(plants, List.of()).withModels(BOOK);
		LookTable.Match match = table.match(thunderling, print(List.of(stand(0.125, true, "aaa")), List.of()));
		assertTrue(match.known());
		assertEquals(StageSet.of(1), match.stages());
		assertTrue(table.uncovered(thunderling).contains(3));
		assertTrue(!table.uncovered(thunderling).contains(7));
	}

	@Test
	void bundledBookLoads() {
		ModelBook bundled = ModelBook.bundled();
		assertTrue(bundled.source() != null);
	}

	private static JsonObject print(List<JsonObject> stands, List<String> blocks) {
		JsonArray entities = new JsonArray();
		stands.forEach(entities::add);
		JsonArray blockArray = new JsonArray();
		blocks.forEach(blockArray::add);
		JsonObject print = new JsonObject();
		print.add("entities", entities);
		print.add("blocks", blockArray);
		return print;
	}

	private static JsonObject stand(double dy, boolean small, String tex) {
		JsonObject stand = new JsonObject();
		stand.addProperty("dy", dy);
		stand.addProperty("kind", "armor_stand");
		stand.addProperty("head", "x");
		stand.addProperty("tex", tex);
		stand.addProperty("small", small);
		return stand;
	}
}
