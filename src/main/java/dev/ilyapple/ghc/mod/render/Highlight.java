package dev.ilyapple.ghc.mod.render;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.ilyapple.ghc.core.Growth;
import dev.ilyapple.ghc.core.Water;
import dev.ilyapple.ghc.core.overlay.Harvest;
import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.mod.GardenGrid;
import dev.ilyapple.ghc.mod.GreenhouseCompanion;
import dev.ilyapple.ghc.mod.JellybeanView;
import dev.ilyapple.ghc.mod.ModState;
import dev.ilyapple.ghc.mod.Settings;
import dev.ilyapple.ghc.mod.WaterHelper;
import dev.ilyapple.ghc.mod.ui.Theme;

/**
 * Outlines the hitboxes of every fully grown plant ready to harvest ({@link Harvest}: mutations including the commons,
 * which are grown as they spawn, and base crops; not placed items or spawns kept as layout inputs) in green, and of
 * every growing crop that needs water ({@link WaterHelper#need}) in yellow, or red once it is at −100; each colour is a
 * setting. Optionally through blocks (Athen's depthless boxes), because some mutations' hitboxes are odd: an armor
 * stand to one side, a click block hidden under a cactus. Boxes are the plant's armor stands (a crop made only of
 * blocks gets its bottom block), cut off at the soil so nothing is drawn underground; hidden Short Jellybean parts are
 * left out.
 */
public final class Highlight {
	private static final float LINE_WIDTH = 2.5f;
	/** The top of the greenhouse soil. */
	private static final double GROUND = GardenGrid.FLOOR_Y + 1;
	/** An outline and its colour. */
	private record Box(AABB box, int color) {}

	private static volatile List<Box> boxes = List.of();
	private static volatile int plants;
	private static volatile int soon;
	private static volatile int dried;

	private Highlight() {}

	public static int count() {
		return boxes.size();
	}

	/** How many plants are outlined as ready to harvest. */
	public static int plants() {
		return plants;
	}

	/** How many growing crops are outlined as needing water before you're back. */
	public static int needWater() {
		return soon;
	}

	/** How many are outlined as dried out (at −100). */
	public static int driedOut() {
		return dried;
	}

	/** Rebuilt after each scan of {@code corner}'s greenhouse. */
	public static void update(@Nullable ClientLevel level, BlockPos corner, ModState.@Nullable Site site, List<Entity>[] entitiesByCell) {
		Settings settings = GreenhouseCompanion.settings();
		if (level == null || site == null || !settings.highlightHarvestable && !settings.highlightWater) {
			clear();
			return;
		}
		List<Box> found = new ArrayList<>();
		int ready = 0;
		int needs = 0;
		int dry = 0;
		dev.ilyapple.ghc.core.schematic.Schematic layout = site.greenhouse().layout();
		List<Census.Plant> census = site.greenhouse().context().census().plants();
		var factors = settings.highlightWater ? site.greenhouse().water().factors(census) : java.util.Map.<Census.Plant, Double>of();
		for (Census.Plant plant : census) {
			int color;
			boolean grown = false;
			if (settings.highlightHarvestable && Harvest.ready(plant, layout, settings.harvestBaseCrops) && beanGrown(plant)) {
				grown = true;
				color = settings.grownColor;
				ready++;
			} else if (settings.highlightWater) {
				Water.Need need = WaterHelper.need(site, plant, factors);
				if (need == Water.Need.NONE) continue;
				color = WaterHelper.color(need);
				if (need == Water.Need.DRY) dry++;
				else needs++;
			} else {
				continue;
			}
			for (AABB box : outline(level, corner, plant, entitiesByCell, grown, settings)) found.add(new Box(box, color));
		}
		boxes = List.copyOf(found);
		plants = ready;
		soon = needs;
		dried = dry;
	}

	/** A Jellybean counts as grown only once its shape says so; any other plant as its stage does. */
	private static boolean beanGrown(Census.Plant plant) {
		if (!plant.id().equals(Growth.JELLYBEAN)) return true;
		JellybeanView.Bean bean = GreenhouseCompanion.jellybeans().bean(plant.cells().getFirst());
		return bean != null && bean.shape().fullyGrown();
	}

	/**
	 * The plant's outline: its armor stands only, as for the commons, so a tall crop's blocks (Wild Rose) and the hidden
	 * click blocks around it stay unboxed. Sugar cane, and cactus unless its hitbox is chosen, get their whole stalk as one
	 * unbroken box instead; a crop made only of blocks its bottom block. A grown Jellybean shows only its grown parts.
	 */
	private static List<AABB> outline(ClientLevel level, BlockPos corner, Census.Plant plant, List<Entity>[] entitiesByCell, boolean grown, Settings settings) {
		Block stalkBlock = plant.id().equals("sugar_cane") ? Blocks.SUGAR_CANE
				: plant.id().equals("cactus") && settings.cactusOutline == Settings.CactusOutline.BLOCK ? Blocks.CACTUS : null;
		if (stalkBlock != null) {
			AABB stalk = stalk(level, corner, plant.cells(), stalkBlock);
			if (stalk != null) {
				List<AABB> one = new ArrayList<>();
				add(one, stalk);
				return one;
			}
		}
		List<AABB> stands = new ArrayList<>();
		for (int cell : plant.cells()) {
			if (grown && plant.id().equals(Growth.JELLYBEAN)) {
				JellybeanView.Bean bean = GreenhouseCompanion.jellybeans().bean(cell);
				if (bean == null || !bean.shape().fullyGrown()) continue;
			}
			for (Entity entity : entitiesByCell[cell]) {
				if (entity instanceof ArmorStand stand && !stand.isMarker() && !stand.isRemoved() && !JellybeanView.hidesEntity(stand)) {
					add(stands, stand.getBoundingBox());
				}
			}
		}
		if (stands.isEmpty()) {
			// A crop that is only blocks (wheat, carrots…): its bottom block
			for (int cell : plant.cells()) {
				BlockPos pos = corner.offset(GardenGrid.cellX(cell), 1, GardenGrid.cellZ(cell));
				BlockState state = level.getBlockState(pos);
				if (state.isAir() || JellybeanView.hides(pos)) continue;
				VoxelShape shape = state.getShape(level, pos);
				if (!shape.isEmpty()) add(stands, shape.bounds().move(pos));
			}
		}
		return single(plant.id(), stands);
	}

	public static void clear() {
		boxes = List.of();
		plants = 0;
		soon = 0;
		dried = 0;
	}

	/** Crops whose model has two overlapping outlines, of which only the larger one is kept. */
	private static final java.util.Set<String> LARGEST_ONLY = java.util.Set.of("cocoa_beans", "melon", "pumpkin");

	/** One outline per part: the larger of two for Cocoa, Melon and Pumpkin, and never a box inside another. */
	private static List<AABB> single(String plant, List<AABB> boxes) {
		if (boxes.size() < 2) return boxes;
		if (LARGEST_ONLY.contains(plant)) {
			AABB largest = boxes.getFirst();
			for (AABB box : boxes) if (volume(box) > volume(largest)) largest = box;
			return List.of(largest);
		}
		List<AABB> kept = new ArrayList<>();
		for (int i = 0; i < boxes.size(); i++) {
			AABB box = boxes.get(i);
			boolean inside = false;
			for (int j = 0; j < boxes.size() && !inside; j++) {
				if (i == j) continue;
				AABB other = boxes.get(j);
				// Inside a bigger box (or the later of two equal ones)
				inside = contains(other, box) && (volume(other) > volume(box) || j < i);
			}
			if (!inside) kept.add(box);
		}
		return kept;
	}

	private static boolean contains(AABB outer, AABB inner) {
		double e = 1e-4;
		return inner.minX >= outer.minX - e && inner.minY >= outer.minY - e && inner.minZ >= outer.minZ - e
				&& inner.maxX <= outer.maxX + e && inner.maxY <= outer.maxY + e && inner.maxZ <= outer.maxZ + e;
	}

	private static double volume(AABB box) {
		return (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
	}

	/** The plant's stalk of {@code block}s, from the soil up, as one box (so the outline has no breaks between blocks). */
	private static @Nullable AABB stalk(ClientLevel level, BlockPos corner, List<Integer> cells, Block block) {
		AABB union = null;
		for (int cell : cells) {
			BlockPos base = corner.offset(GardenGrid.cellX(cell), 1, GardenGrid.cellZ(cell));
			for (int dy = 0; dy < 12; dy++) {
				BlockPos pos = base.above(dy);
				BlockState state = level.getBlockState(pos);
				if (!state.is(block)) {
					if (state.isAir() || dy > 0) break;
					continue;
				}
				VoxelShape shape = state.getShape(level, pos);
				AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
				union = union == null ? box : union.minmax(box);
			}
		}
		return union;
	}

	/** The part of a hitbox above the soil: a box reaching into the ground starts at the ground instead. */
	private static void add(List<AABB> found, AABB box) {
		if (box.maxY <= GROUND + 0.01) return;
		found.add(box.minY < GROUND ? new AABB(box.minX, GROUND, box.minZ, box.maxX, box.maxY, box.maxZ) : box);
	}

	/** A hair bigger so the lines aren't hidden in the model, except at the bottom, which stays on the ground. */
	private static AABB inflateAboveGround(AABB box) {
		AABB bigger = box.inflate(0.002);
		return bigger.minY < GROUND && box.minY >= GROUND - 1e-6 ? new AABB(bigger.minX, GROUND, bigger.minZ, bigger.maxX, bigger.maxY, bigger.maxZ) : bigger;
	}

	/** Drawn after translucent terrain, where Athen draws its boxes; through blocks only when chosen. */
	public static void render(LevelRenderContext context) {
		List<Box> current = boxes;
		if (current.isEmpty() || !(context.bufferSource() instanceof MultiBufferSource.BufferSource buffers)) return;
		CameraRenderState camera = context.levelState().cameraRenderState;
		if (camera == null || camera.pos == null) return;
		boolean through = GreenhouseCompanion.settings().highlightThroughBlocks;
		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		poseStack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
		PoseStack.Pose pose = poseStack.last();
		var fillType = through ? Depthless.FILLED : net.minecraft.client.renderer.rendertype.RenderTypes.debugFilledBox();
		VertexConsumer fill = buffers.getBuffer(fillType);
		for (Box box : current) Depthless.filled(fill, pose, inflateAboveGround(box.box()), Theme.alpha(box.color(), 0x2C));
		buffers.endBatch(fillType);
		var lineType = through ? Depthless.LINES : net.minecraft.client.renderer.rendertype.RenderTypes.lines();
		VertexConsumer lines = buffers.getBuffer(lineType);
		for (Box box : current) Depthless.outline(lines, pose, inflateAboveGround(box.box()), LINE_WIDTH, Theme.alpha(box.color(), 0xFF));
		buffers.endBatch(lineType);
		poseStack.popPose();
	}
}
