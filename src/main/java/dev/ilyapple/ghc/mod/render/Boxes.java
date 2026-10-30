package dev.ilyapple.ghc.mod.render;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.ilyapple.ghc.mod.GreenhouseCompanion;
import dev.ilyapple.ghc.mod.compat.BoxPipelines;
import dev.ilyapple.ghc.mod.compat.Mc;

/**
 * World boxes, framed and filled, with or without the depth check: Parallax's {@code ParallaxRenderQueue} and
 * {@code ParallaxBoxRenderer} (Athen's renderer, github.com/skies-starred/Parallax, BSD-3-Clause, © Starred), in Java.
 * Every box is submitted as custom geometry while the frame's submits are collected, frames first, then fills, each in
 * the depth-tested or the see-through render type ({@link BoxPipelines}).
 */
public final class Boxes {
	/** A world box: filled in {@code fill}, framed {@code width} wide in {@code line} (ARGB). */
	public record Box(AABB box, int fill, int line, float width) {}

	private record Source(Supplier<List<Box>> boxes, BooleanSupplier through) {}

	private static final List<Source> SOURCES = new ArrayList<>();

	private Boxes() {}

	/** Draws {@code boxes} (world coordinates) every frame; through blocks while {@code through} says so. */
	public static void register(Supplier<List<Box>> boxes, BooleanSupplier through) {
		if (SOURCES.isEmpty()) LevelRenderEvents.COLLECT_SUBMITS.register(Boxes::submit);
		SOURCES.add(new Source(boxes, through));
	}

	private static void submit(LevelRenderContext context) {
		var settings = GreenhouseCompanion.settings();
		if (!settings.boxFill && !settings.boxOutline) return;
		List<Box> depth = new ArrayList<>();
		List<Box> depthless = new ArrayList<>();
		for (Source source : SOURCES) {
			List<Box> boxes = source.boxes().get();
			if (!boxes.isEmpty()) (source.through().getAsBoolean() ? depthless : depth).addAll(boxes);
		}
		if (depth.isEmpty() && depthless.isEmpty()) return;
		Vec3 camera = Mc.cameraPosition();
		PoseStack poseStack = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		poseStack.pushPose();
		poseStack.translate(-camera.x, -camera.y, -camera.z);
		if (settings.boxOutline && !depth.isEmpty()) collector.submitCustomGeometry(poseStack, BoxPipelines.LINES_DEPTH, (pose, buffer) -> frames(pose, buffer, depth));
		if (settings.boxOutline && !depthless.isEmpty()) collector.submitCustomGeometry(poseStack, BoxPipelines.LINES_DEPTHLESS, (pose, buffer) -> frames(pose, buffer, depthless));
		if (settings.boxFill && !depth.isEmpty()) collector.submitCustomGeometry(poseStack, BoxPipelines.FILLED_DEPTH, (pose, buffer) -> fills(pose, buffer, depth));
		if (settings.boxFill && !depthless.isEmpty()) collector.submitCustomGeometry(poseStack, BoxPipelines.FILLED_DEPTHLESS, (pose, buffer) -> fills(pose, buffer, depthless));
		poseStack.popPose();
	}

	private static void frames(PoseStack.Pose pose, VertexConsumer buffer, List<Box> boxes) {
		for (Box box : boxes) {
			AABB b = box.box();
			frame(pose, buffer, (float) b.minX, (float) b.minY, (float) b.minZ, (float) b.maxX, (float) b.maxY, (float) b.maxZ, box.line(), box.width());
		}
	}

	private static void fills(PoseStack.Pose pose, VertexConsumer buffer, List<Box> boxes) {
		for (Box box : boxes) {
			AABB b = box.box();
			fill(pose, buffer, (float) b.minX, (float) b.minY, (float) b.minZ, (float) b.maxX, (float) b.maxY, (float) b.maxZ, box.fill());
		}
	}

	/** Parallax's {@code IParallaxRenderer.box} with a line width: the 12 edges, each with its direction as the normal. */
	private static void frame(PoseStack.Pose pose, VertexConsumer buffer, float x1, float y1, float z1, float x2, float y2, float z2, int color, float width) {
		line(pose, buffer, x1, y1, z1, x2, y1, z1, 1, 0, 0, color, width);
		line(pose, buffer, x2, y1, z1, x2, y1, z2, 0, 0, 1, color, width);
		line(pose, buffer, x2, y1, z2, x1, y1, z2, -1, 0, 0, color, width);
		line(pose, buffer, x1, y1, z2, x1, y1, z1, 0, 0, -1, color, width);

		line(pose, buffer, x1, y2, z1, x2, y2, z1, 1, 0, 0, color, width);
		line(pose, buffer, x2, y2, z1, x2, y2, z2, 0, 0, 1, color, width);
		line(pose, buffer, x2, y2, z2, x1, y2, z2, -1, 0, 0, color, width);
		line(pose, buffer, x1, y2, z2, x1, y2, z1, 0, 0, -1, color, width);

		line(pose, buffer, x1, y1, z1, x1, y2, z1, 0, 1, 0, color, width);
		line(pose, buffer, x2, y1, z1, x2, y2, z1, 0, 1, 0, color, width);
		line(pose, buffer, x2, y1, z2, x2, y2, z2, 0, 1, 0, color, width);
		line(pose, buffer, x1, y1, z2, x1, y2, z2, 0, 1, 0, color, width);
	}

	private static void line(PoseStack.Pose pose, VertexConsumer buffer, float xa, float ya, float za, float xb, float yb, float zb, float nx, float ny, float nz,
			int color, float width) {
		buffer.addVertex(pose, xa, ya, za).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
		buffer.addVertex(pose, xb, yb, zb).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
	}

	/** Parallax's {@code IParallaxRenderer.box} filled: six quads, colour only. */
	private static void fill(PoseStack.Pose pose, VertexConsumer buffer, float x1, float y1, float z1, float x2, float y2, float z2, int color) {
		quad(pose, buffer, color, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1);
		quad(pose, buffer, color, x2, y1, z2, x2, y1, z1, x2, y2, z1, x2, y2, z2);
		quad(pose, buffer, color, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1);
		quad(pose, buffer, color, x2, y1, z2, x1, y1, z2, x1, y2, z2, x2, y2, z2);
		quad(pose, buffer, color, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
		quad(pose, buffer, color, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1);
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer buffer, int color, float... xyz) {
		for (int i = 0; i < 12; i += 3) buffer.addVertex(pose, xyz[i], xyz[i + 1], xyz[i + 2]).setColor(color);
	}
}
