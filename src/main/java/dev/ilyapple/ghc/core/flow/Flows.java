package dev.ilyapple.ghc.core.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import dev.ilyapple.ghc.core.schematic.Schematic;

/**
 * SkyShards simulator flows: what the simulator's Export copies ({@code "kind": "skyshards-greenhouse-flows"}),
 * or layouts pasted as SkyShards/SkyMutations links. Every step's layout is decoded into a Skyblocker
 * {@link Schematic}.
 */
public record Flows(List<Plot> plots, Map<String, Integer> startingInventory, @Nullable JsonObject raw) {
	public static final String KIND = "skyshards-greenhouse-flows";

	/** One simulated greenhouse and the steps it goes through. */
	public record Plot(int id, List<Step> steps, boolean loop, int startIndex) {
		public int indexOf(String stepId) {
			for (int i = 0; i < steps.size(); i++) {
				if (steps.get(i).id().equals(stepId)) return i;
			}
			return -1;
		}
	}

	/**
	 * @param schematic the step's layout, or {@code null} if its code couldn't be read
	 * @param fullClear the simulator clears every plant that isn't in the new layout when it enters this step
	 */
	public record Step(String id, String label, @Nullable Schematic schematic, String layoutCode, List<Exit> exits, boolean fullClear) {}

	/**
	 * Leave the step for {@code to} (or the next step when {@code null}) once {@code when} holds: all of it, or any
	 * of it when {@code match} is {@code "any"}. {@code checkOnEntry} exits are also checked the moment the step starts.
	 */
	public record Exit(@Nullable String to, List<Condition> when, boolean any, boolean checkOnEntry) {}

	/** One exit condition, exactly as the simulator stores it ({@code kind} plus its fields). */
	public record Condition(String kind, JsonObject json, List<Condition> of, boolean any) {
		public String string(String member) {
			return json.has(member) && !json.get(member).isJsonNull() ? json.get(member).getAsString() : "";
		}

		public int integer(String member, int fallback) {
			return json.has(member) && !json.get(member).isJsonNull() ? json.get(member).getAsInt() : fallback;
		}

		public String mutationId() {
			return string("mutationId");
		}
	}

	/**
	 * The flows text with one exit condition's amount changed: plot {@code plotId}, step {@code stepId}, exit
	 * {@code exitIndex}, top-level condition {@code conditionIndex}, member {@code field} ({@link #amountField}).
	 * Everything else in the text stays as it was. Throws when the text isn't an exported flows JSON or the condition
	 * has no such amount.
	 */
	public static String withAmount(String text, int plotId, String stepId, int exitIndex, int conditionIndex, String field, int value) {
		JsonObject root = JsonParser.parseString(text.strip()).getAsJsonObject();
		for (var plotElement : root.getAsJsonArray("plots")) {
			JsonObject plot = plotElement.getAsJsonObject();
			if (plot.get("id").getAsInt() != plotId) continue;
			for (var stepElement : plot.getAsJsonObject("flow").getAsJsonArray("steps")) {
				JsonObject step = stepElement.getAsJsonObject();
				if (!step.get("id").getAsString().equals(stepId)) continue;
				JsonObject exit = step.getAsJsonArray("exits").get(exitIndex).getAsJsonObject();
				JsonObject json = exit.getAsJsonArray("when").get(conditionIndex).getAsJsonObject();
				String kind = json.has("kind") ? json.get("kind").getAsString() : "";
				if (!field.equals(amountField(kind))) throw new JsonParseException("That condition has no " + field);
				// Counts start at 1 (0 targets filled means every target); stock amounts may be 0
				json.addProperty(field, Math.max(field.equals("qty") || kind.equals("targetsFilled") ? 0 : 1, value));
				return root.toString();
			}
		}
		throw new JsonParseException("No plot " + plotId + " step " + stepId);
	}

	/** Changes an inventory condition's amount ({@code qty}); see {@link #withAmount}. */
	public static String withQuantity(String text, int plotId, String stepId, int exitIndex, int conditionIndex, int qty) {
		return withAmount(text, plotId, stepId, exitIndex, conditionIndex, "qty", qty);
	}

	/**
	 * The number in an exit condition the player can change, or {@code null}: the stock a plot waits for ({@code qty}),
	 * how many mutations must spawn, be harvested or be fully grown, or targets filled, before the plot moves on
	 * ({@code count}), and cycles in the step ({@code n}).
	 */
	public static @Nullable String amountField(Condition condition) {
		return amountField(condition.kind());
	}

	private static @Nullable String amountField(String kind) {
		return switch (kind) {
			case "inventoryAtLeast", "inventoryBelow", "collectedAtLeast" -> "qty";
			case "fullyGrown", "mutationHarvested", "mutationSpawned", "targetsFilled", "stepVisits" -> "count";
			case "cycles" -> "n";
			default -> null;
		};
	}

	/** Exit conditions on the shared inventory, whose amount ({@code qty}) the player can change. */
	public static boolean hasQuantity(Condition condition) {
		return "qty".equals(amountField(condition));
	}

	/**
	 * Reads whatever the player pasted: an exported flows JSON, or one or more layout links or codes, one per line.
	 * Each link becomes a one-step plot with no exits, the way the simulator loads a pasted share link.
	 */
	public static Flows parse(String text) {
		String trimmed = text.strip();
		if (trimmed.startsWith("{")) return parseJson(JsonParser.parseString(trimmed).getAsJsonObject());

		List<Plot> plots = new ArrayList<>();
		for (String line : trimmed.split("\\R")) {
			if (line.isBlank()) continue;
			Schematic schematic = Schematic.fromLink(line);
			if (schematic == null) throw new JsonParseException("Not a SkyShards or SkyMutations layout: " + abbreviate(line));
			plots.add(new Plot(plots.size() + 1, List.of(new Step("step-1", "Layout", schematic, schematic.code(), List.of(), false)), false, 0));
		}
		if (plots.isEmpty()) throw new JsonParseException("Nothing to read");
		return new Flows(plots, Map.of(), null);
	}

	/**
	 * A flows export made from one layout link per plot (the Flows page's "Plot links"): plot {@code id} grows its
	 * link's layout as one step with no exits, the way the simulator loads a pasted link. Blank links are left out, so
	 * every plot keeps the number it was typed under. Throws when a link isn't a layout or every link is blank.
	 */
	public static String fromPlotLinks(Map<Integer, String> links) {
		JsonArray plots = new JsonArray();
		for (Map.Entry<Integer, String> entry : new java.util.TreeMap<>(links).entrySet()) {
			String link = entry.getValue() == null ? "" : entry.getValue().strip();
			if (link.isEmpty()) continue;
			Schematic schematic = Schematic.fromLink(link);
			if (schematic == null) throw new JsonParseException("Plot " + entry.getKey() + " isn't a SkyShards or SkyMutations layout: " + abbreviate(link));
			JsonObject layout = new JsonObject();
			layout.addProperty("code", schematic.code());
			JsonObject step = new JsonObject();
			step.addProperty("id", "step-1");
			step.addProperty("label", "Layout");
			step.add("layout", layout);
			step.add("exits", new JsonArray());
			JsonArray steps = new JsonArray();
			steps.add(step);
			JsonObject flow = new JsonObject();
			flow.add("steps", steps);
			flow.addProperty("loop", false);
			flow.addProperty("startIndex", 0);
			JsonObject plot = new JsonObject();
			plot.addProperty("id", entry.getKey());
			plot.add("flow", flow);
			plots.add(plot);
		}
		if (plots.isEmpty()) throw new JsonParseException("Paste a layout link for at least one plot");
		JsonObject root = new JsonObject();
		root.addProperty("kind", KIND);
		root.addProperty("version", 1);
		root.add("plots", plots);
		return root.toString();
	}

	public static Flows parseJson(JsonObject root) {
		if (root.has("kind") && !KIND.equals(root.get("kind").getAsString())) {
			throw new JsonParseException("Not a SkyShards flows export (kind " + root.get("kind").getAsString() + ")");
		}
		List<Plot> plots = new ArrayList<>();
		for (JsonElement element : root.has("plots") ? root.getAsJsonArray("plots") : new JsonArray()) {
			JsonObject plot = element.getAsJsonObject();
			JsonObject flow = plot.has("flow") ? plot.getAsJsonObject("flow") : new JsonObject();
			List<Step> steps = new ArrayList<>();
			for (JsonElement stepElement : flow.has("steps") ? flow.getAsJsonArray("steps") : new JsonArray()) {
				steps.add(step(stepElement.getAsJsonObject(), steps.size()));
			}
			int startIndex = flow.has("startIndex") ? flow.get("startIndex").getAsInt() : 0;
			plots.add(new Plot(plot.has("id") ? plot.get("id").getAsInt() : plots.size() + 1, List.copyOf(steps),
					flow.has("loop") && flow.get("loop").getAsBoolean(), Math.max(0, Math.min(startIndex, Math.max(0, steps.size() - 1)))));
		}
		Map<String, Integer> inventory = new LinkedHashMap<>();
		if (root.has("startingInventory") && root.get("startingInventory").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("startingInventory").entrySet()) {
				if (entry.getValue().isJsonPrimitive()) inventory.put(entry.getKey(), entry.getValue().getAsInt());
			}
		}
		return new Flows(List.copyOf(plots), inventory, root);
	}

	private static Step step(JsonObject json, int index) {
		String code = "";
		if (json.has("layout")) {
			JsonElement layout = json.get("layout");
			if (layout.isJsonPrimitive()) code = layout.getAsString();
			else if (layout.getAsJsonObject().has("code")) code = layout.getAsJsonObject().get("code").getAsString();
		}
		List<Exit> exits = new ArrayList<>();
		for (JsonElement element : json.has("exits") ? json.getAsJsonArray("exits") : new JsonArray()) {
			JsonObject exit = element.getAsJsonObject();
			exits.add(new Exit(exit.has("to") && !exit.get("to").isJsonNull() ? exit.get("to").getAsString() : null,
					conditions(exit.has("when") ? exit.getAsJsonArray("when") : new JsonArray()),
					exit.has("match") && "any".equals(exit.get("match").getAsString()),
					exit.has("checkOnEntry") && exit.get("checkOnEntry").getAsBoolean()));
		}
		String id = json.has("id") ? json.get("id").getAsString() : "step-" + (index + 1);
		return new Step(id, json.has("label") ? json.get("label").getAsString() : "Step " + (index + 1),
				code.isEmpty() ? null : Schematic.fromLink(code), code, List.copyOf(exits),
				json.has("fullClear") && json.get("fullClear").getAsBoolean());
	}

	private static List<Condition> conditions(JsonArray array) {
		List<Condition> conditions = new ArrayList<>();
		for (JsonElement element : array) {
			JsonObject json = element.getAsJsonObject();
			String kind = json.has("kind") ? json.get("kind").getAsString() : "";
			List<Condition> of = kind.equals("group") && json.has("of") ? conditions(json.getAsJsonArray("of")) : List.of();
			conditions.add(new Condition(kind, json, of, json.has("match") && "any".equals(json.get("match").getAsString())));
		}
		return List.copyOf(conditions);
	}

	private static String abbreviate(String text) {
		String line = text.strip();
		return line.length() > 40 ? line.substring(0, 40) + "..." : line;
	}
}
