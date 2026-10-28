package dev.ilyapple.ghc.mod;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import dev.ilyapple.ghc.core.Plants;
import dev.ilyapple.ghc.core.StageTracker;

/**
 * Stops clicks that would break a plant too early, like MagicAddons' Break Protection: a left click (harvest) on a
 * Magic Jellybean that isn't at stage 120 yet, and on any other crop that isn't fully grown (or only Jellybeans, if
 * chosen). With Harvest Base Crops off, fully grown base crops are protected too, since they're left to grow. Holding
 * the bypass key (Left Alt unless rebound) lets the click through. Optional: right clicks too.
 *
 * <p>Called from the {@code MultiPlayerGameMode} mixin before the client sends the dig, use or attack packet,
 * which covers single clicks and holding the button while sweeping over plants (SkyHanni hooks the same paths).
 */
public final class Protection {
	private static final long MESSAGE_EVERY_MS = 1200;

	private final Settings settings;
	private final KeyMapping bypass;
	private long lastMessageMs;

	Protection(Settings settings, KeyMapping bypass) {
		this.settings = settings;
		this.bypass = bypass;
	}

	/** A left click (start or continue breaking) on {@code pos}: {@code true} to stop it. */
	public boolean blockBreak(BlockPos pos) {
		boolean blocked = check(cellOf(pos), true);
		if (!blocked) GreenhouseCompanion.drops().clickedBlock(pos);
		return blocked;
	}

	/** A right click on {@code pos}. */
	public boolean blockUse(BlockPos pos) {
		return settings.protectRightClicks && !holdingDiagnostics() && check(cellOf(pos), false);
	}

	/** A left click on an entity (Jellybean heads are armor stands). */
	public boolean attack(Entity entity) {
		boolean blocked = check(cellOf(entity), true);
		if (!blocked) GreenhouseCompanion.drops().clickedEntity(entity);
		return blocked;
	}

	public boolean interact(Entity entity) {
		return settings.protectRightClicks && !holdingDiagnostics() && check(cellOf(entity), false);
	}

	private int cellOf(BlockPos pos) {
		BlockPos corner = GreenhouseCompanion.jellybeans().corner();
		return corner == null ? -1 : GardenGrid.cellOf(corner, pos);
	}

	private int cellOf(Entity entity) {
		BlockPos corner = GreenhouseCompanion.jellybeans().corner();
		if (corner == null) return -1;
		// A click on a young Jellybean's held bean is a click on that Jellybean, not on the cell the bean is over
		return entity.level() == null ? GardenGrid.cellAt(corner, entity.position()) : GardenGrid.plantCellOf(entity.level(), corner, entity);
	}

	private boolean check(int cell, boolean breaking) {
		if (cell < 0 || bypass.isDown()) return false;
		String why = reason(cell, breaking);
		if (why == null) return false;
		message(why);
		DebugStream.emit("blocked", event -> {
			event.addProperty("cell", cell);
			event.addProperty("breaking", breaking);
			event.addProperty("why", why);
		});
		return true;
	}

	private @Nullable String reason(int cell, boolean breaking) {
		JellybeanView.Bean bean = GreenhouseCompanion.jellybeans().bean(cell);
		if (bean != null) {
			if (!settings.protectGrowing || bean.fullyGrown()) return null;
			if (bean.placedLook() && !settings.protectPlacedLook) return null;
			return "Magic Jellybean " + JellybeanView.text(bean) + " isn't fully grown";
		}
		ModState.Site site = GreenhouseCompanion.currentSite();
		StageTracker.Tracked tracked = site == null ? null : site.greenhouse().tracker().get(cell);
		if (tracked == null || tracked.origin() == StageTracker.Origin.PLACED || tracked.stages().isEmpty()) return null;
		Plants.Plant plant = tracked.plant();
		boolean grown = tracked.stages().lo() >= plant.readyStage();
		// Base crops left to grow (Harvest Base Crops off) can't be broken once grown either
		if (!plant.mutation() && grown && !settings.harvestBaseCrops && settings.protectGrownBaseCrops) {
			return plant.name() + " is left to grow (Harvest Base Crops is off)";
		}
		if (!settings.protectGrowing || settings.protectJellybeansOnly || !plant.grows()) return null;
		// Most restrictive: only let it through once even the highest possible stage is fully grown
		if (grown) return null;
		return plant.name() + " (stage " + tracked.stages() + ") isn't fully grown";
	}

	private void message(String why) {
		if (!settings.protectionMessage) return;
		long now = System.currentTimeMillis();
		if (now - lastMessageMs < MESSAGE_EVERY_MS) return;
		lastMessageMs = now;
		Minecraft client = Minecraft.getInstance();
		client.gui.setOverlayMessage(Component.literal("✋ " + why + " · hold ").withColor(0xFFFBBF24)
				.append(bypass.getTranslatedKeyMessage().copy().withColor(0xFFF8FAFC))
				.append(Component.literal(" to break anyway").withColor(0xFFFBBF24)), false);
	}

	private static boolean holdingDiagnostics() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return false;
		String held = client.player.getMainHandItem().getHoverName().getString();
		return held.contains("Diagnostics");
	}
}
