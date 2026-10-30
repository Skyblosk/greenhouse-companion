package dev.ilyapple.ghc.core.drops;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts each drop down to the harvest it came with (after GreenEssentials' {@code HarvestAttribution}).
 *
 * <ul>
 * <li>A plant seen to break is only counted as harvested once something it drops is put down to it; one that yields
 *     nothing (a placed item, a crop broken too early) is forgotten {@link #COUNT_WITHIN_MS} later.</li>
 * <li>The server breaks a plant, sends its "RARE CROP!" line and updates the inventory together; the break shows a few
 *     ticks after the line. So a chat or inventory drop belongs to the harvest seen from {@link #BEFORE_TICKS} ticks
 *     before it to {@link #AFTER_TICKS} after, the one nearest {@link #LAG_TICKS} after, among the plants that can drop
 *     it ({@link LootTable#canDrop}); common mutations are eligible for armor rare crops too.</li>
 * <li>A [Sacks] report lists what reached the sacks in about the last 30 seconds: it belongs to the harvests of the
 *     {@link #SACK_WINDOW_MS} before it.</li>
 * <li>When harvests of several plants could have made a drop, it is split between the plants that drop that item, by
 *     how much each one drops ({@link LootTable}: Shadevine's 78 Cactus a harvest against a Cactus's 132). A drop no
 *     candidate's loot table has (a rare drop, a chip) goes to the one plant there, or is "mixed" when there are
 *     several. A "RARE CROP!" line with no harvest near it in a greenhouse is "unmatched".</li>
 * </ul>
 */
public final class DropMatcher {
	/**
	 * Client ticks before and after a drop to look for its harvest. In the user's 27 Oct log the break showed 2 to 5 ticks
	 * after its "RARE CROP!" line (a 4-tick window either side missed 5 of the 63 lines), now and then 1 or 2 before.
	 */
	public static final int BEFORE_TICKS = 4;
	public static final int AFTER_TICKS = 8;
	static final int LAG_TICKS = 3;
	/** The window drops logged before 28 Oct were settled after (their log time is this many ticks after they came). */
	public static final int OLD_SETTLE_TICKS = 4;
	public static final long SACK_WINDOW_MS = 35_000;
	/** A harvest nothing was put down to by then yielded nothing. */
	public static final long COUNT_WITHIN_MS = SACK_WINDOW_MS + 5_000;
	private static final long KEEP_MS = 120_000;

	public enum Source { CHAT, INVENTORY, SACKS }

	/** A plant seen breaking at client tick {@code tick}. */
	public record Harvest(String plant, long tick, long ms) {}

	/** Something that arrived at client tick {@code tick}. {@code inGreenhouse}: the player stood in one then. */
	public record Drop(String item, long count, Source source, long tick, long ms, boolean inGreenhouse) {}

	/**
	 * What became of a drop: {@code shares} how much each plant got; {@code verdict} is {@code matched} (one plant),
	 * {@code split}, {@code mixed}, {@code unmatched} or {@code ignored}; {@code candidates} the plants that were near.
	 */
	public record Outcome(Drop drop, Map<String, Long> shares, String verdict, List<String> candidates) {}

	private static final class Pending {
		final Harvest harvest;
		boolean counted;

		Pending(Harvest harvest) {
			this.harvest = harvest;
		}
	}

	private final List<DropBook> books;
	private final LootTable loot;
	private final List<Pending> harvests = new ArrayList<>();
	private final List<Drop> waiting = new ArrayList<>();
	private final List<Harvest> nothing = new ArrayList<>();

	/** Every harvest and drop is written into each of {@code books} (this session's and all time's). */
	public DropMatcher(List<DropBook> books, LootTable loot) {
		this.books = List.copyOf(books);
		this.loot = loot;
	}

	public void harvest(Harvest harvest) {
		harvests.add(new Pending(harvest));
	}

	public void drop(Drop drop) {
		if (drop.count() > 0) waiting.add(drop);
	}

	/** Harvests that yielded nothing since the last call (not counted). */
	public List<Harvest> yieldedNothing() {
		List<Harvest> list = List.copyOf(nothing);
		nothing.clear();
		return list;
	}

	/**
	 * Settles every drop that can be settled at client tick {@code tick}: chat and inventory drops once
	 * {@link #AFTER_TICKS} ticks have passed (the break shows a few ticks after its line), sack reports at once.
	 */
	public List<Outcome> tick(long tick, long ms) {
		List<Outcome> outcomes = new ArrayList<>();
		for (Iterator<Drop> it = waiting.iterator(); it.hasNext();) {
			Drop drop = it.next();
			if (drop.source() != Source.SACKS && tick - drop.tick() < AFTER_TICKS) continue;
			it.remove();
			outcomes.add(drop.source() == Source.SACKS ? settle(drop, sackWindow(drop), false) : near(drop));
		}
		for (Iterator<Pending> it = harvests.iterator(); it.hasNext();) {
			Pending pending = it.next();
			long age = ms - pending.harvest.ms();
			if (!pending.counted && age > COUNT_WITHIN_MS) {
				nothing.add(pending.harvest);
				it.remove();
			} else if (age > KEEP_MS) {
				it.remove();
			}
		}
		return outcomes;
	}

	/** Chat and inventory: the harvests in the window, the ones nearest {@link #LAG_TICKS} after it of those that can drop it. */
	private Outcome near(Drop drop) {
		List<Pending> window = new ArrayList<>();
		for (Pending pending : harvests) {
			long after = pending.harvest.tick() - drop.tick();
			if (after >= -BEFORE_TICKS && after <= AFTER_TICKS) window.add(pending);
		}
		List<Pending> able = new ArrayList<>(window);
		able.removeIf(pending -> !loot.canDrop(pending.harvest.plant(), drop.item()));
		List<Pending> from = able.isEmpty() ? window : able;
		long best = Long.MAX_VALUE;
		for (Pending pending : from) best = Math.min(best, Math.abs(pending.harvest.tick() - drop.tick() - LAG_TICKS));
		List<Pending> nearest = new ArrayList<>();
		for (Pending pending : from) if (Math.abs(pending.harvest.tick() - drop.tick() - LAG_TICKS) == best) nearest.add(pending);
		if (nearest.isEmpty()) {
			boolean counts = drop.source() == Source.CHAT && drop.inGreenhouse();
			if (counts) for (DropBook book : books) book.unmatched(drop.item(), drop.count());
			return new Outcome(drop, Map.of(), counts ? "unmatched" : "ignored", List.of());
		}
		return settle(drop, nearest, true);
	}

	private List<Pending> sackWindow(Drop drop) {
		List<Pending> window = new ArrayList<>();
		for (Pending pending : harvests) {
			if (pending.harvest.ms() <= drop.ms() && drop.ms() - pending.harvest.ms() <= SACK_WINDOW_MS) window.add(pending);
		}
		return window;
	}

	/**
	 * Splits {@code drop} between the plants of {@code candidates} that drop it, by count × loot table weight; with no
	 * weights, a lone plant gets it (only for chat and inventory, where the harvest is pinned to the tick).
	 */
	private Outcome settle(Drop drop, List<Pending> candidates, boolean pinned) {
		Map<String, List<Pending>> byPlant = new LinkedHashMap<>();
		for (Pending pending : candidates) byPlant.computeIfAbsent(pending.harvest.plant(), id -> new ArrayList<>()).add(pending);
		List<String> plants = List.copyOf(byPlant.keySet());
		if (byPlant.isEmpty()) return new Outcome(drop, Map.of(), "ignored", List.of());
		// Base crops keep their armor restrictions; a Cactus cannot drop Ethereal Vine.
		byPlant.keySet().removeIf(plant -> !loot.canDrop(plant, drop.item()));
		if (byPlant.isEmpty()) {
			for (DropBook book : books) book.unmatched(drop.item(), drop.count());
			return new Outcome(drop, Map.of(), "unmatched", plants);
		}

		if (drop.source() == Source.CHAT && Items.RARE.contains(drop.item()) && byPlant.size() > 1) {
			for (DropBook book : books) book.mixed(drop.item(), drop.count());
			return new Outcome(drop, Map.of(), "mixed", plants);
		}

		Map<String, Double> weights = new LinkedHashMap<>();
		byPlant.forEach((plant, list) -> {
			double weight = list.size() * loot.weight(plant, drop.item());
			if (weight > 0) weights.put(plant, weight);
		});
		if (weights.isEmpty()) {
			if (pinned && byPlant.size() == 1) weights.put(byPlant.keySet().iterator().next(), 1.0);
			else {
				for (DropBook book : books) book.mixed(drop.item(), drop.count());
				return new Outcome(drop, Map.of(), "mixed", plants);
			}
		}

		Map<String, Long> shares = split(drop.count(), weights);
		shares.values().removeIf(share -> share <= 0);
		shares.forEach((plant, share) -> {
			for (Pending pending : byPlant.get(plant)) {
				if (pending.counted) continue;
				pending.counted = true;
				for (DropBook book : books) book.broke(plant, DropBook.Kind.HARVESTED);
			}
			for (DropBook book : books) book.drop(plant, DropBook.Kind.HARVESTED, drop.item(), share);
		});
		return new Outcome(drop, shares, shares.size() == 1 ? "matched" : "split", plants);
	}

	/** {@code count} split by {@code weights} in whole items (largest remainders get the rest). */
	static Map<String, Long> split(long count, Map<String, Double> weights) {
		double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
		Map<String, Long> shares = new LinkedHashMap<>();
		Map<String, Double> remainders = new LinkedHashMap<>();
		long given = 0;
		for (Map.Entry<String, Double> entry : weights.entrySet()) {
			double exact = count * entry.getValue() / total;
			long whole = (long) Math.floor(exact);
			shares.put(entry.getKey(), whole);
			remainders.put(entry.getKey(), exact - whole);
			given += whole;
		}
		List<String> order = new ArrayList<>(remainders.keySet());
		order.sort((a, b) -> Double.compare(remainders.get(b), remainders.get(a)));
		for (int i = 0; given < count; i++, given++) shares.merge(order.get(i % order.size()), 1L, Long::sum);
		return shares;
	}
}
