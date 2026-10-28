package dev.ilyapple.ghc.mod.render;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.ilyapple.ghc.mod.compat.Mc;

/**
 * Every in-world render path that differs between Minecraft versions, drawn in a real client and world: boxes with
 * the depth check and without ({@link Boxes}), a name tag ({@link Mc#nameTag}), and the overlay's ghost head and
 * ghost block. A crash fails the test; the screenshot shows how it looks. {@code ./gradlew :26.3:runClientGameTest}
 */
public class RenderGameTest implements FabricClientGameTest {
	private static volatile Vec3 at = Vec3.ZERO;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			// Let the chunks render (the chunk-wait call is named differently on 26.1 and 26.2+)
			context.waitTicks(60);
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			// Stand still facing east (+x), looking a little down; "at" is on the ground 5 blocks ahead
			world.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ -90 15");
			context.waitTicks(20);
			at = context.computeOnClient(client -> client.player.position().add(5, 0, 0));
			// Left: depth-tested (its lower half is underground, hidden); right: see-through (all of it shows)
			Boxes.register(() -> List.of(new Boxes.Box(new AABB(at.x - 0.5, at.y - 1, at.z - 2.5, at.x + 0.5, at.y + 1, at.z - 1.5), 0x4434D399, 0xFF34D399, 2.5f)), () -> false);
			Boxes.register(() -> List.of(new Boxes.Box(new AABB(at.x - 0.5, at.y - 1, at.z + 1.5, at.x + 0.5, at.y + 1, at.z + 2.5), 0x44EF4444, 0xFFEF4444, 2.5f)), () -> true);
			LevelRenderEvents.COLLECT_SUBMITS.register(render -> {
				CameraRenderState camera = render.levelState().cameraRenderState;
				if (camera == null || camera.pos == null) return;
				PoseStack pose = render.poseStack();
				pose.pushPose();
				pose.translate(at.x - camera.pos.x, at.y + 2.6 - camera.pos.y, at.z - camera.pos.z);
				Mc.nameTag(render.submitNodeCollector(), pose, Component.literal("Greenhouse Companion " + SharedConstants.getCurrentVersion().name()), 36, camera);
				pose.popPose();
				pose.pushPose();
				pose.translate(at.x - 0.5 - camera.pos.x, at.y - camera.pos.y, at.z - 0.5 - camera.pos.z);
				LayoutOverlay.block(Blocks.SUGAR_CANE.defaultBlockState(), pose, render.submitNodeCollector(), 0x80FFFFFF);
				pose.popPose();
				pose.pushPose();
				pose.translate(at.x - camera.pos.x, at.y + 1.6 - camera.pos.y, at.z - camera.pos.z);
				LayoutOverlay.head("dde18b1db0f938380dd8bed0c9189c3e62ea3acf900a19b2e95f52708c3ae3f2", pose, render.submitNodeCollector());
				pose.popPose();
			});
			context.waitTicks(40);
			context.takeScreenshot("ghc-render-" + SharedConstants.getCurrentVersion().name());
		}
	}
}
