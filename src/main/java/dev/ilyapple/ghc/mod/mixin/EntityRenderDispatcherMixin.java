package dev.ilyapple.ghc.mod.mixin;

import com.llamalad7.mixinextras.sugar.Local;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

import dev.ilyapple.ghc.mod.JellybeanView;

/**
 * Hides the Jellybean heads above the bottom block (SkyHanni hides entities at the same place). Only the entity is taken
 * ({@code @Local}), so the hook fits every version: 26.3 added a partial-tick argument to {@code shouldRender}.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void ghc$hideJellybeanParts(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true) Entity entity) {
		if (JellybeanView.hidesEntity(entity)) cir.setReturnValue(false);
	}
}
