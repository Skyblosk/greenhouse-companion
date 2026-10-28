package dev.ilyapple.ghc.mod;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

import dev.ilyapple.ghc.core.Growth;
import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.StageSet;
import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.flow.Census;
import dev.ilyapple.ghc.mod.render.LayoutOverlay;

/**
 * Text drawn in the world, as name tags in Fabric's submit phase (SkyHanni's {@code WorldRenderUtils.drawString}):
 * <ul>
 * <li>stage labels (Stage Above Crops): every growing plant, or only Jellybeans; shown within the label range;</li>
 * <li>the layout overlay's labels ({@link LayoutOverlay}): what to do at each red box, shown only up close;</li>
 * <li>water values (Water Above Crops, {@link WaterHelper}), within their own range.</li>
 * </ul>
 * One format everywhere. A stage reads {@code 12 / 15} (a range {@code 11–12 / 15}); fully grown {@code ✔ 15}. Placed
 * items get no label. Colours: white exact, amber a range, red a look not on record, green grown.
 */
public final class WorldLabels {
	public static final int EXACT = 0xFFF8FAFC;
	public static final int RANGE = 0xFFFBBF24;
	public static final int UNREAD = 0xFFF87171;
	public static final int GROWN = 0xFF34D399;

	private static volatile List<JellybeanView.Label> labels = List.of();

	private WorldLabels() {}

	static void render(LevelRenderContext context) {
		CameraRenderState camera = context.levelState().cameraRenderState;
		if (camera == null || camera.pos == null) return;
		Settings settings = GreenhouseCompanion.settings();
		draw(context, camera, labels, settings.labelRange);
		draw(context, camera, LayoutOverlay.labels(), settings.overlayLabelRange);
		draw(context, camera, WaterHelper.labels(), settings.waterLabelRange);
	}

	private static void draw(LevelRenderContext context, CameraRenderState camera, List<JellybeanView.Label> list, double range) {
		if (list.isEmpty()) return;
		PoseStack pose = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		double rangeSq = range * range;
		for (JellybeanView.Label label : list) {
			double distanceSq = camera.pos.distanceToSqr(label.position());
			if (distanceSq > rangeSq) continue;
			pose.pushPose();
			pose.translate(label.position().x - camera.pos.x, label.position().y - camera.pos.y, label.position().z - camera.pos.z);
			collector.submitNameTag(pose, Vec3.ZERO, 0, Component.literal(label.text()).withColor(label.color()), true,
					LightCoordsUtil.FULL_BRIGHT, distanceSq, camera);
			pose.popPose();
		}
	}

	/** Rebuilds the stage labels after a scan of the greenhouse at {@code corner}. */
	static void update(@Nullable BlockPos corner, ModState.@Nullable Site site, JellybeanView jellybeans, Settings settings) {
		if (corner == null) {
			labels = List.of();
			return;
		}
		List<JellybeanView.Label> list = new ArrayList<>(jellybeans.labels());
		if (site != null && settings.stageLabels && !settings.stageLabelsJellybeansOnly) {
			Census census = site.greenhouse().context().census();
			for (Census.Plant plant : census.plants()) {
				if (plant.id().equals(Growth.JELLYBEAN) || plant.dead() || !plant.kind().grows()) continue;
				// Placed items aren't growing, so no label (a "placed" label was redundant)
				if (plant.origin() == StageTracker.Origin.PLACED && plant.kind().mutation()) continue;
				// Base crops left to grow get no stage label (nothing to time a harvest by)
				if (!plant.kind().mutation() && !settings.harvestBaseCrops) continue;
				int anchor = plant.cells().stream().mapToInt(Integer::intValue).min().orElse(-1);
				if (anchor < 0) continue;
				double span = plant.kind().size();
				Vec3 at = new Vec3(corner.getX() + GardenGrid.cellX(anchor) + span / 2, GardenGrid.FLOOR_Y + 1.6, corner.getZ() + GardenGrid.cellZ(anchor) + span / 2);
				list.add(new JellybeanView.Label(at, stageText(plant.stages(), plant.kind()), color(plant)));
			}
		}
		labels = List.copyOf(list);
	}

	static int color(Census.Plant plant) {
		if (!plant.stages().isEmpty() && plant.stages().lo() >= plant.kind().readyStage()) return GROWN;
		return plant.exact() ? EXACT : plant.lookKnown() ? RANGE : UNREAD;
	}

	/** {@code 12 / 15}, {@code 11–12 / 15}, or {@code ✔ 15} once fully grown at every stage it could be at. */
	public static String stageText(StageSet stages, Plants.Plant plant) {
		if (stages.isEmpty()) return "? / " + plant.maxStage();
		if (stages.lo() >= plant.readyStage() && plant.grows()) return "✔ " + (stages.size() == 1 ? stages.lo() : stages.lo() + "–" + stages.hi());
		String stage = stages.size() == 1 ? Integer.toString(stages.lo()) : stages.lo() + "–" + stages.hi();
		return stage + " / " + plant.maxStage();
	}
}
