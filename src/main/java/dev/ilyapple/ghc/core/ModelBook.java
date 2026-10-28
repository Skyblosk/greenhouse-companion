package dev.ilyapple.ghc.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Stage models from outside the stage recorder: for each plant, which armor stand heads (texture, small or not,
 * height above the soil) and which vanilla blocks it shows at which stages. The bundled book is converted from
 * MagicAddons' crop definitions (converted offline); it fills the stages the recorder hasn't
 * read yet. A plant bigger than one cell has a model per cell (and per turn of the plot), so each of its cells is
 * matched on its own and the census intersects them.
 *
 * <p>A live cell matches a model when every head the model lists is there at its height (to within the
 * fingerprint's 1/16 rounding), no other head with one of the plant's own textures is there, and every block the
 * model lists is there with the listed properties. Sideways offsets and head poses are ignored, the same as the
 * recorder's look keys. When several models match, the most specific ones win.
 */
public final class ModelBook {
	public static final String RESOURCE = "/assets/greenhouse-companion/models/magicaddons-stages.json";
	private static final double DY_TOLERANCE = 0.04;

	public record Stand(double dy, boolean small, @Nullable String tex, @Nullable String item) {}

	public record Block(int y, String id, Map<String, String> props) {}

	public record Model(StageSet stages, List<Stand> stands, List<Block> blocks) {
		int specificity() {
			return stands.size() * 2 + blocks.size();
		}
	}

	private final Map<String, List<Model>> models;
	private final Map<String, Set<String>> textures = new LinkedHashMap<>();
	private final String source;

	private ModelBook(Map<String, List<Model>> models, String source) {
		this.models = models;
		this.source = source;
		models.forEach((plant, list) -> {
			Set<String> own = new HashSet<>();
			for (Model model : list) for (Stand stand : model.stands()) if (stand.tex() != null) own.add(stand.tex());
			textures.put(plant, own);
		});
	}

	public static ModelBook empty() {
		return new ModelBook(Map.of(), "none");
	}

	/** The book bundled with the mod, or an empty one when it is missing. */
	public static ModelBook bundled() {
		try (InputStream in = ModelBook.class.getResourceAsStream(RESOURCE)) {
			if (in == null) return empty();
			return parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (IOException | RuntimeException e) {
			return empty();
		}
	}

	public static ModelBook parse(JsonObject root) {
		Map<String, List<Model>> models = new LinkedHashMap<>();
		JsonObject plants = root.has("plants") ? root.getAsJsonObject("plants") : new JsonObject();
		for (Map.Entry<String, JsonElement> plant : plants.entrySet()) {
			List<Model> list = new ArrayList<>();
			for (JsonElement element : plant.getValue().getAsJsonArray()) {
				JsonObject json = element.getAsJsonObject();
				var range = json.getAsJsonArray("stages");
				StageSet stages = StageSet.range(range.get(0).getAsInt(), range.get(range.size() - 1).getAsInt());
				List<Stand> stands = new ArrayList<>();
				if (json.has("stands")) {
					for (JsonElement s : json.getAsJsonArray("stands")) {
						JsonObject stand = s.getAsJsonObject();
						stands.add(new Stand(stand.get("dy").getAsDouble(), stand.has("small") && stand.get("small").getAsBoolean(),
								string(stand, "tex"), string(stand, "item")));
					}
				}
				List<Block> blocks = new ArrayList<>();
				if (json.has("blocks")) {
					for (JsonElement b : json.getAsJsonArray("blocks")) {
						JsonObject block = b.getAsJsonObject();
						Map<String, String> props = new LinkedHashMap<>();
						if (block.has("props")) block.getAsJsonObject("props").entrySet().forEach(e -> props.put(e.getKey(), e.getValue().getAsString()));
						blocks.add(new Block(block.get("y").getAsInt(), block.get("id").getAsString(), props));
					}
				}
				list.add(new Model(stages, List.copyOf(stands), List.copyOf(blocks)));
			}
			models.put(plant.getKey(), List.copyOf(list));
		}
		return new ModelBook(models, root.has("source") ? root.get("source").getAsString() : "unknown");
	}

	public String source() {
		return source;
	}

	public boolean has(String plantId) {
		return models.containsKey(plantId);
	}

	public List<Model> modelsOf(String plantId) {
		return models.getOrDefault(plantId, List.of());
	}

	/** Every stage some model of the plant covers. */
	public StageSet covered(Plants.Plant plant) {
		StageSet covered = StageSet.EMPTY;
		for (Model model : modelsOf(plant.id())) covered = covered.union(model.stages());
		return covered.intersect(plant.allStages());
	}

	/** The stages the best-matching models allow, or {@code null} when no model matches. */
	public @Nullable StageSet stages(Plants.Plant plant, JsonObject fingerprint) {
		List<Model> list = modelsOf(plant.id());
		if (list.isEmpty()) return null;
		List<JsonObject> stands = new ArrayList<>();
		if (fingerprint.has("entities")) {
			for (JsonElement element : fingerprint.getAsJsonArray("entities")) {
				JsonObject entity = element.getAsJsonObject();
				if ("armor_stand".equals(string(entity, "kind"))) stands.add(entity);
			}
		}
		List<String> blocks = LookKeys.blocksOf(fingerprint);
		Set<String> own = textures.getOrDefault(plant.id(), Set.of());

		int best = -1;
		StageSet result = null;
		for (Model model : list) {
			if (!matches(model, stands, blocks, own)) continue;
			int specificity = model.specificity();
			if (specificity > best) {
				best = specificity;
				result = model.stages();
			} else if (specificity == best) {
				result = result.union(model.stages());
			}
		}
		return result == null ? null : result.intersect(plant.allStages());
	}

	private static boolean matches(Model model, List<JsonObject> stands, List<String> blocks, Set<String> own) {
		if (model.stands().isEmpty() && model.blocks().isEmpty()) return false;
		boolean[] used = new boolean[stands.size()];
		for (Stand stand : model.stands()) {
			int found = -1;
			for (int i = 0; i < stands.size() && found < 0; i++) {
				if (!used[i] && fits(stand, stands.get(i))) found = i;
			}
			if (found < 0) return false;
			used[found] = true;
		}
		// Any other head of this plant's own textures means a different stage
		for (int i = 0; i < stands.size(); i++) {
			if (used[i]) continue;
			String tex = string(stands.get(i), "tex");
			if (tex != null && own.contains(tex)) return false;
		}
		for (Block block : model.blocks()) {
			int index = block.y() - 1;
			if (index < 0 || index >= blocks.size()) return false;
			String live = blocks.get(index);
			if (!LookKeys.blockPath(live).equals(path(block.id()))) return false;
			for (Map.Entry<String, String> prop : block.props().entrySet()) {
				if (!live.contains(prop.getKey() + "=" + prop.getValue())) return false;
			}
		}
		return true;
	}

	private static boolean fits(Stand stand, JsonObject entity) {
		if (Math.abs(entity.get("dy").getAsDouble() - stand.dy()) > DY_TOLERANCE) return false;
		boolean small = entity.has("small") && entity.get("small").getAsBoolean();
		if (small != stand.small()) return false;
		if (stand.tex() != null) return stand.tex().equals(string(entity, "tex"));
		if (stand.item() != null) {
			if (!entity.has("slots")) return false;
			for (Map.Entry<String, JsonElement> slot : entity.getAsJsonObject("slots").entrySet()) {
				JsonElement item = slot.getValue();
				String id = item.isJsonObject() && item.getAsJsonObject().has("id") ? item.getAsJsonObject().get("id").getAsString() : item.toString();
				if (id.contains(stand.item())) return true;
			}
			return false;
		}
		return true;
	}

	private static String path(String id) {
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}

	private static @Nullable String string(JsonObject json, String member) {
		return json.has(member) && !json.get(member).isJsonNull() ? json.get(member).getAsString() : null;
	}
}
