package dev.ilyapple.ghc.core;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Made-up fingerprints and look tables for the tracker tests. */
final class TestPlants {
	static final Plants PLANTS = Plants.defaults();

	private TestPlants() {}

	/** A one-stand look: the head {@code head} at height {@code dy}, plus optional blocks. */
	static JsonObject look(String head, double dy, String... blocks) {
		JsonObject entity = new JsonObject();
		entity.addProperty("dy", dy);
		entity.addProperty("kind", "armor_stand");
		entity.addProperty("head", head);
		entity.addProperty("tex", "t-" + head);
		entity.addProperty("invisible", true);
		JsonArray entities = new JsonArray();
		entities.add(entity);
		JsonObject fingerprint = new JsonObject();
		fingerprint.add("entities", entities);
		JsonArray blockArray = new JsonArray();
		for (String block : blocks) blockArray.add(block);
		fingerprint.add("blocks", blockArray);
		return fingerprint;
	}

	/** An All-in Aloe with its "Stage N" hologram. */
	static JsonObject aloe(int stage) {
		JsonObject fingerprint = look("allinaloe1", 0.1875);
		JsonObject hologram = new JsonObject();
		hologram.addProperty("dy", 3.0);
		hologram.addProperty("kind", "armor_stand");
		hologram.addProperty("head", "");
		hologram.addProperty("marker", true);
		hologram.addProperty("name", "Stage " + stage);
		fingerprint.getAsJsonArray("entities").add(hologram);
		return fingerprint;
	}

	static JsonObject empty() {
		JsonObject fingerprint = new JsonObject();
		fingerprint.add("entities", new JsonArray());
		fingerprint.add("blocks", new JsonArray());
		return fingerprint;
	}

	/** Observations of {@code plantId} where look {@code heads[i]} was read at every stage in {@code stages[i]}. */
	static List<LookTable.Observation> readings(String plantId, String[] heads, int[][] stages) {
		List<LookTable.Observation> observations = new ArrayList<>();
		for (int i = 0; i < heads.length; i++) {
			JsonObject fingerprint = look(heads[i], 0.0);
			for (int stage : stages[i]) {
				observations.add(new LookTable.Observation(plantId, LookKeys.keysOf(fingerprint).key(), fingerprint, stage, false, null, null, null));
			}
		}
		return observations;
	}
}
