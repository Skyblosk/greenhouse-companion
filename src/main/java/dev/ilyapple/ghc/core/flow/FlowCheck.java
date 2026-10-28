package dev.ilyapple.ghc.core.flow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.ilyapple.ghc.core.Plants;

/**
 * Checks imported flows the way the simulator's editor does, plus what can't be followed in game. Problems never
 * stop an import: they are shown next to the flow so the player knows which steps need a hand.
 */
public final class FlowCheck {
	public enum Level { ERROR, WARNING, INFO }

	public record Problem(Level level, int plotId, String where, String text) {}

	/** Exit kinds the simulator has (dev build of 26 Oct 2026). */
	public static final Set<String> KINDS = Set.of("cycles", "inventoryAtLeast", "inventoryBelow", "collectedAtLeast", "layoutShort",
			"allFullyGrown", "noneFullyGrown", "fullyGrown", "mutationHarvested", "mutationSpawned", "lowestStageAtLeast",
			"lowestStageBelow", "highestStageAtLeast", "highestStageBelow", "targetsFilled", "decayImminent", "plantDecayed",
			"stepVisits", "script", "group");

	private FlowCheck() {}

	public static List<Problem> check(Flows flows, Plants plants) {
		List<Problem> problems = new ArrayList<>();
		JsonObject raw = flows.raw();
		if (raw != null && raw.has("script") && hasSource(raw.get("script"))) {
			problems.add(new Problem(Level.WARNING, 0, "Controller script", "The controller script doesn't run in game; steps it moves have to be moved by hand."));
		}
		Set<Integer> ids = new HashSet<>();
		for (Flows.Plot plot : flows.plots()) {
			if (!ids.add(plot.id())) problems.add(new Problem(Level.ERROR, plot.id(), "Plot " + plot.id(), "Two plots have id " + plot.id() + "."));
			if (plot.steps().isEmpty()) {
				problems.add(new Problem(Level.ERROR, plot.id(), "Plot " + plot.id(), "This plot has no steps."));
				continue;
			}
			JsonObject plotJson = plotJson(raw, plot.id());
			if (plotJson != null && plotJson.has("script") && hasSource(plotJson.get("script"))) {
				problems.add(new Problem(Level.WARNING, plot.id(), "Plot " + plot.id(), "Its plot script doesn't run in game (breaking primed Blastberries, regrowing scaffolds…): do those by hand."));
			}
			Set<String> stepIds = new HashSet<>();
			for (Flows.Step step : plot.steps()) stepIds.add(step.id());
			for (int i = 0; i < plot.steps().size(); i++) {
				Flows.Step step = plot.steps().get(i);
				String where = "Plot " + plot.id() + " · " + step.label();
				if (step.schematic() == null) {
					problems.add(new Problem(step.layoutCode().isEmpty() ? Level.WARNING : Level.ERROR, plot.id(), where,
							step.layoutCode().isEmpty() ? "No layout: nothing to show in the world." : "Its layout code couldn't be read."));
				}
				if (step.exits().isEmpty() && (plot.loop() || i < plot.steps().size() - 1)) {
					problems.add(new Problem(Level.INFO, plot.id(), where, "No exit: the plot stays on this step until you move it."));
				}
				int targets = step.schematic() == null ? 0 : step.schematic().targets().size();
				for (Flows.Exit exit : step.exits()) {
					if (exit.to() != null && !stepIds.contains(exit.to())) {
						problems.add(new Problem(Level.ERROR, plot.id(), where, "An exit goes to step \"" + exit.to() + "\", which no longer exists."));
					}
					conditions(exit.when(), plants, plot.id(), where, targets, problems);
				}
			}
		}
		return problems;
	}

	private static void conditions(List<Flows.Condition> conditions, Plants plants, int plotId, String where, int targets, List<Problem> problems) {
		for (Flows.Condition condition : conditions) {
			String kind = condition.kind();
			if (!KINDS.contains(kind)) {
				problems.add(new Problem(Level.ERROR, plotId, where, "Unknown condition \"" + kind + "\": treated as never met."));
				continue;
			}
			switch (kind) {
				case "group" -> conditions(condition.of(), plants, plotId, where, targets, problems);
				case "decayImminent" -> problems.add(new Problem(Level.WARNING, plotId, where, "\"Something decays within\" can't be seen in game: treated as never met."));
				case "script" -> problems.add(new Problem(Level.WARNING, plotId, where, "Script conditions don't run in game: treated as never met."));
				case "inventoryAtLeast", "inventoryBelow", "collectedAtLeast" -> {
					if (plants.byId(condition.string("item")) == null) problems.add(new Problem(Level.WARNING, plotId, where, "\"" + condition.string("item") + "\" isn't an item the mod knows."));
					if (kind.equals("collectedAtLeast")) problems.add(new Problem(Level.INFO, plotId, where, "\"Collected in total\" counts from when the mod started following, not from the start of the profile."));
				}
				case "fullyGrown", "mutationSpawned", "mutationHarvested", "lowestStageAtLeast", "lowestStageBelow", "highestStageAtLeast", "highestStageBelow" -> {
					if (plants.byId(condition.mutationId()) == null) problems.add(new Problem(Level.WARNING, plotId, where, "\"" + condition.mutationId() + "\" isn't a plant the mod knows."));
				}
				case "plantDecayed" -> {
					if (plants.byId(condition.string("kindId")) == null) problems.add(new Problem(Level.WARNING, plotId, where, "\"" + condition.string("kindId") + "\" isn't a plant the mod knows."));
				}
				case "targetsFilled" -> {
					int count = condition.integer("count", 0);
					if (targets == 0) problems.add(new Problem(Level.WARNING, plotId, where, "The layout has no target cells, so \"targets filled\" can never be met."));
					else if (count > targets) problems.add(new Problem(Level.WARNING, plotId, where, "The layout only has " + targets + " targets, so " + count + " can never be filled."));
				}
				default -> {
				}
			}
		}
	}

	private static boolean hasSource(JsonElement script) {
		if (!script.isJsonObject()) return false;
		JsonObject json = script.getAsJsonObject();
		boolean enabled = !json.has("enabled") || json.get("enabled").getAsBoolean();
		return enabled && json.has("source") && !json.get("source").getAsString().isBlank();
	}

	private static JsonObject plotJson(JsonObject raw, int id) {
		if (raw == null || !raw.has("plots")) return null;
		for (JsonElement element : raw.getAsJsonArray("plots")) {
			JsonObject plot = element.getAsJsonObject();
			if (plot.has("id") && plot.get("id").getAsInt() == id) return plot;
		}
		return null;
	}
}
