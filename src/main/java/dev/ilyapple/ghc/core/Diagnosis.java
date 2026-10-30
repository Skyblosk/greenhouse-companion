package dev.ilyapple.ghc.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * One reading of the Crop Diagnostics menu (the Plant Diagnostics Tool's right-click), the only thing in the game that
 * states a plant's stage and state outright. The tool is never required; when the player uses it anyway, the reading
 * overrides what the tracker had worked out ({@link StageTracker#diagnose}).
 *
 * <p>Wording, from the user's recordings (alpha, 16 Oct 2026; 1,250 readings): slot 4 is the plant's head, named after
 * it; slot 20 has {@code Stage: 6/27}, {@code Stage: FULLY GROWN} or {@code Stage: DEAD} and {@code Times Mutated: N}, and
 * crops can also list "Decay Info"; slot 24 has {@code Status: Growing | Harvestable | Wilting | Stopped |
 * Uncollectable | DEAD}. Every "Uncollectable" reading was a fully grown plant that had helped mutations spawn: a
 * placed item, which can't be harvested. Placed Magic Jellybeans read "Harvestable", so a fully grown Jellybean reading says nothing about origin.
 *
 * <p>It also states the plant's water exactly: {@code Water: -30/100}, then {@code Lasts for: 11h 44m 59s} until it
 * reaches −100 (alpha, 27 Oct 2026).
 *
 * @param plant  the plant's name (slot 4), or {@code null}
 * @param stage  the stage from {@code Stage: x/y}, or {@code null} when fully grown, dead or not shown
 * @param status the status word, lower case, or {@code null}
 * @param water  the water from {@code Water: -30/100}, or {@code null} when not shown
 */
public record Diagnosis(@Nullable String plant, @Nullable Integer stage, boolean fullyGrown, boolean dead, @Nullable String status,
		@Nullable Integer timesMutated, boolean decayInfo, @Nullable Double water) {
	private static final Pattern STAGE = Pattern.compile("(?i)^\\s*stage\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)");
	private static final Pattern WATER = Pattern.compile("(?i)^\\s*water\\s*:\\s*(-?\\d+(?:\\.\\d+)?)\\s*/\\s*100");
	private static final Pattern STAGE_WORD = Pattern.compile("(?i)^\\s*stage\\s*:\\s*(fully grown|dead)");
	private static final Pattern STATUS = Pattern.compile("(?i)^\\s*status\\s*:\\s*([a-z ]+?)\\s*$");
	private static final Pattern MUTATED = Pattern.compile("(?i)^\\s*times mutated\\s*:\\s*(\\d+)");

	/**
	 * What the reading says about where the plant came from, or {@code null} when it doesn't say. "Uncollectable" means placed;
	 * "Harvestable" means spawned or planted except for Jellybeans. Decay information also appears on spawned crops.
	 * A plant still growing wasn't placed, since placed items come out fully grown. A fully grown Magic Jellybean
	 * that is "Harvestable" can be either, so that reading leaves its origin alone.
	 *
	 * @param grown whether the plant is fully grown (by the menu, or its stage against the plant's ready stage)
	 */
	public StageTracker.@Nullable Origin origin(boolean mutation, boolean grown, boolean jellybean) {
		if (dead) return null;
		if ("uncollectable".equals(status)) return StageTracker.Origin.PLACED;
		if ("harvestable".equals(status) && !jellybean) return mutation ? StageTracker.Origin.SPAWNED : StageTracker.Origin.PLANTED;
		if (status == null || grown) return null;
		return mutation ? StageTracker.Origin.SPAWNED : StageTracker.Origin.PLANTED;
	}

	/** Dry or held up: "Wilting" or "Stopped". */
	public boolean stalled() {
		return "wilting".equals(status) || "stopped".equals(status);
	}

	/**
	 * Reads the menu. {@code headName} is slot 4's item name, {@code lines} every lore line of the menu (any order).
	 * Returns {@code null} when nothing about a stage or status was found.
	 */
	public static @Nullable Diagnosis parse(@Nullable String headName, List<String> lines) {
		Integer stage = null;
		boolean fullyGrown = false;
		boolean dead = false;
		String status = null;
		Integer mutated = null;
		boolean decay = false;
		Double water = null;
		for (String raw : lines) {
			String line = raw.strip();
			Matcher matcher = WATER.matcher(line);
			if (matcher.find()) {
				water = Double.parseDouble(matcher.group(1));
				continue;
			}
			matcher = STAGE.matcher(line);
			if (matcher.find()) {
				stage = Integer.parseInt(matcher.group(1));
				continue;
			}
			matcher = STAGE_WORD.matcher(line);
			if (matcher.find()) {
				if (matcher.group(1).equalsIgnoreCase("dead")) dead = true;
				else fullyGrown = true;
				continue;
			}
			matcher = STATUS.matcher(line);
			if (matcher.find()) {
				status = matcher.group(1).toLowerCase(Locale.ROOT);
				if (status.equals("dead")) dead = true;
				continue;
			}
			matcher = MUTATED.matcher(line);
			if (matcher.find()) {
				mutated = Integer.parseInt(matcher.group(1));
				continue;
			}
			if (line.equalsIgnoreCase("decay info")) decay = true;
		}
		if (stage == null && !fullyGrown && !dead && status == null) return null;
		String plant = headName == null || headName.isBlank() ? null : headName.strip();
		return new Diagnosis(plant, stage, fullyGrown, dead, status, mutated, decay, water);
	}
}
