package dev.ilyapple.ghc.mod.compat;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
//? if < 26.3
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

/**
 * The calls that differ between Minecraft versions. Stonecutter comments ({@code //? if >= 26.2}) pick each version's
 * code; the committed source is the 26.1 one.
 */
public final class Mc {
	private Mc() {}

	public static @Nullable Screen screen() {
		//? if >= 26.2 {
		/*return Minecraft.getInstance().gui.screen();
		*///?} else
		return Minecraft.getInstance().screen;
	}

	public static void setScreen(@Nullable Screen screen) {
		//? if >= 26.2 {
		/*Minecraft.getInstance().gui.setScreen(screen);
		*///?} else
		Minecraft.getInstance().setScreen(screen);
	}

	/** The line above the hotbar. */
	public static void actionBar(Component text) {
		//? if >= 26.2 {
		/*Minecraft.getInstance().gui.hud.setOverlayMessage(text, false);
		*///?} else
		Minecraft.getInstance().gui.setOverlayMessage(text, false);
	}

	public static void title(Component title, Component subtitle) {
		//? if >= 26.2 {
		/*Minecraft.getInstance().gui.hud.setTitle(title);
		Minecraft.getInstance().gui.hud.setSubtitle(subtitle);
		*///?} else {
		Minecraft.getInstance().gui.setTitle(title);
		Minecraft.getInstance().gui.setSubtitle(subtitle);
		//?}
	}

	/** F1: the HUD is hidden. */
	public static boolean hudHidden() {
		//? if >= 26.2 {
		/*return Minecraft.getInstance().gui.hud.isHidden();
		*///?} else
		return Minecraft.getInstance().options.hideGui;
	}

	/** The key type for keyboard key mappings. */
	public static InputConstants.Type keyboard() {
		//? if >= 26.3 {
		/*return InputConstants.Type.KEYBOARD;
		*///?} else
		return InputConstants.Type.KEYSYM;
	}

	/** Opens {@code folder} in the file manager; not while the GUI game test clicks through the window ({@code -Dghc.test}). */
	public static void openFolder(Path folder) {
		if (Boolean.getBoolean("ghc.test")) return;
		//? if >= 26.3 {
		/*com.mojang.blaze3d.Blaze3D.openPath(folder);
		*///?} else
		Util.getPlatform().openPath(folder);
	}

	/** A name tag at the pose's origin, seen through blocks, at full brightness ({@code distanceSq} unused since 26.2). */
	public static void nameTag(SubmitNodeCollector collector, PoseStack pose, Component text, double distanceSq, CameraRenderState camera) {
		//? if >= 26.2 {
		/*collector.submitNameTag(pose, Vec3.ZERO, 0, text, true, LightCoordsUtil.FULL_BRIGHT, camera);
		*///?} else
		collector.submitNameTag(pose, Vec3.ZERO, 0, text, true, LightCoordsUtil.FULL_BRIGHT, distanceSq, camera);
	}

	/** Where the camera is, for drawing in world coordinates (Parallax reads the main camera the same way). */
	public static Vec3 cameraPosition() {
		//? if >= 26.2 {
		/*return Minecraft.getInstance().gameRenderer.mainCamera().position();
		*///?} else
		return Minecraft.getInstance().gameRenderer.getMainCamera().position();
	}

	/** The loading overlay is up (the dev demo waits for it). */
	public static boolean loading() {
		//? if >= 26.2 {
		/*return Minecraft.getInstance().gui.overlay() != null;
		*///?} else
		return Minecraft.getInstance().getOverlay() != null;
	}

	/** What a screenshot is taken of (the dev demo). */
	public static RenderTarget mainTarget() {
		//? if >= 26.2 {
		/*return Minecraft.getInstance().gameRenderer.mainRenderTarget();
		*///?} else
		return Minecraft.getInstance().getMainRenderTarget();
	}
}
