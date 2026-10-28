package dev.ilyapple.ghc.mod.render;

import java.util.Optional;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;

/**
 * Lines and filled boxes drawn without the depth test, so they show through cactus, canes and every other block:
 * the approach of Athen's {@code DualLevelPipeline}, {@code DualLevelRenderType} and {@code BoxRenderer}, in Java.
 * The vanilla line and debug-filled pipelines are copied with no depth-stencil state.
 */
public final class Depthless {
	public static final RenderPipeline LINES_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
			.withLocation("greenhouse-companion/depthless/lines").withDepthStencilState(Optional.empty()).build());
	public static final RenderPipeline FILLED_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation("greenhouse-companion/depthless/filled").withDepthStencilState(Optional.empty()).withCull(false).build());
	public static final RenderType LINES = RenderType.create("greenhouse_companion_lines_depthless",
			RenderSetup.builder(LINES_PIPELINE).bufferSize(RenderType.TRANSIENT_BUFFER_SIZE).createRenderSetup());
	public static final RenderType FILLED = RenderType.create("greenhouse_companion_filled_depthless",
			RenderSetup.builder(FILLED_PIPELINE).bufferSize(RenderType.TRANSIENT_BUFFER_SIZE).createRenderSetup());

	/** The 12 edges of a box, as corner flags (x0, y0, z0, x1, y1, z1); Athen's {@code BoxRenderer.edges}. */
	private static final int[][] EDGES = {
			{0, 0, 0, 1, 0, 0}, {1, 0, 0, 1, 0, 1}, {1, 0, 1, 0, 0, 1}, {0, 0, 1, 0, 0, 0},
			{0, 1, 0, 1, 1, 0}, {1, 1, 0, 1, 1, 1}, {1, 1, 1, 0, 1, 1}, {0, 1, 1, 0, 1, 0},
			{0, 0, 0, 0, 1, 0}, {1, 0, 0, 1, 1, 0}, {1, 0, 1, 1, 1, 1}, {0, 0, 1, 0, 1, 1}};

	private Depthless() {}

	/** Loads the class, registering the pipelines early. */
	public static void init() {
	}

	/** The box's outline; coordinates relative to the pose (camera-relative). */
	public static void outline(VertexConsumer buffer, PoseStack.Pose pose, AABB box, float width, int color) {
		float[] x = {(float) box.minX, (float) box.maxX};
		float[] y = {(float) box.minY, (float) box.maxY};
		float[] z = {(float) box.minZ, (float) box.maxZ};
		for (int[] edge : EDGES) line(buffer, pose, x[edge[0]], y[edge[1]], z[edge[2]], x[edge[3]], y[edge[4]], z[edge[5]], width, color);
	}

	/** Athen's {@code ILevelRenderer.vertex}: one line segment with its direction as the normal. */
	public static void line(VertexConsumer buffer, PoseStack.Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, float width, int color) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		float dz = z2 - z1;
		float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		float nx = length > 0 ? dx / length : 0;
		float ny = length > 0 ? dy / length : 0;
		float nz = length > 0 ? dz / length : 0;
		buffer.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
		buffer.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
	}

	/** A filled box, six quads; Athen's filled-box vertex order. */
	public static void filled(VertexConsumer buffer, PoseStack.Pose pose, AABB box, int color) {
		float x1 = (float) box.minX, x2 = (float) box.maxX, y1 = (float) box.minY, y2 = (float) box.maxY, z1 = (float) box.minZ, z2 = (float) box.maxZ;
		float[][] quads = {
				{x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1}, {x2, y1, z2, x2, y1, z1, x2, y2, z1, x2, y2, z2},
				{x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1}, {x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2},
				{x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2}, {x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1}};
		for (float[] quad : quads) for (int i = 0; i < 12; i += 3) buffer.addVertex(pose, quad[i], quad[i + 1], quad[i + 2]).setColor(color);
	}
}
