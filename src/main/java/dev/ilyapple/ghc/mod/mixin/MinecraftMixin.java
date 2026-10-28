package dev.ilyapple.ghc.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import dev.ilyapple.ghc.mod.GreenhouseCompanion;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/** What the crosshair was on and what was held when right-click was pressed: placements and plantings. */
	@Inject(method = "startUseItem", at = @At("HEAD"))
	private void ghc$onStartUseItem(CallbackInfo ci) {
		GreenhouseCompanion.onUse((Minecraft) (Object) this);
	}
}
