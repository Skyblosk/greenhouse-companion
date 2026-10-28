package dev.ilyapple.ghc.core.replay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import dev.ilyapple.ghc.core.LookTable;
import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.StageSet;

/** Turns replay results into the Markdown report the README links to. */
public final class ReplayReport {
	private ReplayReport() {}

	public record Run(String name, String what, List<Replay.Result> results) {}

	public static String markdown(Replay replay, LookTable table, List<Run> runs, String source) {
		StringBuilder out = new StringBuilder();
		out.append("# Stage tracker replay\n\n");
		out.append("Recording: `").append(source).append("`\n\n");
		long skips = replay.ticks().stream().filter(tick -> tick.skips() > 0).count();
		int tickCount = replay.ticks().stream().mapToInt(Replay.Tick::count).sum();
		out.append("The tracker saw only looks, right-clicks and the clock. ").append(replay.readings())
				.append(" Crop Diagnostics readings were used to score it, never as input. The clock was rebuilt from the readings' countdowns: ")
				.append(tickCount).append(" ticks in ").append(replay.ticks().size()).append(" events, ").append(skips)
				.append(" of them alpha skips.\n\n");
		out.append("A reading counts as **contained** when the tracker's stage set includes the stage the menu showed. ")
				.append("Glasscorn's FULLY GROWN counts as 7 or 8. **Width** is the span of the tracker's set (1 = exact).\n\n");

		out.append("| Run | What the look table knew | Readings | Contained | Exact | Median width | Unknown plant/cell |\n|---|---|---|---|---|---|---|\n");
		for (Run run : runs) {
			List<Replay.Result> results = run.results();
			List<Replay.Result> tracked = results.stream().filter(result -> result.tracked() != null).toList();
			long contained = results.stream().filter(Replay.Result::contains).count();
			long exact = tracked.stream().filter(result -> result.width() == 1).count();
			out.append("| ").append(run.name()).append(" | ").append(run.what()).append(" | ").append(results.size()).append(" | ")
					.append(percent(contained, results.size())).append(" | ").append(percent(exact, results.size())).append(" | ")
					.append(median(tracked.stream().mapToInt(Replay.Result::width).toArray())).append(" | ")
					.append(results.size() - tracked.size()).append(" |\n");
		}
		out.append('\n');

		Run main = runs.getFirst();
		out.append("## Width before and after the first look change (").append(main.name()).append(")\n\n");
		out.append("Before a plant's look has changed while tracked, its stage comes from the look alone (or from its spawn). After, the clock and the look change pin it.\n\n");
		out.append("| | Readings | Contained | Median width | Mean width | Exact |\n|---|---|---|---|---|---|\n");
		for (boolean changed : new boolean[] {false, true}) {
			List<Replay.Result> group = main.results().stream().filter(result -> result.tracked() != null && result.lookChanged() == changed).toList();
			int[] widths = group.stream().mapToInt(Replay.Result::width).toArray();
			out.append("| ").append(changed ? "After a look change" : "Before any look change").append(" | ").append(group.size()).append(" | ")
					.append(percent(group.stream().filter(Replay.Result::contains).count(), group.size())).append(" | ")
					.append(median(widths)).append(" | ").append(String.format("%.2f", Arrays.stream(widths).average().orElse(0))).append(" | ")
					.append(percent(Arrays.stream(widths).filter(width -> width == 1).count(), group.size())).append(" |\n");
		}
		out.append('\n');

		out.append("## By plant\n\n| Plant | Readings | ");
		for (Run run : runs) out.append(run.name()).append(" | ");
		out.append("Median width | Unread stages |\n|---|---|");
		out.append("---|".repeat(runs.size())).append("---|---|\n");
		Map<String, List<List<Replay.Result>>> byPlant = new TreeMap<>();
		for (int r = 0; r < runs.size(); r++) {
			for (Replay.Result result : runs.get(r).results()) {
				List<List<Replay.Result>> lists = byPlant.computeIfAbsent(result.plant(), plant -> {
					List<List<Replay.Result>> fresh = new ArrayList<>();
					for (int i = 0; i < runs.size(); i++) fresh.add(new ArrayList<>());
					return fresh;
				});
				lists.get(r).add(result);
			}
		}
		Plants plants = table.plants();
		for (Map.Entry<String, List<List<Replay.Result>>> entry : byPlant.entrySet()) {
			Plants.Plant plant = plants.byId(entry.getKey());
			out.append("| ").append(plant == null ? entry.getKey() : plant.name()).append(" | ").append(entry.getValue().getFirst().size()).append(" | ");
			for (List<Replay.Result> list : entry.getValue()) out.append(percent(list.stream().filter(Replay.Result::contains).count(), list.size())).append(" | ");
			out.append(median(entry.getValue().getFirst().stream().filter(result -> result.tracked() != null).mapToInt(Replay.Result::width).toArray()))
					.append(" | ").append(plant == null ? "" : unread(table, plant)).append(" |\n");
		}
		out.append('\n');

		out.append(missing(table));

		for (Run run : runs) {
			List<Replay.Result> misses = run.results().stream().filter(Predicate.not(Replay.Result::contains)).toList();
			out.append("## Misses: ").append(run.name()).append(" (").append(misses.size()).append(")\n\n");
			if (misses.isEmpty()) {
				out.append("None.\n\n");
				continue;
			}
			out.append("| Time (UTC) | Greenhouse | Cell | Plant | Menu | Tracker | Origin | Look | Look known | Look changed |\n|---|---|---|---|---|---|---|---|---|---|\n");
			for (Replay.Result miss : misses.subList(0, Math.min(60, misses.size()))) {
				Plants.Plant plant = plants.byId(miss.plant());
				out.append("| ").append(miss.time().replace('T', ' ').replace("Z", "")).append(" | ").append(miss.corner().replace("alpha.hypixel.net|", "alpha ").replace("hypixel.net|", "main ")).append(" | ")
						.append(miss.cell() / 10).append(',').append(miss.cell() % 10).append(" | ").append(plant == null ? miss.plant() : plant.name())
						.append(" | ").append(miss.truth()).append(" | ").append(miss.tracked() == null ? "not tracked" : miss.tracked().toString())
						.append(" | ").append(miss.origin() == null ? "-" : miss.origin().name().toLowerCase()).append(" | ").append(miss.lookId())
						.append(" | ").append(miss.lookKnown() ? "yes" : "no").append(" | ").append(miss.lookChanged() ? "yes" : "no").append(" |\n");
			}
			if (misses.size() > 60) out.append("\n…and ").append(misses.size() - 60).append(" more.\n");
			out.append('\n');
		}
		return out.toString();
	}

	/**
	 * What is left to read with the Plant Diagnostics Tool, for every plant that grows. Jellybean phases read at one
	 * height count for all heights; Glasscorn's FULLY GROWN covers 7 and 8; All-in Aloe needs nothing (its hologram).
	 */
	public static String missing(LookTable table) {
		StringBuilder out = new StringBuilder("## Still to calibrate\n\n");
		out.append("Stages no Crop Diagnostics reading covers yet. The tracker copes (an unread stage is reached by the clock, and an unknown look ")
				.append("is taken to be an unread stage), but each reading here makes the stages exact sooner. Grouped by rarity.\n\n");
		out.append("| Plant | Rarity | Stages | Still to read |\n|---|---|---|---|\n");
		List<String> neverRead = new ArrayList<>();
		List<Plants.Plant> plants = new ArrayList<>(table.plants().all());
		List<String> order = List.of("legendary", "epic", "rare", "uncommon", "common", "crop");
		plants.sort(java.util.Comparator.comparingInt((Plants.Plant plant) -> order.indexOf(plant.rarity())).thenComparing(Plants.Plant::name));
		for (Plants.Plant plant : plants) {
			if (!plant.grows()) continue;
			if (plant.id().equals(dev.ilyapple.ghc.core.Growth.ALOE)) continue;
			if (table.looksOf(plant.id()).isEmpty()) {
				neverRead.add(plant.name() + " (" + plant.stages() + ")");
				continue;
			}
			StageSet unread = table.unread(plant);
			if (unread.isEmpty()) continue;
			out.append("| ").append(plant.name()).append(" | ").append(plant.rarity()).append(" | ").append(plant.stages()).append(" | ")
					.append(unread.toString().replace("{", "").replace("}", "")).append(" |\n");
		}
		out.append('\n');
		if (!neverRead.isEmpty()) out.append("Never read at all: ").append(String.join(", ", neverRead)).append(".\n\n");
		return out.toString();
	}

	/** The stages of a plant no reading covers yet, folded for Jellybean. */
	public static String unread(LookTable table, Plants.Plant plant) {
		if (!plant.grows()) return table.looksOf(plant.id()).isEmpty() ? "not read" : "";
		if (table.looksOf(plant.id()).isEmpty()) return "not read";
		StageSet unread = table.unread(plant);
		return unread.isEmpty() ? "" : unread.toString().replace("{", "").replace("}", "");
	}

	private static String percent(long part, long total) {
		return total == 0 ? "-" : String.format("%.1f%% (%d)", 100.0 * part / total, part);
	}

	private static String median(int[] values) {
		if (values.length == 0) return "-";
		int[] sorted = values.clone();
		Arrays.sort(sorted);
		return Integer.toString(sorted[sorted.length / 2]);
	}
}
