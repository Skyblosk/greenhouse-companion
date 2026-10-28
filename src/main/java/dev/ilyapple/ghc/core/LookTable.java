package dev.ilyapple.ghc.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Look key → the stages a plant was seen at, built from the stage recorder's {@code calibration.json}.
 *
 * <p>Calibration is unfinished, so a look seen only at stage 9 may really cover 8–10. Each look therefore also
 * gets a <em>plausible</em> run: every stage between the nearest stages claimed by a different look of the same
 * plant. The tracker narrows with the plausible run, so a stage nobody has read yet can still be reached.
 *
 * <p>Magic Jellybean is folded: it grows one block taller every 12 stages and repeats the same looks one block
 * higher, so a look is keyed by its shape above the stack (the tier, {@code stage / 12}, is the number of sugar
 * cane blocks) and its stages are stored as phases {@code stage % 12}. A look read at stage 57 also identifies 93.
 * {@link Jellybean} also reads the tier and phase from the shape itself, so no Jellybean look is ever unknown.
 *
 * <p>Looks nobody has read with the recorder can still be recognised from a {@link ModelBook} (stage models from
 * other sources). The recorder's own readings win; a model only narrows them or fills a gap.
 */
public final class LookTable {
	/** One Crop Diagnostics reading. {@code fullyGrown} means the menu said FULLY GROWN rather than a number. */
	public record Observation(String plantId, String key, JsonObject fingerprint, int stage, boolean fullyGrown,
			@Nullable String time, @Nullable String cell, @Nullable String status) {}

	/**
	 * @param key       the look key, or for Jellybean the folded shape key
	 * @param seen      the stages (Jellybean: phases) read with this look
	 * @param plausible {@code seen} widened to the stages no other look has claimed around it
	 */
	public record Look(String plantId, String key, String shortId, StageSet seen, StageSet plausible, int count) {}

	/**
	 * What a live look says about a plant.
	 *
	 * @param stages  the stages the plant can be at with this look
	 * @param known   the look is in the table; when not, {@code stages} is every stage the plant has
	 * @param look    the table entry, when known
	 * @param offset  what to add to the entry's stages (Jellybean's height, 12 per tier; 0 for every other plant)
	 */
	public record Match(StageSet stages, boolean known, String foldedKey, @Nullable Look look, int offset) {}

	private final Plants plants;
	private final Map<String, Map<String, Look>> looks = new LinkedHashMap<>();
	private final List<Observation> observations;
	private final @Nullable ModelBook models;

	private LookTable(Plants plants, List<Observation> observations, @Nullable ModelBook models) {
		this.plants = plants;
		this.observations = List.copyOf(observations);
		this.models = models;
		Map<String, Map<String, StageSet>> seen = new LinkedHashMap<>();
		Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
		for (Observation observation : observations) {
			Plants.Plant plant = plants.byId(observation.plantId());
			if (plant == null) continue;
			Folded folded = fold(plant, observation.fingerprint(), observation.key());
			StageSet stages = observation.fullyGrown() ? Growth.fullyGrownStages(plant) : StageSet.of(observation.stage());
			StageSet inFoldSpace = stages.offset(-folded.offset()).intersect(foldDomain(plant));
			if (inFoldSpace.isEmpty()) continue;
			seen.computeIfAbsent(plant.id(), id -> new LinkedHashMap<>()).merge(folded.key(), inFoldSpace, StageSet::union);
			counts.computeIfAbsent(plant.id(), id -> new LinkedHashMap<>()).merge(folded.key(), 1, Integer::sum);
		}
		for (Map.Entry<String, Map<String, StageSet>> plant : seen.entrySet()) {
			StageSet domain = foldDomain(plants.byId(plant.getKey()));
			Map<String, Look> plantLooks = new LinkedHashMap<>();
			for (Map.Entry<String, StageSet> look : plant.getValue().entrySet()) {
				StageSet others = StageSet.EMPTY;
				for (Map.Entry<String, StageSet> other : plant.getValue().entrySet()) {
					if (!other.getKey().equals(look.getKey())) others = others.union(other.getValue());
				}
				StageSet own = look.getValue();
				int below = domain.lo() - 1;
				int above = domain.hi() + 1;
				for (int stage : others.toList()) {
					if (stage < own.lo()) below = Math.max(below, stage);
					if (stage > own.hi()) above = Math.min(above, stage);
				}
				StageSet plausible = StageSet.range(below + 1, above - 1).intersect(domain).union(own);
				plantLooks.put(look.getKey(), new Look(plant.getKey(), look.getKey(), LookKeys.shortId(look.getKey()), own, plausible,
						counts.get(plant.getKey()).get(look.getKey())));
			}
			looks.put(plant.getKey(), plantLooks);
		}
	}

	public static LookTable of(Plants plants, List<Observation> observations) {
		return new LookTable(plants, observations, null);
	}

	/** The same readings, also recognising looks from {@code models}. */
	public LookTable withModels(@Nullable ModelBook models) {
		return new LookTable(plants, observations, models);
	}

	public @Nullable ModelBook models() {
		return models;
	}

	/** Reads every observation in a {@code calibration.json}, re-keying each look from its saved fingerprint. */
	public static List<Observation> readCalibration(Plants plants, Path file) throws IOException {
		JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		List<Observation> observations = new ArrayList<>();
		JsonObject mutations = root.has("mutations") ? root.getAsJsonObject("mutations") : new JsonObject();
		for (Map.Entry<String, JsonElement> mutation : mutations.entrySet()) {
			Plants.Plant plant = plants.byName(mutation.getKey());
			if (plant == null) continue;
			for (Map.Entry<String, JsonElement> entry : mutation.getValue().getAsJsonObject().entrySet()) {
				JsonObject look = entry.getValue().getAsJsonObject();
				JsonObject fingerprint = look.has("fingerprint") ? look.getAsJsonObject("fingerprint") : new JsonObject();
				String key = LookKeys.keysOf(fingerprint).key();
				JsonArray list = look.has("observations") ? look.getAsJsonArray("observations") : new JsonArray();
				for (JsonElement element : list) {
					JsonObject observation = element.getAsJsonObject();
					observations.add(new Observation(plant.id(), key, fingerprint, observation.get("stage").getAsInt(),
							observation.has("fullyGrown") && observation.get("fullyGrown").getAsBoolean(),
							optional(observation, "time"), optional(observation, "cell"), optional(observation, "status")));
				}
			}
		}
		return observations;
	}

	public static LookTable fromCalibration(Plants plants, Path file) throws IOException {
		return of(plants, readCalibration(plants, file));
	}

	/** The same table without some observations, for leave-one-out checks. */
	public LookTable without(Predicate<Observation> drop) {
		return new LookTable(plants, observations.stream().filter(drop.negate()).toList(), models);
	}

	public List<Observation> observations() {
		return observations;
	}

	public Plants plants() {
		return plants;
	}

	/** Every look of one plant, keyed by (folded) look key. */
	public Map<String, Look> looksOf(String plantId) {
		return looks.getOrDefault(plantId, Map.of());
	}

	/** What a cell's current fingerprint says about the stage of the plant in it. */
	public Match match(Plants.Plant plant, JsonObject fingerprint) {
		return match(plant, fingerprint, LookKeys.keysOf(fingerprint).key());
	}

	public Match match(Plants.Plant plant, JsonObject fingerprint, String key) {
		Folded folded = fold(plant, fingerprint, key);
		Look look = looksOf(plant.id()).get(folded.key());
		if (plant.id().equals(Growth.JELLYBEAN)) {
			// The shape alone gives the tier and phase, so every Jellybean look counts as known
			Jellybean.Look shape = Jellybean.read(fingerprint);
			if (shape != null) {
				StageSet stages = shape.stages();
				if (look != null) {
					StageSet both = stages.intersect(look.plausible().offset(folded.offset()));
					if (!both.isEmpty()) stages = both;
				}
				return new Match(stages, true, folded.key(), look, folded.offset());
			}
		}
		StageSet model = models == null ? null : models.stages(plant, fingerprint);
		if (look == null && model != null) return new Match(model, true, folded.key(), null, folded.offset());
		if (look == null) return new Match(plant.allStages(), false, folded.key(), null, folded.offset());
		StageSet stages = look.plausible().offset(folded.offset()).intersect(plant.allStages());
		if (stages.isEmpty()) return new Match(model != null ? model : plant.allStages(), model != null, folded.key(), look, folded.offset());
		if (model != null) {
			StageSet both = stages.intersect(model);
			if (!both.isEmpty()) stages = both;
		}
		return new Match(stages, true, folded.key(), look, folded.offset());
	}

	/**
	 * Where {@link #match} gets a look's stages from: {@code shape} (Jellybean), {@code recorder}, {@code recorder+models}
	 * (the models narrowed it), {@code models}, or {@code unknown}.
	 */
	public String source(Plants.Plant plant, JsonObject fingerprint, String key) {
		if (plant.id().equals(Growth.JELLYBEAN) && Jellybean.read(fingerprint) != null) return "shape";
		Folded folded = fold(plant, fingerprint, key);
		boolean recorded = looksOf(plant.id()).containsKey(folded.key());
		boolean modelled = models != null && models.stages(plant, fingerprint) != null;
		if (recorded) return modelled ? "recorder+models" : "recorder";
		return modelled ? "models" : "unknown";
	}

	private final Map<String, StageSet> unread = new java.util.concurrent.ConcurrentHashMap<>();

	/** The stages of a plant that no look has been read at yet. */
	public StageSet unread(Plants.Plant plant) {
		return unread.computeIfAbsent(plant.id(), id -> computeUnread(plant));
	}

	/** The stages neither the recorder's readings nor the model book cover: what is left to record. */
	public StageSet uncovered(Plants.Plant plant) {
		StageSet left = unread(plant);
		// Jellybean is read from its shape and All-in Aloe from its "Stage N" hologram
		if (plant.id().equals(Growth.JELLYBEAN) || plant.id().equals(Growth.ALOE)) return StageSet.EMPTY;
		return models == null ? left : left.minus(models.covered(plant));
	}

	private StageSet computeUnread(Plants.Plant plant) {
		StageSet read = StageSet.EMPTY;
		for (Observation observation : observations) {
			if (!observation.plantId().equals(plant.id())) continue;
			read = read.union(observation.fullyGrown() ? Growth.fullyGrownStages(plant) : StageSet.of(observation.stage()));
		}
		if (plant.id().equals(Growth.JELLYBEAN)) {
			// A phase read at one height counts for every height; the fully grown look (120) only counts when read itself
			StageSet phases = StageSet.EMPTY;
			for (Look look : looksOf(plant.id()).values()) phases = phases.union(look.seen());
			StageSet covered = StageSet.EMPTY;
			for (int tier = 0; tier <= plant.maxStage() / 12; tier++) covered = covered.union(phases.offset(tier * 12));
			read = read.union(covered.intersect(StageSet.range(1, plant.maxStage() - 1)));
		}
		return plant.allStages().minus(read);
	}

	// Folding

	/** A look key with the stage offset its fold removed: {@code stage = offset + phase}. */
	record Folded(String key, int offset) {}

	private static StageSet foldDomain(Plants.Plant plant) {
		return plant.id().equals(Growth.JELLYBEAN) ? StageSet.range(0, 11) : plant.allStages();
	}

	static Folded fold(Plants.Plant plant, JsonObject fingerprint, String key) {
		if (!plant.id().equals(Growth.JELLYBEAN)) return new Folded(key, 0);
		List<String> blocks = LookKeys.blocksOf(fingerprint);
		int tier = (int) blocks.stream().filter(block -> LookKeys.blockPath(block).equals("sugar_cane")).count();

		// Keep only what sits above the finished segments, moved down to the stack's top
		JsonObject folded = new JsonObject();
		JsonArray entities = new JsonArray();
		if (fingerprint.has("entities")) {
			for (JsonElement element : fingerprint.getAsJsonArray("entities")) {
				JsonObject entity = element.getAsJsonObject().deepCopy();
				double dy = entity.get("dy").getAsDouble();
				if (dy <= tier - 0.5) continue;
				entity.addProperty("dy", dy - tier);
				entities.add(entity);
			}
		}
		folded.add("entities", entities);
		JsonArray otherBlocks = new JsonArray();
		blocks.stream().filter(block -> !LookKeys.blockPath(block).equals("sugar_cane")).forEach(otherBlocks::add);
		folded.add("blocks", otherBlocks);
		return new Folded("JB " + LookKeys.keysOf(folded).key(), tier * 12);
	}

	private static @Nullable String optional(JsonObject json, String member) {
		return json.has(member) && !json.get(member).isJsonNull() ? json.get(member).getAsString() : null;
	}
}
