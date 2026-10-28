package dev.ilyapple.ghc.core.overlay;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;

import dev.ilyapple.ghc.core.Grounds;
import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

/**
 * What the in-world layout overlay shows for one greenhouse: a verdict per layout cell, worked out from the step's
 * layout (already turned the way the greenhouse is planted), what grows there and the soil under each cell.
 *
 * <p>Built on the two references the user picked. Skyblocker's greenhouse paste checks each layout cell against the
 * crop standing there; GreenEssentials adds ghost soil for the wrong ground, waits for two scans before showing a
 * missing plant, and only asks for Devourer inputs a spot still needs. On top of those it covers the greenhouse's
 * quirks:
 * <ul>
 * <li>Soils: a cell is fine on any soil its plant grows on (Shadevine on farmland or sand, see {@link Grounds}).</li>
 * <li>Big plants: a 2x2 or 3x3 plant counts once its kind stands on any cell of the footprint; cells holding only its
 *     hidden click block are part of it, not a wrong crop.</li>
 * <li>Spawns: a spawned mutation standing where the layout places the same kind is that input (the simulator's
 *     "spawns fill layout inputs"); a target counts as filled at any stage.</li>
 * <li>Empty spawn spots are fine: they get a spot label up close, never a red box.</li>
 * <li>Dead Plants anywhere, and Devourer roots, are flagged for clearing.</li>
 * <li>Devourer layouts (optional, the user's root script): a Puffercloud or Zombud is only asked for while a spot it
 *     touches is empty; otherwise its cell, and every cell the layout leaves empty, takes a free filler crop (Nether
 *     Wart on soul sand, Cocoa Beans on farmland).</li>
 * </ul>
 * Plain Java with no Minecraft classes, so it is tested against the user's saved greenhouses.
 */
public final class PlotOverlay {
	/**
	 * What the player should do at a mark. Each has its own icon and colour, used for its label, its box in the
	 * greenhouse and its outline on the Flow page.
	 */
	public enum Action {
		/** Nothing stands where the layout places a plant: blue {@code + Wild Rose}. */
		PLACE("+", 0xFF60A5FA),
		/** Another plant stands where the layout places one: red {@code ✘ Cactus → Wild Rose}. */
		REMOVE("✘", 0xFFF87171),
		/** Something other than the target stands on a spawn spot: orange {@code ⊘ Cactus → Shadevine spot}. */
		CLEAR("⊘", 0xFFFB923C),
		/** The soil isn't one the cell's plant grows on: brown {@code ▼ Soil: Sand or Farmland}. */
		SOIL("▼", 0xFFC8935A),
		/** An empty spawn spot, waiting for its target: fine, yellow {@code ◇ Shadevine}, labelled up close only. */
		SPOT("◇", 0xFFFBBF24),
		/** A Devourer root to break: purple {@code ✂ Devourer Root}. */
		ROOT("✂", 0xFFC084FC);

		private final String icon;
		private final int color;

		Action(String icon, int color) {
			this.icon = icon;
			this.color = color;
		}

		public String icon() {
			return icon;
		}

		/** ARGB. */
		public int color() {
			return color;
		}

		/** Whether the mark is a problem (boxed), as opposed to information. */
		public boolean problem() {
			return this != SPOT;
		}
	}

	/**
	 * One mark. {@code anchor} and {@code size} give the footprint (cells x * 10 + z); {@code want} is the plant or soil
	 * the layout wants there, {@code present} what stands there now, {@code filler} a free crop suggested by the
	 * Devourer rule rather than the layout itself.
	 */
	public record Mark(Action action, int anchor, int size, @Nullable String want, @Nullable String present, boolean filler, String label) {
		/** Identity across scans, for showing a mark only once two scans agree. */
		public String key() {
			return action + "|" + anchor + "|" + want + "|" + present;
		}
	}

	public record Options(boolean devourerRingOnlyWhenNeeded) {
		public static final Options DEFAULT = new Options(true);
	}

	public static final String DEVOURER = "devourer";
	public static final Map<String, String> FILLER = Map.of("soul_sand", "nether_wart", "farmland", "cocoa_beans");
	private static final int SIZE = Schematic.SIZE;

	private PlotOverlay() {}

	/**
	 * @param soilAt the soil block id under a cell ({@code sand}, {@code farmland}…), or {@code null} when unknown
	 * @param roots  cells where a Devourer root stands
	 */
	public static List<Mark> plan(Schematic layout, Census census, Plants plants, IntFunction<@Nullable String> soilAt, Set<Integer> roots, Options options) {
		List<Mark> marks = new ArrayList<>();
		boolean[] covered = new boolean[SIZE * SIZE];
		Set<Integer> devourerSpots = new LinkedHashSet<>();
		for (Map.Entry<Integer, Integer> target : layout.targets().entrySet()) {
			if (DEVOURER.equals(simId(target.getValue()))) devourerSpots.add(target.getKey());
		}
		boolean devourerRule = options.devourerRingOnlyWhenNeeded() && !devourerSpots.isEmpty();
		Set<String> devourerInputs = devourerRule ? requirementIds(plants.byId(DEVOURER)) : Set.of();

		for (Map.Entry<Integer, Integer> placement : layout.placements().entrySet()) {
			int anchor = placement.getKey();
			String want = simId(placement.getValue());
			int size = SkyblockerCrops.sizeOf(placement.getValue());
			for (int cell : footprint(anchor, size)) covered[cell] = true;
			if (want == null) continue;
			Occupancy here = occupancy(census, anchor, size, want);
			if (devourerRule && devourerInputs.contains(want) && size == 1 && touchesSpot(anchor, devourerSpots) && !needed(anchor, devourerSpots, census)) {
				// No spot around it is waiting: leave a standing input alone, else a filler crop is enough
				if (here.matches()) continue;
				String filler = FILLER.get(soilAt.apply(anchor));
				if (here.empty()) {
					if (filler != null) marks.add(mark(Action.PLACE, anchor, 1, filler, null, true, plants));
				} else if (filler == null || !filler.equals(here.other())) {
					if (!FILLER.containsValue(here.other())) marks.add(mark(Action.REMOVE, anchor, 1, filler == null ? want : filler, here.other(), filler != null, plants));
				}
				continue;
			}
			if (here.matches()) continue;
			if (here.empty()) marks.add(mark(Action.PLACE, anchor, size, want, null, false, plants));
			else marks.add(mark(Action.REMOVE, anchor, size, want, here.other(), false, plants));
		}

		for (Map.Entry<Integer, Integer> target : layout.targets().entrySet()) {
			int anchor = target.getKey();
			String want = simId(target.getValue());
			int size = SkyblockerCrops.sizeOf(target.getValue());
			for (int cell : footprint(anchor, size)) covered[cell] = true;
			if (want == null) continue;
			Occupancy here = occupancy(census, anchor, size, want);
			if (here.matches()) continue;
			if (here.empty()) {
				if (!roots.contains(anchor)) marks.add(mark(Action.SPOT, anchor, size, want, null, false, plants));
			} else {
				marks.add(mark(Action.CLEAR, anchor, size, want, here.other(), false, plants));
			}
		}

		// Cells the layout leaves alone: Dead Plants are junk anywhere; Devourer layouts fill the rest
		for (int cell = 0; cell < SIZE * SIZE; cell++) {
			if (covered[cell] || census.clickBlock(cell)) continue;
			StageTracker.Tracked there = census.cell(cell);
			if (there != null && there.plant().id().equals("dead_plant")) {
				marks.add(mark(Action.REMOVE, cell, 1, null, "dead_plant", false, plants));
				continue;
			}
			if (devourerRule && there == null && !roots.contains(cell)) {
				String filler = FILLER.get(soilAt.apply(cell));
				if (filler != null) marks.add(mark(Action.PLACE, cell, 1, filler, null, true, plants));
			}
		}

		// Soil under every cell the layout uses, and its explicit ground tiles
		for (int cell = 0; cell < SIZE * SIZE; cell++) {
			Set<String> required = Grounds.required(layout, cell, plants);
			if (required == null || required.isEmpty()) continue;
			String soil = soilAt.apply(cell);
			if (soil == null || Grounds.accepts(required, soil)) continue;
			Mark mark = new Mark(Action.SOIL, cell, 1, String.join(",", required), soil, false, Action.SOIL.icon() + " " + soilLabel(required));
			marks.add(mark);
		}

		for (int cell : roots) marks.add(mark(Action.ROOT, cell, 1, null, "devourer_root", false, plants));
		return marks;
	}

	/** What stands on a footprint, seen from the layout's point of view. */
	private record Occupancy(boolean matches, boolean empty, @Nullable String other) {}

	private static Occupancy occupancy(Census census, int anchor, int size, String want) {
		boolean matches = false;
		String other = null;
		for (int cell : footprint(anchor, size)) {
			StageTracker.Tracked there = census.cell(cell);
			if (there == null || census.clickBlock(cell)) continue;
			if (there.plant().id().equals(want)) matches = true;
			else if (other == null) other = there.plant().id();
		}
		// A big plant's hidden click block can sit on a cell of a small layout plant's neighbour; only real crops count
		return new Occupancy(matches, !matches && other == null, matches ? null : other);
	}

	/** Whether a Devourer spot touching this ring cell is still waiting for a spawn. */
	private static boolean needed(int cell, Set<Integer> spots, Census census) {
		for (int spot : spots) {
			if (!adjacent(cell, spot)) continue;
			StageTracker.Tracked there = census.cell(spot);
			if (there == null || !there.plant().id().equals(DEVOURER)) return true;
		}
		return false;
	}

	private static boolean touchesSpot(int cell, Set<Integer> spots) {
		for (int spot : spots) if (adjacent(cell, spot)) return true;
		return false;
	}

	private static boolean adjacent(int a, int b) {
		int dx = Math.abs(a / SIZE - b / SIZE);
		int dz = Math.abs(a % SIZE - b % SIZE);
		return Math.max(dx, dz) == 1;
	}

	private static Set<String> requirementIds(Plants.@Nullable Plant plant) {
		Set<String> ids = new LinkedHashSet<>();
		if (plant != null) for (Plants.Requirement requirement : plant.requirements()) ids.add(requirement.id());
		return ids;
	}

	public static List<Integer> footprint(int anchor, int size) {
		List<Integer> cells = new ArrayList<>();
		int x0 = anchor / SIZE;
		int z0 = anchor % SIZE;
		for (int dx = 0; dx < size; dx++) {
			for (int dz = 0; dz < size; dz++) {
				if (x0 + dx < SIZE && z0 + dz < SIZE) cells.add(Schematic.cell(x0 + dx, z0 + dz));
			}
		}
		return cells;
	}

	private static @Nullable String simId(int cropId) {
		SkyblockerCrops.Crop crop = SkyblockerCrops.byId(cropId);
		return crop == null ? null : crop.simId();
	}

	// Labels: the action's icon, then what it's about; an arrow points at what the layout wants instead

	private static Mark mark(Action action, int anchor, int size, @Nullable String want, @Nullable String present, boolean filler, Plants plants) {
		String wanted = name(want, plants);
		String there = name(present, plants);
		String text = switch (action) {
			case PLACE -> wanted + (filler ? " (filler)" : "");
			case REMOVE -> want == null ? there : there + " → " + wanted;
			case CLEAR -> there + " → " + wanted + " spot";
			case SPOT -> wanted;
			case ROOT -> "Devourer Root";
			case SOIL -> "Soil";
		};
		String label = action.icon() + " " + text;
		return new Mark(action, anchor, size, want, present, filler, label);
	}

	public static String soilLabel(Set<String> required) {
		List<String> names = new ArrayList<>();
		for (String soil : required) names.add(title(soil));
		return "Soil: " + String.join(" or ", names);
	}

	public static String name(@Nullable String id, Plants plants) {
		if (id == null) return "";
		if (id.equals("devourer_root")) return "Devourer Root";
		Plants.Plant plant = plants.byId(id);
		return plant != null ? plant.name() : title(id);
	}

	private static String title(String id) {
		StringBuilder out = new StringBuilder();
		for (String word : id.split("_")) {
			if (word.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return out.toString();
	}

	/** A short count of the problems for the HUD, each with its action's icon: "+3 place · ✘1 remove · ▼2 soil". */
	public static String summary(List<Mark> marks) {
		Map<Action, Integer> counts = new java.util.EnumMap<>(Action.class);
		for (Mark mark : marks) if (mark.action().problem()) counts.merge(mark.action(), 1, Integer::sum);
		List<String> parts = new ArrayList<>();
		counts.forEach((action, count) -> parts.add(action.icon() + count + " " + switch (action) {
			case PLACE -> "place";
			case REMOVE -> "remove";
			case CLEAR -> "clear";
			case SOIL -> "soil";
			case ROOT -> count == 1 ? "root" : "roots";
			case SPOT -> "";
		}));
		return String.join(" · ", parts);
	}
}
