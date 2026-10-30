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
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import dev.ilyapple.ghc.mod.GreenhouseCompanion;
import dev.ilyapple.ghc.mod.Settings;
import dev.ilyapple.ghc.core.StageSet;
import dev.ilyapple.ghc.core.StageTracker;
import dev.ilyapple.ghc.core.flow.Census;
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
			checkHighlightGeometry(context, world);
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
			Settings settings = GreenhouseCompanion.settings();
			boolean originalFill = settings.boxFill;
			boolean originalOutline = settings.boxOutline;
			try {
				for (boolean fill : new boolean[] {true, false}) for (boolean outline : new boolean[] {true, false}) {
					context.runOnClient(client -> {
						settings.boxFill = fill;
						settings.boxOutline = outline;
					});
					context.waitTicks(10);
					context.takeScreenshot("box-style-" + (fill ? "fill" : "no-fill") + "-" + (outline ? "outline" : "no-outline"));
				}
			} finally {
				context.runOnClient(client -> {
					settings.boxFill = originalFill;
					settings.boxOutline = originalOutline;
				});
			}
		}
	}
	/** Verify the actual client block/armor-stand shapes and fallbacks, including a common mutation. */
	@SuppressWarnings("unchecked")
	private void checkHighlightGeometry(ClientGameTestContext context, TestSingleplayerContext world) {
		// The default test world can be far below y=73. Support the player and the depth-test boxes.
		world.getServer().runCommand("fill -4 73 -4 12 73 12 stone");
		world.getServer().runCommand("tp @a 0 76 3");
		world.getServer().runCommand("setblock 0 73 0 farmland");
		world.getServer().runCommand("setblock 0 74 0 wheat[age=7]");
		world.getServer().runCommand("summon armor_stand 0.5 74 0.5 {NoGravity:1b,Tags:[\"ghc_outline_test\"]}");
		context.waitTicks(40);
		context.runOnClient(client -> {
			BlockPos corner = new BlockPos(0, 73, 0);
			List<Entity>[] entities = new List[100];
			for (int i = 0; i < entities.length; i++) entities[i] = List.of();
			entities[0] = client.level.getEntities((Entity) null, new AABB(0, 74, 0, 1, 77, 1), e -> e instanceof ArmorStand);
			if (entities[0].isEmpty()) throw new AssertionError("Highlight fixture needs an armor stand");
			var kind = GreenhouseCompanion.state().plants().byId("shadevine");
			var plant = new Census.Plant(kind, List.of(0), StageSet.of(0), StageTracker.Origin.SPAWNED, true, true);
			Settings settings = new Settings();
			List<AABB> stands = Highlight.outline(client.level, corner, plant, entities, false, settings);
			settings.toggleHighlightOutline("shadevine");
			List<AABB> blocks = Highlight.outline(client.level, corner, plant, entities, false, settings);
			if (stands.isEmpty() || blocks.isEmpty() || stands.equals(blocks))
				throw new AssertionError("Common mutations must switch between distinct block and armor-stand shapes");
			entities[0] = List.of();
			settings.toggleHighlightOutline("shadevine");
			if (Highlight.outline(client.level, corner, plant, entities, false, settings).isEmpty())
				throw new AssertionError("A block-only crop must still be highlighted in armor-stand mode");
		});
		world.getServer().runCommand("setblock 0 74 0 air");
		context.waitTicks(10);
		context.runOnClient(client -> {
			List<Entity>[] entities = new List[100];
			for (int i = 0; i < entities.length; i++) entities[i] = List.of();
			entities[0] = client.level.getEntities((Entity) null, new AABB(0, 74, 0, 1, 77, 1), e -> e instanceof ArmorStand);
			var kind = GreenhouseCompanion.state().plants().byId("shadevine");
			var plant = new Census.Plant(kind, List.of(0), StageSet.of(0), StageTracker.Origin.SPAWNED, true, true);
			Settings settings = new Settings();
			settings.toggleHighlightOutline("shadevine");
			if (Highlight.outline(client.level, new BlockPos(0, 73, 0), plant, entities, false, settings).isEmpty())
				throw new AssertionError("An armor-only mutation must still be highlighted in block mode");
		});
		world.getServer().runCommand("kill @e[tag=ghc_outline_test]");
	}

}
