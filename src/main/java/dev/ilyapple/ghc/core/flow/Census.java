package dev.ilyapple.ghc.core.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.StageSet;
import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.StageTracker.Origin;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.core.schematic.SkyblockerCrops;

/**
 * The tracked cells turned into plants, the way the simulator counts them: a 2x2 or 3x3 plant fills several cells
 * but is one plant, and a plant whose arrival wasn't seen gets its origin from the layout (in an input cell it was
 * placed or planted, anywhere else it spawned).
 */
public final class Census {
	/** One plant. {@code stages} is the in-game stage, which the simulator uses as-is for every origin. */
	public record Plant(Plants.Plant kind, List<Integer> cells, StageSet stages, Origin origin, boolean exact, boolean lookKnown) {
		public String id() {
			return kind.id();
		}

		public boolean dead() {
			return kind.id().equals("dead_plant");
		}

		/** Spawned or planted, the plants the simulator's "everything / nothing fully grown" look at. */
		public boolean grownOnPlot() {
			return !dead() && (origin == Origin.SPAWNED || origin == Origin.PLANTED);
		}
	}

	private final List<Plant> plants;
	private final Map<Integer, StageTracker.Tracked> cells = new HashMap<>();
	private final Set<Integer> clickBlocks;

	private Census(List<Plant> plants, List<StageTracker.Tracked> tracked, Set<Integer> clickBlocks) {
		this.plants = List.copyOf(plants);
		this.clickBlocks = Set.copyOf(clickBlocks);
		for (StageTracker.Tracked cell : tracked) cells.put(cell.cell(), cell);
	}

	public static Census of(List<StageTracker.Tracked> tracked, @Nullable Schematic layout) {
		Map<Integer, StageTracker.Tracked> byCell = new HashMap<>();
		for (StageTracker.Tracked cell : tracked) byCell.put(cell.cell(), cell);

		// A cell holding only a vanilla block next to a big plant is that plant's hidden click target, not a crop
		Set<Integer> skip = new HashSet<>();
		for (StageTracker.Tracked cell : tracked) {
			if (!cell.blockOnly()) continue;
			for (StageTracker.Tracked other : tracked) {
				if (other.plant().size() > 1 && Math.abs(other.x() - cell.x()) <= 2 && Math.abs(other.z() - cell.z()) <= 2) {
					skip.add(cell.cell());
					break;
				}
			}
		}

		List<Plant> plants = new ArrayList<>();
		Set<Integer> done = new HashSet<>(skip);
		for (StageTracker.Tracked cell : tracked) {
			if (done.contains(cell.cell())) continue;
			List<StageTracker.Tracked> group = new ArrayList<>();
			if (cell.plant().size() > 1) {
				// Neighbouring cells of the same big plant kind; several plants side by side share a group
				Deque<StageTracker.Tracked> queue = new ArrayDeque<>(List.of(cell));
				done.add(cell.cell());
				while (!queue.isEmpty()) {
					StageTracker.Tracked current = queue.poll();
					group.add(current);
					for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						int x = current.x() + step[0];
						int z = current.z() + step[1];
						if (x < 0 || x >= 10 || z < 0 || z >= 10) continue;
						StageTracker.Tracked next = byCell.get(Schematic.cell(x, z));
						if (next != null && !done.contains(next.cell()) && next.plant() == cell.plant()) {
							done.add(next.cell());
							queue.add(next);
						}
					}
				}
			} else {
				group.add(cell);
				done.add(cell.cell());
			}

			StageSet together = cell.plant().allStages();
			StageSet any = StageSet.EMPTY;
			boolean known = false;
			Origin origin = Origin.UNKNOWN;
			List<Integer> cellList = new ArrayList<>();
			for (StageTracker.Tracked member : group) {
				together = together.intersect(member.stages());
				any = any.union(member.stages());
				known |= member.lookKnown();
				if (origin == Origin.UNKNOWN) origin = member.origin();
				cellList.add(member.cell());
			}
			StageSet stages = together.isEmpty() ? any : together;
			if (origin == Origin.UNKNOWN) origin = guessOrigin(cell, layout);
			int footprint = cell.plant().size() * cell.plant().size();
			int count = Math.max(1, (group.size() + footprint - 1) / footprint);
			for (int i = 0; i < count; i++) plants.add(new Plant(cell.plant(), List.copyOf(cellList), stages, origin, stages.size() == 1, known));
		}
		return new Census(plants, tracked, skip);
	}

	/**
	 * Where a plant whose arrival wasn't seen came from. A mutation still growing spawned (placed ones come out fully
	 * grown). A fully grown one standing where the layout places the same kind was placed; one on a spawn spot spawned. Any other mutation that is fully grown counts as placed (placed items always
	 * are, and calling it placed is the cautious reading: it isn't offered for harvest), unless a Crop Diagnostics
	 * reading or seeing it appear says otherwise. Base crops are planted.
	 */
	private static Origin guessOrigin(StageTracker.Tracked cell, @Nullable Schematic layout) {
		if (!cell.plant().mutation()) return Origin.PLANTED;
		// Placed items come out fully grown: one still growing spawned, wherever it stands
		Origin settled = StageTracker.settle(cell.plant(), Origin.UNKNOWN, cell.stages());
		if (settled != Origin.UNKNOWN) return settled;
		if (layout != null) {
			int id = layout.at(cell.x(), cell.z());
			SkyblockerCrops.Crop crop = id > 0 ? SkyblockerCrops.byId(id) : null;
			if (crop != null && cell.plant().id().equals(crop.simId())) return Origin.PLACED;
			if (onTarget(cell.cell(), layout)) return Origin.SPAWNED;
		}
		boolean grown = !cell.stages().isEmpty() && cell.stages().lo() >= cell.plant().readyStage();
		return grown ? Origin.PLACED : Origin.SPAWNED;
	}

	/** Whether a cell lies on one of the layout's spawn spots (any cell of a big target). */
	public static boolean onTarget(int cell, Schematic layout) {
		int x = cell / 10;
		int z = cell % 10;
		for (Map.Entry<Integer, Integer> target : layout.targets().entrySet()) {
			int size = SkyblockerCrops.sizeOf(target.getValue());
			int ax = target.getKey() / 10;
			int az = target.getKey() % 10;
			if (x >= ax && x < ax + size && z >= az && z < az + size) return true;
		}
		return false;
	}

	public List<Plant> plants() {
		return plants;
	}

	/** Living plants of one kind, any origin. */
	public List<Plant> of(String id) {
		return plants.stream().filter(plant -> plant.id().equals(id) && !plant.dead()).toList();
	}

	public StageTracker.@Nullable Tracked cell(int cell) {
		return cells.get(cell);
	}

	/** A cell holding only a big plant's hidden click block (a vanilla block next to a 2x2 or 3x3 plant), not a crop. */
	public boolean clickBlock(int cell) {
		return clickBlocks.contains(cell);
	}

	/** Whether every cell of a target's footprint holds a living plant of the target's kind. */
	public boolean targetFilled(int anchor, int cropId) {
		SkyblockerCrops.Crop crop = SkyblockerCrops.byId(cropId);
		if (crop == null || crop.simId() == null) return false;
		int x0 = anchor / 10;
		int z0 = anchor % 10;
		for (int dx = 0; dx < crop.size(); dx++) {
			for (int dz = 0; dz < crop.size(); dz++) {
				if (x0 + dx >= 10 || z0 + dz >= 10) continue;
				StageTracker.Tracked cell = cells.get(Schematic.cell(x0 + dx, z0 + dz));
				if (cell == null || !cell.plant().id().equals(crop.simId())) return false;
			}
		}
		return true;
	}
}
