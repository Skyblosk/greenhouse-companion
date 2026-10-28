package dev.ilyapple.ghc.core;

import com.google.gson.JsonObject;

/**
 * The shared growth clock: every greenhouse plant grows on the same tick. The mod sees a tick when a plant's look
 * changes, so the first growth change after a quiet spell marks the tick and later ones within half a period
 * belong to it. Between those, ticks are predicted from the period, which is learned from consecutive ticks seen.
 * The Crop Diagnostics "Next Stage" countdown, if the player happens to open the menu, sets the phase exactly.
 *
 * <p>One tick took 6501 s (1h48m21s) on the alpha on 16 October 2026; later readings that day and the next gave
 * 6568–6571 s, and 6890 s near the end, so the period drifts and is relearned all the time.
 */
public final class GrowthClock {
	public static final long DEFAULT_PERIOD_MS = 6_501_000;
	/** A predicted tick is applied this late (as a fraction of the period) to leave room for drift. */
	private static final double GRACE = 0.05;

	private long periodMs;
	private long lastTickMs = -1;
	private boolean lastTickSeen;

	public GrowthClock() {
		this(DEFAULT_PERIOD_MS);
	}

	public GrowthClock(long periodMs) {
		this.periodMs = periodMs;
	}

	public long periodMs() {
		return periodMs;
	}

	public long lastTickMs() {
		return lastTickMs;
	}

	public boolean synced() {
		return lastTickMs >= 0;
	}

	/** When the next tick is due, or -1 before the clock has been synced. */
	public long nextTickMs() {
		return lastTickMs < 0 ? -1 : lastTickMs + periodMs;
	}

	/** A plant's look changed by growing (not appearing or being removed). Returns how many ticks to apply. */
	public int onGrowthSeen(long ms) {
		if (lastTickMs < 0) {
			lastTickMs = ms;
			lastTickSeen = true;
			return 1;
		}
		long since = ms - lastTickMs;
		if (since < periodMs / 2) {
			// Part of the tick already applied; a seen change pins a predicted tick's time
			if (!lastTickSeen) {
				lastTickMs = Math.min(lastTickMs, ms);
				lastTickSeen = true;
			}
			return 0;
		}
		int ticks = (int) Math.max(1, Math.round((double) since / periodMs));
		if (ticks == 1 && lastTickSeen) {
			long measured = since;
			if (Math.abs(measured - periodMs) < periodMs * 0.15) periodMs = Math.round(periodMs * 0.7 + measured * 0.3);
		}
		lastTickMs = ms;
		lastTickSeen = true;
		return ticks;
	}

	/** Called regularly. Returns the predicted ticks that have come due without a look change to show them. */
	public int poll(long ms) {
		if (lastTickMs < 0) return 0;
		long since = ms - lastTickMs;
		if (since < periodMs * (1 + GRACE)) return 0;
		int ticks = (int) (since / periodMs);
		lastTickMs += ticks * periodMs;
		lastTickSeen = false;
		return ticks;
	}

	/** The menu said the next stage is {@code remainingMs} away. Returns ticks that passed unseen, if any. */
	public int onCountdown(long ms, long remainingMs) {
		long implied = ms + remainingMs - periodMs;
		if (lastTickMs < 0) {
			lastTickMs = implied;
			lastTickSeen = true;
			return 0;
		}
		long shift = implied - lastTickMs;
		if (Math.abs(shift) < periodMs / 2) {
			lastTickMs = implied;
			lastTickSeen = true;
			return 0;
		}
		int ticks = (int) Math.max(0, Math.round((double) shift / periodMs));
		lastTickMs = implied;
		lastTickSeen = true;
		return ticks;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("periodMs", periodMs);
		json.addProperty("lastTickMs", lastTickMs);
		return json;
	}

	public static GrowthClock fromJson(JsonObject json) {
		GrowthClock clock = new GrowthClock(json.has("periodMs") ? json.get("periodMs").getAsLong() : DEFAULT_PERIOD_MS);
		clock.lastTickMs = json.has("lastTickMs") ? json.get("lastTickMs").getAsLong() : -1;
		return clock;
	}
}
