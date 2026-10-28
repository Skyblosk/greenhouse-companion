package dev.ilyapple.ghc.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import dev.ilyapple.ghc.core.Growth;
import dev.ilyapple.ghc.core.Jellybean;
import dev.ilyapple.ghc.core.StageSet;
import dev.ilyapple.ghc.core.StageTracker;

/**
 * Magic Jellybeans in the greenhouse being watched: their stage (for the label above them and for click
 * protection) and, when "Short Jellybeans" is on, which of their parts to hide so each one is drawn as its bottom
 * block only, like MagicAddons' Shorter Cane Crops.
 *
 * <p>Hidden blocks are the sugar cane and melon stem above the bottom block; {@link HiddenBlockModel} draws nothing
 * for them, so they stay real blocks with their usual hitboxes. Hidden heads are the cane segments and melon head
 * above the bottom block, plus the bean a phase 0–2 Jellybean holds out over the next cell. Only Jellybeans at
 * least one block tall are shortened; a young one already is.
 *
 * <p>Updated on the client thread after each scan; the block set is read from chunk builder threads, so it is
 * swapped as a whole.
 */
public final class JellybeanView {
	/** A segment's feet sit below the soil top; anything with feet above this is above the bottom block. */
	private static final double BOTTOM_PART_MAX_Y = GardenGrid.FLOOR_Y + 0.5;
	/** The held bean of a tier-1+ Jellybean is at least this high; a young Jellybean's melon head never is. */
	private static final double HELD_BEAN_MIN_Y = GardenGrid.FLOOR_Y + 0.85;

	/** One Jellybean as last seen. */
	public record Bean(int cell, BlockPos bottom, Jellybean.Look shape, StageSet stages, boolean exact, boolean placed) {
		public boolean fullyGrown() {
			return shape.fullyGrown() || placed;
		}

		/** Whether the shape is the one a placed Jellybean also has (stage 119), so it might be fully grown. */
		public boolean placedLook() {
			return stages.contains(Jellybean.MAX_STAGE - 1) && stages.size() == 1;
		}
	}

	/** A label to draw above a plant. */
	public record Label(Vec3 position, String text, int color) {}

	private static volatile LongSet hiddenBlocks = LongSets.EMPTY_SET;
	private static volatile IntSet hiddenEntities = IntSets.EMPTY_SET;

	private final Map<Integer, Bean> beans = new HashMap<>();
	private final Map<Integer, List<ArmorStand>> stands = new HashMap<>();
	private @Nullable BlockPos corner;
	private List<Label> labels = List.of();

	/** Whether the block at {@code pos} should be drawn as nothing. Called from chunk builder threads. */
	public static boolean hides(BlockPos pos) {
		LongSet set = hiddenBlocks;
		return !set.isEmpty() && set.contains(pos.asLong());
	}

	public static boolean hidesEntity(Entity entity) {
		IntSet set = hiddenEntities;
		return !set.isEmpty() && set.contains(entity.getId());
	}

	public @Nullable Bean bean(int cell) {
		return beans.get(cell);
	}

	public @Nullable BlockPos corner() {
		return corner;
	}

	public List<Label> labels() {
		return labels;
	}

	/** Start of a scan of {@code corner}'s greenhouse. */
	void begin(BlockPos corner) {
		if (!corner.equals(this.corner)) beans.clear();
		this.corner = corner;
		stands.clear();
	}

	/** One cell of the scan: its fingerprint and the entities in its column. */
	void cell(int cell, JsonObject print, List<Entity> entities, StageTracker.@Nullable Tracked tracked) {
		List<ArmorStand> parts = new ArrayList<>();
		for (Entity entity : entities) if (entity instanceof ArmorStand stand && isPart(stand)) parts.add(stand);
		if (!parts.isEmpty()) stands.put(cell, parts);

		Jellybean.Look shape = Jellybean.read(print);
		if (shape == null || corner == null) {
			beans.remove(cell);
			return;
		}
		StageSet stages = shape.stages();
		boolean exact = stages.size() == 1;
		boolean placed = false;
		if (tracked != null && tracked.plant().id().equals(Growth.JELLYBEAN)) {
			StageSet both = tracked.stages().intersect(stages);
			if (!both.isEmpty()) {
				stages = both;
				exact = both.size() == 1;
			}
			placed = tracked.origin() == StageTracker.Origin.PLACED;
		}
		BlockPos bottom = corner.offset(GardenGrid.cellX(cell), 1, GardenGrid.cellZ(cell));
		beans.put(cell, new Bean(cell, bottom, shape, stages, exact, placed));
	}

	/** End of a scan: works out the hidden parts and labels and redraws the sections that changed. */
	void end(@Nullable ClientLevel level, Settings settings) {
		LongOpenHashSet blocks = new LongOpenHashSet();
		IntOpenHashSet entities = new IntOpenHashSet();
		List<Label> newLabels = new ArrayList<>();
		for (Bean bean : beans.values()) {
			boolean shorten = settings.shrinkJellybeans && bean.shape().tier() >= 1;
			if (shorten) {
				for (int y = 1; y <= bean.shape().tier(); y++) blocks.add(bean.bottom().above(y).asLong());
				for (ArmorStand stand : stands.getOrDefault(bean.cell(), List.of())) if (stand.getY() > BOTTOM_PART_MAX_Y) entities.add(stand.getId());
				for (int neighbour : neighbours(bean.cell())) {
					for (ArmorStand stand : stands.getOrDefault(neighbour, List.of())) {
						if (!stand.isSmall() && isMelon(stand) && stand.getY() > HELD_BEAN_MIN_Y) entities.add(stand.getId());
					}
				}
			}
			// A placed Jellybean gets no label: it's an input, not something growing (the user found "placed" redundant)
			if (settings.stageLabels && !bean.placed()) {
				double top = shorten ? 1 : bean.shape().height();
				newLabels.add(new Label(Vec3.atBottomCenterOf(bean.bottom()).add(0, top - 0.25, 0), text(bean), color(bean)));
			}
		}
		labels = List.copyOf(newLabels);
		hiddenEntities = entities.isEmpty() ? IntSets.EMPTY_SET : entities;
		swapBlocks(level, blocks);
	}

	/** Shows everything again (left the greenhouse, or the option was turned off). */
	void clear(@Nullable ClientLevel level) {
		beans.clear();
		stands.clear();
		corner = null;
		labels = List.of();
		hiddenEntities = IntSets.EMPTY_SET;
		swapBlocks(level, new LongOpenHashSet());
	}

	private static void swapBlocks(@Nullable ClientLevel level, LongOpenHashSet blocks) {
		LongSet previous = hiddenBlocks;
		if (previous.equals(blocks)) return;
		hiddenBlocks = blocks.isEmpty() ? LongSets.EMPTY_SET : blocks;
		if (level == null) return;
		LongOpenHashSet sections = new LongOpenHashSet();
		for (long pos : blocks) if (!previous.contains(pos)) sections.add(SectionPos.blockToSection(pos));
		for (long pos : previous) if (!blocks.contains(pos)) sections.add(SectionPos.blockToSection(pos));
		for (long section : sections) level.setSectionDirtyWithNeighbors(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
	}

	public static String text(Bean bean) {
		if (bean.shape().fullyGrown()) return "✔ 120";
		StageSet stages = bean.stages();
		String stage = stages.size() == 1 ? Integer.toString(stages.lo()) : stages.lo() + "–" + stages.hi();
		return stage + " / " + Jellybean.MAX_STAGE;
	}

	public static int color(Bean bean) {
		if (bean.fullyGrown()) return 0xFF34D399;
		return bean.exact() ? 0xFFF8FAFC : 0xFFFBBF24;
	}

	private static int[] neighbours(int cell) {
		int x = GardenGrid.cellX(cell);
		int z = GardenGrid.cellZ(cell);
		List<Integer> list = new ArrayList<>(4);
		if (x > 0) list.add(cell - GardenGrid.SIZE);
		if (x < GardenGrid.SIZE - 1) list.add(cell + GardenGrid.SIZE);
		if (z > 0) list.add(cell - 1);
		if (z < GardenGrid.SIZE - 1) list.add(cell + 1);
		return list.stream().mapToInt(Integer::intValue).toArray();
	}

	private static boolean isPart(ArmorStand stand) {
		ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
		if (head.isEmpty()) return false;
		String tex = Fingerprints.textureHash(head);
		if (Jellybean.CANE_TEXTURE.equals(tex) || Jellybean.MELON_TEXTURE.equals(tex)) return true;
		return head.getHoverName().getString().toLowerCase(java.util.Locale.ROOT).startsWith("magicjellybean");
	}

	private static boolean isMelon(ArmorStand stand) {
		ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
		String tex = Fingerprints.textureHash(head);
		if (tex != null) return tex.equals(Jellybean.MELON_TEXTURE);
		return head.getHoverName().getString().equalsIgnoreCase("magicjellybean0");
	}
}
