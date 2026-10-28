package dev.ilyapple.ghc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * An immutable set of growth stages 0..127. A plant's stage is usually a short run ({@link #lo()}..{@link #hi()}),
 * but a look seen at stages that aren't next to each other (a Jellybean phase seen at several heights) gives gaps,
 * so this keeps the exact set rather than just the two ends.
 */
public final class StageSet {
	public static final int LIMIT = 128;
	public static final StageSet EMPTY = new StageSet(0L, 0L);

	private final long low;
	private final long high;

	private StageSet(long low, long high) {
		this.low = low;
		this.high = high;
	}

	public static StageSet of(int... stages) {
		long low = 0;
		long high = 0;
		for (int stage : stages) {
			check(stage);
			if (stage < 64) low |= 1L << stage;
			else high |= 1L << (stage - 64);
		}
		return new StageSet(low, high);
	}

	public static StageSet of(Iterable<Integer> stages) {
		StageSet set = EMPTY;
		for (int stage : stages) set = set.with(stage);
		return set;
	}

	/** Every stage from {@code from} to {@code to}, both included; empty when {@code from > to}. */
	public static StageSet range(int from, int to) {
		StageSet set = EMPTY;
		for (int stage = Math.max(0, from); stage <= Math.min(LIMIT - 1, to); stage++) set = set.with(stage);
		return set;
	}

	private static void check(int stage) {
		if (stage < 0 || stage >= LIMIT) throw new IllegalArgumentException("stage out of range: " + stage);
	}

	public StageSet with(int stage) {
		check(stage);
		return stage < 64 ? new StageSet(low | 1L << stage, high) : new StageSet(low, high | 1L << (stage - 64));
	}

	public boolean contains(int stage) {
		if (stage < 0 || stage >= LIMIT) return false;
		return stage < 64 ? (low >>> stage & 1L) != 0 : (high >>> (stage - 64) & 1L) != 0;
	}

	public boolean isEmpty() {
		return low == 0 && high == 0;
	}

	public int size() {
		return Long.bitCount(low) + Long.bitCount(high);
	}

	/** The lowest stage; -1 when empty. */
	public int lo() {
		if (low != 0) return Long.numberOfTrailingZeros(low);
		if (high != 0) return 64 + Long.numberOfTrailingZeros(high);
		return -1;
	}

	/** The highest stage; -1 when empty. */
	public int hi() {
		if (high != 0) return 127 - Long.numberOfLeadingZeros(high);
		if (low != 0) return 63 - Long.numberOfLeadingZeros(low);
		return -1;
	}

	/** {@code hi - lo + 1}, the width of the interval the set spans; 0 when empty. */
	public int width() {
		return isEmpty() ? 0 : hi() - lo() + 1;
	}

	public StageSet union(StageSet other) {
		return new StageSet(low | other.low, high | other.high);
	}

	public StageSet intersect(StageSet other) {
		return new StageSet(low & other.low, high & other.high);
	}

	public StageSet minus(StageSet other) {
		return new StageSet(low & ~other.low, high & ~other.high);
	}

	/** Every stage {@code s + offset} for {@code s} in this set, dropping any that fall outside 0..127. */
	public StageSet offset(int offset) {
		StageSet set = EMPTY;
		for (int stage : toList()) {
			int moved = stage + offset;
			if (moved >= 0 && moved < LIMIT) set = set.with(moved);
		}
		return set;
	}

	/** The union of {@code step(s)} over every stage in this set. */
	public StageSet flatMap(IntFunction<StageSet> step) {
		StageSet set = EMPTY;
		for (int stage : toList()) set = set.union(step.apply(stage));
		return set;
	}

	public List<Integer> toList() {
		List<Integer> stages = new ArrayList<>();
		for (int stage = 0; stage < LIMIT; stage++) {
			if (contains(stage)) stages.add(stage);
		}
		return stages;
	}

	/** {@code 7}, {@code 107–108}, or {@code {3, 8, 13}} when there are gaps. */
	@Override
	public String toString() {
		if (isEmpty()) return "{}";
		if (size() == 1) return Integer.toString(lo());
		if (size() == width()) return lo() + "–" + hi();
		List<String> parts = new ArrayList<>();
		List<Integer> stages = toList();
		int start = stages.getFirst();
		int previous = start;
		for (int i = 1; i <= stages.size(); i++) {
			int stage = i < stages.size() ? stages.get(i) : Integer.MIN_VALUE;
			if (stage == previous + 1) {
				previous = stage;
				continue;
			}
			parts.add(start == previous ? Integer.toString(start) : start + "–" + previous);
			start = stage;
			previous = stage;
		}
		return "{" + String.join(", ", parts) + "}";
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof StageSet set && set.low == low && set.high == high;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(low) * 31 + Long.hashCode(high);
	}
}
