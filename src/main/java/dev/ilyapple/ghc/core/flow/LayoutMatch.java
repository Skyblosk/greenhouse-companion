package dev.ilyapple.ghc.core.flow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

/**
 * Works out which flow plot (and which of its steps) a greenhouse is running from what grows in it: each step's
 * layout is scored by how many of its placements are there and how many cells hold something else. Every step is
 * tried at all four quarter turns, so a greenhouse planted facing another way is still recognised.
 */
public final class LayoutMatch {
	/** Placements found must be at least this share of the layout's for a match to count. */
	public static final double MIN_SHARE = 0.6;
	/** The best plot must beat the next plot by this much to be picked without asking. */
	public static final double MIN_MARGIN = 0.15;

	public record Score(int plotId, int stepIndex, String stepLabel, int matched, int wrong, int expected, int rotation) {
		public double value() {
			if (expected == 0) return 0;
			return (matched - 0.5 * wrong) / expected;
		}
	}

	/** What the best match says, and whether it is clear enough to act on. */
	public record Verdict(@Nullable Score best, @Nullable Score runnerUp, boolean confident) {}

	private LayoutMatch() {}

	/** Every step of every plot, best first. {@code plantByCell} is the plant id in each occupied cell. */
	public static List<Score> rank(Flows flows, Map<Integer, String> plantByCell) {
		List<Score> scores = new ArrayList<>();
		for (Flows.Plot plot : flows.plots()) {
			for (int i = 0; i < plot.steps().size(); i++) {
				Flows.Step step = plot.steps().get(i);
				Schematic drawn = step.schematic();
				if (drawn == null || drawn.placements().isEmpty()) continue;
				for (int rotation = 0; rotation < 4; rotation++) {
					Schematic layout = drawn.rotated(rotation);
					int matched = 0;
					int wrong = 0;
					for (Map.Entry<Integer, Integer> placement : layout.placements().entrySet()) {
						SkyblockerCrops.Crop crop = SkyblockerCrops.byId(placement.getValue());
						String there = plantByCell.get(placement.getKey());
						if (crop == null || crop.simId() == null || there == null) continue;
						if (there.equals(crop.simId())) matched++;
						else wrong++;
					}
					scores.add(new Score(plot.id(), i, step.label(), matched, wrong, layout.placements().size(), rotation));
				}
			}
		}
		scores.sort(Comparator.comparingDouble(Score::value).reversed().thenComparingInt(Score::plotId).thenComparingInt(Score::stepIndex)
				.thenComparingInt(Score::rotation));
		return scores;
	}

	/** The best step, and whether it is clearly ahead of every step of the other plots. */
	public static Verdict identify(Flows flows, Map<Integer, String> plantByCell) {
		List<Score> scores = rank(flows, plantByCell);
		if (scores.isEmpty()) return new Verdict(null, null, false);
		Score best = scores.getFirst();
		Score runnerUp = null;
		for (Score score : scores) {
			// The same layouts in another plot, or another turn of a symmetric layout, don't compete
			if (score.plotId() != best.plotId() && !sameSteps(flows, best, score)) {
				runnerUp = score;
				break;
			}
		}
		// Plots running the same layouts can't be told apart, and that's fine: any of them is right
		double share = best.expected() == 0 ? 0 : (double) best.matched() / best.expected();
		boolean confident = share >= MIN_SHARE && (runnerUp == null || best.value() - runnerUp.value() >= MIN_MARGIN);
		return new Verdict(best, runnerUp, confident);
	}

	private static boolean sameSteps(Flows flows, Score a, Score b) {
		Schematic left = layout(flows, a);
		return left != null && left.sameAs(layout(flows, b));
	}

	/** The scored step's layout, turned the way it was scored. */
	public static @Nullable Schematic layout(Flows flows, Score score) {
		for (Flows.Plot plot : flows.plots()) {
			if (plot.id() != score.plotId()) continue;
			Schematic schematic = plot.steps().get(score.stepIndex()).schematic();
			return schematic == null ? null : schematic.rotated(score.rotation());
		}
		return null;
	}
}
