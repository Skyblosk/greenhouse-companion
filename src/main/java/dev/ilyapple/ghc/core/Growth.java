package dev.ilyapple.ghc.core;

/**
 * How a plant's stage moves on one tick of the shared growth clock. Most plants go up one stage and then stay
 * fully grown. Two don't:
 * <ul>
 * <li>Glasscorn is harvestable at 7 and 8 (the menu says FULLY GROWN at both) and goes back to 1 after 8.</li>
 * <li>All-in Aloe rolls a reset chance each time it leaves stage 4 or higher (3% at 4 up to 72% at 27, from the
 *     simulator's harvest table) and can go back to 1. Its "Stage N" hologram says which happened.</li>
 * </ul>
 */
public final class Growth {
	public static final String GLASSCORN = "glasscorn";
	public static final String ALOE = "all_in_aloe";
	public static final String JELLYBEAN = "magic_jellybean";
	/** All-in Aloe has no reset chance below this stage. */
	public static final int ALOE_FIRST_RESET_STAGE = 4;
	/** Glasscorn's menu shows FULLY GROWN from this stage. */
	public static final int GLASSCORN_HARVESTABLE = 7;

	private Growth() {}

	/** Plants that go back to an early stage on their own (Glasscorn after 8, All-in Aloe from stage 4 on). */
	public static boolean canReset(Plants.Plant plant) {
		return plant.id().equals(GLASSCORN) || plant.id().equals(ALOE);
	}

	/** The stages a plant at {@code stage} can be at after one tick. */
	public static StageSet next(Plants.Plant plant, int stage) {
		if (!plant.grows()) return StageSet.of(stage);
		int max = plant.maxStage();
		return switch (plant.id()) {
			case GLASSCORN -> StageSet.of(stage >= max ? 1 : stage + 1);
			case ALOE -> stage >= max ? StageSet.of(max)
					: stage >= ALOE_FIRST_RESET_STAGE ? StageSet.of(stage + 1, 1) : StageSet.of(stage + 1);
			default -> StageSet.of(Math.min(stage + 1, max));
		};
	}

	/**
	 * Moves every candidate forward {@code ticks} ticks. With {@code mayStall} each tick may also be missed (a dry or
	 * wilting plant), so the low end stays put while the high end moves.
	 */
	public static StageSet advance(Plants.Plant plant, StageSet stages, int ticks, boolean mayStall) {
		StageSet current = stages;
		for (int i = 0; i < ticks; i++) {
			StageSet moved = current.flatMap(stage -> next(plant, stage));
			current = mayStall ? moved.union(current) : moved;
		}
		return current;
	}

	/** The stages at which the Crop Diagnostics menu says FULLY GROWN instead of a number. */
	public static StageSet fullyGrownStages(Plants.Plant plant) {
		if (plant.id().equals(GLASSCORN)) return StageSet.range(GLASSCORN_HARVESTABLE, plant.maxStage());
		return StageSet.of(plant.maxStage());
	}
}
