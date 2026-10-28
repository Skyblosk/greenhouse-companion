package dev.ilyapple.ghc.mod.render;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.ilyapple.ghc.core.ModelBook;
import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.core.overlay.PlotOverlay;
import dev.ilyapple.ghc.core.schematic.Schematic;
import dev.ilyapple.ghc.mod.Fingerprints;
import dev.ilyapple.ghc.mod.GardenGrid;
import dev.ilyapple.ghc.mod.GreenhouseCompanion;
import dev.ilyapple.ghc.mod.JellybeanView;
import dev.ilyapple.ghc.mod.ModState;
import dev.ilyapple.ghc.mod.Settings;
import dev.ilyapple.ghc.mod.ui.Theme;

/**
 * The current flow step's layout shown in the greenhouse (turned the way it is planted), from {@link PlotOverlay}'s
 * verdicts. Every problem gets a box in its action's colour (see {@link PlotOverlay.Action}): around the crop (from the
 * soil up to the plant's top) for a plant to place (blue), remove (red) or clear (orange), around the soil block for the
 * wrong ground (brown), and around a Devourer root (purple). A missing plant also gets a translucent ghost of
 * itself and wrong soil a ghost of the soil it needs (GreenEssentials' {@code GhostGeometry} and
 * {@code LayoutOverlay}, Skyblocker's paste preview). Labels with the action's icon ({@code + Wild Rose},
 * {@code ✘ Cactus → Wild Rose}, {@code ⊘ Cactus → Shadevine spot}, {@code ▼ Soil: Sand or Farmland}, {@code ◇ Shadevine},
 * {@code ✂ Devourer Root}) appear only when you come close, like the stage labels. Empty spawn spots are fine and never boxed.
 *
 * <p>A mark is shown once two scans in a row agree on it (GreenEssentials' rule), so plants still loading or a crop
 * mid-harvest don't flash red. Identifying the greenhouse again, or turning it, clears everything at once.
 */
public final class LayoutOverlay {
	private static final int GHOST = 0x80FFFFFF;
	private static final int GHOST_BLOCK = 0x70FFFFFF;
	private static final float LINE_WIDTH = 2.0f;
	/** The top of the soil: nothing is drawn below it except the soil boxes themselves. */
	private static final double GROUND = GardenGrid.FLOOR_Y + 1;

	private enum Ghost { NONE, HEAD, BLOCK, SOIL }

	/** One thing to draw. {@code pos} is the ghost's min corner; {@code box} the red box (world coordinates). */
	private record Part(@Nullable AABB box, int color, Ghost ghost, @Nullable BlockPos pos, int size, @Nullable String texture, @Nullable BlockState block) {}

	private static volatile List<Part> parts = List.of();
	private static volatile List<JellybeanView.Label> labels = List.of();
	private static volatile List<PlotOverlay.Mark> shown = List.of();
	private static Map<String, PlotOverlay.Mark> previous = Map.of();
	private static final Map<String, ResolvableProfile> PROFILES = new HashMap<>();
	private static @Nullable Object entityModels;
	private static @Nullable SkullModelBase headModel;

	private LayoutOverlay() {}

	/** Drops everything drawn, e.g. after the greenhouse was identified again or turned. */
	public static void clear() {
		parts = List.of();
		labels = List.of();
		shown = List.of();
		previous = Map.of();
	}

	public static List<JellybeanView.Label> labels() {
		return labels;
	}

	/** The marks on show (after the two-scan check), for the HUD and the debug stream. */
	public static List<PlotOverlay.Mark> marks() {
		return shown;
	}

	/** Plans the marks after a scan of {@code corner}'s greenhouse. */
	public static void update(@Nullable ClientLevel level, BlockPos corner, ModState.@Nullable Site site, List<Entity>[] entitiesByCell) {
		Settings settings = GreenhouseCompanion.settings();
		Schematic layout = site == null ? null : site.greenhouse().layout();
		if (level == null || site == null || layout == null || !settings.layoutHologram) {
			clear();
			return;
		}
		Plants plants = GreenhouseCompanion.state().plants();
		Census census = site.greenhouse().context().census();
		Set<Integer> roots = new HashSet<>();
		for (int cell = 0; cell < entitiesByCell.length; cell++) {
			for (Entity entity : entitiesByCell[cell]) if (entity instanceof ArmorStand stand && isRoot(stand)) roots.add(cell);
		}
		List<PlotOverlay.Mark> planned = PlotOverlay.plan(layout, census, plants, cell -> soil(level, corner, cell), roots,
				new PlotOverlay.Options(settings.devourerRingOnlyWhenNeeded));

		Map<String, PlotOverlay.Mark> now = new LinkedHashMap<>();
		for (PlotOverlay.Mark mark : planned) now.put(mark.key(), mark);
		List<PlotOverlay.Mark> steady = new ArrayList<>();
		for (PlotOverlay.Mark mark : planned) if (previous.containsKey(mark.key())) steady.add(mark);
		previous = now;

		List<Part> drawn = new ArrayList<>();
		List<JellybeanView.Label> text = new ArrayList<>();
		Map<Integer, Integer> labelsAt = new HashMap<>();
		for (PlotOverlay.Mark mark : steady) {
			if (mark.action() == PlotOverlay.Action.SOIL && !settings.hologramGround) continue;
			int x = GardenGrid.cellX(mark.anchor());
			int z = GardenGrid.cellZ(mark.anchor());
			double wx = corner.getX() + x;
			double wz = corner.getZ() + z;
			double top;
			switch (mark.action()) {
				case SOIL -> {
					AABB box = new AABB(wx, GardenGrid.FLOOR_Y, wz, wx + 1, GROUND, wz + 1).inflate(0.004);
					BlockState wanted = soilBlock(mark.want() == null ? "" : mark.want().split(",")[0]);
					drawn.add(new Part(box, mark.action().color(), wanted == null ? Ghost.NONE : Ghost.SOIL, corner.offset(x, 0, z), 1, null, wanted));
					top = GROUND + 0.2;
				}
				case PLACE -> {
					Plants.Plant plant = mark.want() == null ? null : plants.byId(mark.want());
					String texture = plant == null ? null : texture(plant);
					BlockState block = plant == null || texture != null ? null : block(plant);
					AABB box = new AABB(wx, GROUND, wz, wx + mark.size(), GROUND + 1, wz + mark.size()).deflate(0.01);
					Ghost ghost = texture != null ? Ghost.HEAD : block != null ? Ghost.BLOCK : Ghost.NONE;
					drawn.add(new Part(box, mark.action().color(), ghost, corner.offset(x, 1, z), mark.size(), texture, block));
					top = GROUND + 1;
				}
				case REMOVE, CLEAR, ROOT -> {
					top = columnTop(level, corner, mark, entitiesByCell);
					drawn.add(new Part(new AABB(wx, GROUND, wz, wx + mark.size(), top, wz + mark.size()).inflate(0.01), mark.action().color(), Ghost.NONE, null,
							mark.size(), null, null));
				}
				default -> top = GROUND + 0.6;
			}
			if (!settings.hologramLabels) continue;
			int stacked = labelsAt.merge(mark.anchor(), 1, Integer::sum) - 1;
			Vec3 at = new Vec3(wx + mark.size() / 2.0, top + 0.35 + stacked * 0.3, wz + mark.size() / 2.0);
			text.add(new JellybeanView.Label(at, mark.label(), mark.action().color()));
		}
		parts = List.copyOf(drawn);
		labels = List.copyOf(text);
		shown = List.copyOf(steady);
	}


	/** How high the plants on a mark's footprint reach (at least one block), from their armor stands and blocks. */
	private static double columnTop(ClientLevel level, BlockPos corner, PlotOverlay.Mark mark, List<Entity>[] entitiesByCell) {
		double top = GROUND + 1;
		for (int cell : PlotOverlay.footprint(mark.anchor(), mark.size())) {
			for (Entity entity : entitiesByCell[cell]) {
				if (entity instanceof ArmorStand stand && !stand.isMarker()) top = Math.max(top, stand.getBoundingBox().maxY);
			}
			BlockPos base = corner.offset(GardenGrid.cellX(cell), 1, GardenGrid.cellZ(cell));
			for (int dy = 0; dy < 12; dy++) {
				BlockPos pos = base.above(dy);
				BlockState state = level.getBlockState(pos);
				if (state.isAir()) break;
				VoxelShape shape = state.getShape(level, pos);
				if (!shape.isEmpty()) top = Math.max(top, pos.getY() + shape.bounds().maxY);
			}
		}
		return Math.min(top, GROUND + 8);
	}

	private static @Nullable String soil(ClientLevel level, BlockPos corner, int cell) {
		BlockPos pos = corner.offset(GardenGrid.cellX(cell), 0, GardenGrid.cellZ(cell));
		if (!level.isLoaded(pos)) return null;
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
	}

	/** A Devourer root: an armor stand wearing the "Devourer Roots" head (GreenEssentials' {@code DevourerRoots}). */
	private static boolean isRoot(ArmorStand stand) {
		String name = Fingerprints.textOf(stand.getItemBySlot(EquipmentSlot.HEAD).getHoverName());
		StringBuilder letters = new StringBuilder();
		for (char c : name.toCharArray()) if (Character.isLetterOrDigit(c)) letters.append(Character.toLowerCase(c));
		return letters.toString().startsWith("devourerroot");
	}

	// Drawing

	public static void render(LevelRenderContext context) {
		List<Part> current = parts;
		if (current.isEmpty()) return;
		CameraRenderState camera = context.levelState().cameraRenderState;
		if (camera == null || camera.pos == null) return;
		PoseStack stack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		for (Part part : current) {
			if (part.ghost() == Ghost.NONE || part.pos() == null) continue;
			stack.pushPose();
			stack.translate(part.pos().getX() - camera.pos.x, part.pos().getY() - camera.pos.y, part.pos().getZ() - camera.pos.z);
			switch (part.ghost()) {
				case HEAD -> {
					stack.translate(part.size() / 2.0, 0, part.size() / 2.0);
					stack.scale(1.2f, -1.2f, -1.2f);
					head(part.texture(), stack, collector);
				}
				case BLOCK -> {
					stack.translate(0.002, 0.002, 0.002);
					block(part.block(), stack, collector, GHOST_BLOCK);
				}
				case SOIL -> {
					// A hair larger than the block, so the wrong soil shows its replacement over it
					stack.translate(-0.002, -0.002, -0.002);
					stack.scale(1.004f, 1.004f, 1.004f);
					block(part.block(), stack, collector, GHOST_BLOCK);
				}
				case NONE -> { }
			}
			stack.popPose();
		}
	}

	/** The boxes, drawn after translucent terrain with Athen's depthless lines so they show through crops. */
	public static void renderBoxes(LevelRenderContext context) {
		List<Part> current = parts;
		if (current.isEmpty() || !(context.bufferSource() instanceof MultiBufferSource.BufferSource buffers)) return;
		CameraRenderState camera = context.levelState().cameraRenderState;
		if (camera == null || camera.pos == null) return;
		boolean through = GreenhouseCompanion.settings().overlaySeeThrough;
		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		poseStack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
		PoseStack.Pose pose = poseStack.last();
		VertexConsumer fill = buffers.getBuffer(through ? Depthless.FILLED : RenderTypes.debugFilledBox());
		for (Part part : current) if (part.box() != null) Depthless.filled(fill, pose, part.box(), Theme.alpha(part.color(), 0x22));
		buffers.endBatch(through ? Depthless.FILLED : RenderTypes.debugFilledBox());
		VertexConsumer lines = buffers.getBuffer(through ? Depthless.LINES : RenderTypes.lines());
		for (Part part : current) if (part.box() != null) Depthless.outline(lines, pose, part.box(), LINE_WIDTH, part.color());
		buffers.endBatch(through ? Depthless.LINES : RenderTypes.lines());
		poseStack.popPose();
	}

	// Ghosts (after GreenEssentials' GhostGeometry and Skyblocker's renderPreview)

	private static void head(@Nullable String texture, PoseStack stack, SubmitNodeCollector collector) {
		if (texture == null) return;
		Minecraft client = Minecraft.getInstance();
		if (entityModels != client.getEntityModels()) {
			entityModels = client.getEntityModels();
			headModel = SkullBlockRenderer.createModel(client.getEntityModels(), SkullBlock.Types.PLAYER);
		}
		SkullModelBase model = headModel;
		if (model == null) return;
		ResolvableProfile profile = PROFILES.computeIfAbsent(texture, LayoutOverlay::profile);
		Identifier skin = client.playerSkinRenderCache().getOrDefault(profile).playerSkin().body().texturePath();
		collector.submitCustomGeometry(stack, RenderTypes.entityTranslucent(skin), (pose, vertices) -> {
			PoseStack local = new PoseStack();
			local.last().set(pose);
			model.setupAnim(new SkullModelBase.State());
			model.renderToBuffer(local, vertices, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, GHOST);
		});
	}

	private static void block(@Nullable BlockState state, PoseStack stack, SubmitNodeCollector collector, int color) {
		if (state == null) return;
		var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
		List<BlockStateModelPart> modelParts = new ArrayList<>();
		model.collectParts(RandomSource.create(0L), modelParts);
		Map<Identifier, List<net.minecraft.client.resources.model.geometry.BakedQuad>> byAtlas = new HashMap<>();
		for (BlockStateModelPart part : modelParts) {
			for (Direction direction : Direction.values()) {
				for (var quad : part.getQuads(direction)) byAtlas.computeIfAbsent(quad.materialInfo().sprite().atlasLocation(), key -> new ArrayList<>()).add(quad);
			}
			for (var quad : part.getQuads(null)) byAtlas.computeIfAbsent(quad.materialInfo().sprite().atlasLocation(), key -> new ArrayList<>()).add(quad);
		}
		byAtlas.forEach((atlas, quads) -> collector.submitCustomGeometry(stack, RenderTypes.entityTranslucent(atlas), (pose, vertices) -> {
			QuadInstance instance = new QuadInstance();
			instance.setColor(color);
			instance.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
			instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
			for (var quad : quads) vertices.putBakedQuad(pose, quad, instance);
		}));
	}

	// What each plant looks like fully grown

	private static final Map<String, @Nullable String> TEXTURES = new HashMap<>();
	private static final Map<String, @Nullable BlockState> BLOCKS = new HashMap<>();

	/** The head texture of the plant's fully grown model in the model book: its first full-size head, else any. */
	private static @Nullable String texture(Plants.Plant plant) {
		return TEXTURES.computeIfAbsent(plant.id(), id -> {
			ModelBook.Model top = topModel(plant);
			if (top == null) return null;
			String any = null;
			for (ModelBook.Stand stand : top.stands()) {
				if (stand.tex() == null) continue;
				if (!stand.small()) return stand.tex();
				if (any == null) any = stand.tex();
			}
			return any;
		});
	}

	/** The block just above the soil in the plant's fully grown model, for plants that are blocks (wheat, cane...). */
	private static @Nullable BlockState block(Plants.Plant plant) {
		return BLOCKS.computeIfAbsent(plant.id(), id -> {
			ModelBook.Model top = topModel(plant);
			if (top == null) return null;
			for (ModelBook.Block block : top.blocks()) {
				if (block.y() != 1) continue;
				Block type = BuiltInRegistries.BLOCK.getValue(Identifier.parse(block.id()));
				if (type == Blocks.AIR) continue;
				BlockState state = type.defaultBlockState();
				for (Map.Entry<String, String> prop : block.props().entrySet()) state = withProperty(state, prop.getKey(), prop.getValue());
				return state;
			}
			return null;
		});
	}

	private static ModelBook.@Nullable Model topModel(Plants.Plant plant) {
		ModelBook book = GreenhouseCompanion.state().table().models();
		if (book == null) return null;
		ModelBook.Model best = null;
		for (ModelBook.Model model : book.modelsOf(plant.id())) {
			if (best == null || model.stages().hi() > best.stages().hi()) best = model;
		}
		return best;
	}

	private static BlockState withProperty(BlockState state, String name, String value) {
		for (net.minecraft.world.level.block.state.properties.Property<?> property : state.getProperties()) {
			if (property.getName().equals(name)) return set(state, property, value);
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState set(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> property, String value) {
		return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
	}

	private static @Nullable BlockState soilBlock(String soil) {
		return switch (soil) {
			case "farmland" -> Blocks.FARMLAND.defaultBlockState();
			case "sand" -> Blocks.SAND.defaultBlockState();
			case "soul_sand" -> Blocks.SOUL_SAND.defaultBlockState();
			case "end_stone" -> Blocks.END_STONE.defaultBlockState();
			case "mycelium" -> Blocks.MYCELIUM.defaultBlockState();
			case "netherrack" -> Blocks.NETHERRACK.defaultBlockState();
			case "dirt" -> Blocks.DIRT.defaultBlockState();
			default -> null;
		};
	}

	/** A player-head profile skinned with a textures.minecraft.net texture hash (GreenEssentials' MutationAssets). */
	private static ResolvableProfile profile(String texture) {
		String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + texture + "\"}}}";
		String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		UUID id = UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8));
		PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", value)));
		return ResolvableProfile.createResolved(new GameProfile(id, "Greenhouse", properties));
	}
}
