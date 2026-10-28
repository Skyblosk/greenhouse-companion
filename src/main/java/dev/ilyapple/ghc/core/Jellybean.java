package dev.ilyapple.ghc.core;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reads a Magic Jellybean's stage straight from its shape, without the calibration table.
 *
 * <p>A Jellybean is a column of sugar cane blocks with one full-size cane head per block, topped by a melon stem
 * and a small melon head. Every 12 stages it gains a block, so the number of sugar cane blocks is {@code stage / 12}
 * (its tier). Within a tier the top repeats six looks (recorded with the stage recorder, and the same as
 * MagicAddons' Jellybean model):
 *
 * <table>
 * <tr><th>Phase</th><th>Melon stem age</th><th>Melon head, above the canes</th></tr>
 * <tr><td>0–2</td><td>3</td><td>none (a bean held to the side)</td></tr>
 * <tr><td>3–4</td><td>5</td><td>+0.3125</td></tr>
 * <tr><td>5–6</td><td>7</td><td>+0.625</td></tr>
 * <tr><td>7–8</td><td>7</td><td>+0.6875</td></tr>
 * <tr><td>9–10</td><td>6</td><td>+0.8125</td></tr>
 * <tr><td>11</td><td>6</td><td>none, one cane head more than canes</td></tr>
 * </table>
 *
 * Stage 120 (fully grown) is ten sugar cane blocks with no melon stem. A placed Jellybean looks like stage 119.
 */
public final class Jellybean {
	public static final int TIER_STAGES = 12;
	public static final int MAX_STAGE = 120;
	public static final int MAX_TIER = MAX_STAGE / TIER_STAGES;
	/** The full-size cane segment's head texture. */
	public static final String CANE_TEXTURE = "c526a56b80f56a6870f891d1d46fa7f8c71494cad24e94326da84b3829417b81";
	/** The small melon head on top (and the bean held to the side at phases 0–2). */
	public static final String MELON_TEXTURE = "e3f23b34867472673a484f4baea5f51fbf93abe4d11e2808b6634970150bde24";

	/**
	 * @param tier       sugar cane blocks in the column
	 * @param stages     the stages this shape allows
	 * @param fullyGrown stage 120
	 * @param height     blocks the plant takes from the soil up (canes plus the stem)
	 */
	public record Look(int tier, StageSet stages, boolean fullyGrown, int height) {}

	private Jellybean() {}

	/** The Jellybean shape in a cell fingerprint (recorder format), or {@code null} when it isn't one. */
	public static @Nullable Look read(JsonObject fingerprint) {
		List<String> blocks = LookKeys.blocksOf(fingerprint);
		int tier = 0;
		while (tier < blocks.size() && LookKeys.blockPath(blocks.get(tier)).equals("sugar_cane")) tier++;
		int stemAge = -1;
		if (tier < blocks.size() && LookKeys.blockPath(blocks.get(tier)).equals("melon_stem")) stemAge = age(blocks.get(tier));

		int segments = 0;
		Double cap = null;
		if (fingerprint.has("entities")) {
			for (JsonElement element : fingerprint.getAsJsonArray("entities")) {
				JsonObject entity = element.getAsJsonObject();
				if (!isJellybeanPart(entity)) continue;
				boolean small = entity.has("small") && entity.get("small").getAsBoolean();
				double dy = entity.get("dy").getAsDouble();
				// The full-size melon head is the bean held to the side, maybe a neighbour's: not a segment
				if (!small && !isMelon(entity)) segments++;
				else if (small && dy > tier - 0.25) cap = dy - tier;
			}
		}
		if (stemAge < 0) {
			if (tier == MAX_TIER) return new Look(tier, StageSet.of(MAX_STAGE), true, tier);
			return null;
		}
		if (tier == 0 && segments == 0 && cap == null && stemAge != 3) return null;

		int lo;
		int hi;
		switch (stemAge) {
			case 3 -> {
				lo = 0;
				hi = 2;
			}
			case 5 -> {
				lo = 3;
				hi = 4;
			}
			case 7 -> {
				if (cap == null) {
					lo = 5;
					hi = 8;
				} else if (cap < 0.656) {
					lo = 5;
					hi = 6;
				} else {
					lo = 7;
					hi = 8;
				}
			}
			case 6 -> {
				if (segments > tier) {
					lo = 11;
					hi = 11;
				} else if (cap != null) {
					lo = 9;
					hi = 10;
				} else {
					lo = 9;
					hi = 11;
				}
			}
			default -> {
				lo = 0;
				hi = 11;
			}
		}
		int base = tier * TIER_STAGES;
		StageSet stages = StageSet.range(Math.max(1, base + lo), Math.min(MAX_STAGE - 1, base + hi));
		if (stages.isEmpty()) return null;
		return new Look(tier, stages, false, tier + 1);
	}

	/** A cane segment or melon head of a Jellybean, by head texture or name. */
	public static boolean isJellybeanPart(JsonObject entity) {
		String tex = entity.has("tex") ? entity.get("tex").getAsString() : "";
		if (tex.equals(CANE_TEXTURE) || tex.equals(MELON_TEXTURE)) return true;
		String head = entity.has("head") ? entity.get("head").getAsString() : "";
		return head.toLowerCase(java.util.Locale.ROOT).startsWith("magicjellybean");
	}

	/** The melon head (the small top, or the bean held to the side), rather than a cane segment. */
	public static boolean isMelon(JsonObject entity) {
		String tex = entity.has("tex") ? entity.get("tex").getAsString() : "";
		if (!tex.isEmpty()) return tex.equals(MELON_TEXTURE);
		String head = entity.has("head") ? entity.get("head").getAsString() : "";
		return head.toLowerCase(java.util.Locale.ROOT).equals("magicjellybean0");
	}

	private static int age(String blockState) {
		int at = blockState.indexOf("age=");
		if (at < 0) return -1;
		int end = at + 4;
		while (end < blockState.length() && Character.isDigit(blockState.charAt(end))) end++;
		try {
			return Integer.parseInt(blockState.substring(at + 4, end));
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
