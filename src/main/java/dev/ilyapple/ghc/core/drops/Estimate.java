package dev.ilyapple.ghc.core.drops;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * The drop recorder's estimated value: what the recorded drops are worth, less the input mutations those harvests used
 * up. The inputs come from the simulator's Normal profile (one plot running the layout with its inputs in stock, the
 * leaderboard's "Normal" numbers): a plot uses {@code uses[input]} of each input mutation per day while making
 * {@code made} of its mutation, so each harvest costs {@code uses / made} of every input. Base crops, Fire and Fermento
 * are free inputs and never listed. Items picked back up (placed ones) aren't income and count for nothing.
 */
public final class Estimate {
	/** A base unit's price, or {@code null} when unknown. */
	public interface Price {
		@Nullable Double of(String item);
	}

	/**
	 * One plant's estimate: {@code gross} from its drops, {@code inputs} the input mutations its harvests used,
	 * {@code unpriced} items with no known price (left out of {@code gross}).
	 */
	public record Value(double gross, double inputs, Set<String> unpriced) {
		public double net() {
			return gross - inputs;
		}
	}

	private Estimate() {}

	/** The SkyBlock item id of a simulator plant id: {@code magic_jellybean} → {@code MAGIC_JELLYBEAN}. */
	public static String itemId(String plantId) {
		return plantId.toUpperCase(Locale.ROOT);
	}

	/** Input mutations used per harvest, from a Normal-profile day: {@code uses / made}. Empty when nothing was made. */
	public static Map<String, Double> perHarvest(Map<String, Double> usesPerDay, double madePerDay) {
		if (madePerDay <= 0 || usesPerDay.isEmpty()) return Map.of();
		Map<String, Double> per = new java.util.TreeMap<>();
		usesPerDay.forEach((input, used) -> {
			if (used > 0) per.put(input, used / madePerDay);
		});
		return per;
	}

	/** One plant's line, its harvests charged {@code perHarvest} of each input. */
	public static Value of(DropBook.Line line, Map<String, Double> perHarvest, Price price) {
		Set<String> unpriced = new HashSet<>();
		double gross = worth(line.drops(), price, unpriced);
		double inputs = 0;
		for (Map.Entry<String, Double> input : perHarvest.entrySet()) {
			Double each = price.of(itemId(input.getKey()));
			if (each == null) {
				unpriced.add(itemId(input.getKey()));
				continue;
			}
			inputs += line.harvested() * input.getValue() * each;
		}
		return new Value(gross, inputs, unpriced);
	}

	/**
	 * The whole book: every plant's line, plus drops that went "mixed" or "unmatched" (real drops, just not pinned on
	 * one plant). {@code perHarvest} gives each plant's inputs per harvest.
	 */
	public static Value of(DropBook book, Function<String, Map<String, Double>> perHarvest, Price price) {
		Set<String> unpriced = new HashSet<>();
		double gross = 0;
		double inputs = 0;
		for (Map.Entry<String, DropBook.Line> entry : book.lines().entrySet()) {
			Value value = of(entry.getValue(), perHarvest.apply(entry.getKey()), price);
			gross += value.gross();
			inputs += value.inputs();
			unpriced.addAll(value.unpriced());
		}
		gross += worth(book.mixed(), price, unpriced);
		gross += worth(book.unmatched(), price, unpriced);
		return new Value(gross, inputs, unpriced);
	}

	private static double worth(Map<String, Long> items, Price price, Set<String> unpriced) {
		double total = 0;
		for (Map.Entry<String, Long> item : items.entrySet()) {
			Double each = price.of(item.getKey());
			if (each == null) unpriced.add(item.getKey());
			else total += each * item.getValue();
		}
		return total;
	}
}
