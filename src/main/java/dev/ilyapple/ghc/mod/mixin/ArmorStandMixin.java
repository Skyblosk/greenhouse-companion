package dev.ilyapple.ghc.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.decoration.ArmorStand;

import dev.ilyapple.ghc.mod.JellybeanView;

/** A hidden Jellybean head can't be aimed at either, so the crosshair goes through to what is behind it. */
@Mixin(ArmorStand.class)
public abstract class ArmorStandMixin {
	@Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
	private void ghc$hiddenNotPickable(CallbackInfoReturnable<Boolean> cir) {
		if (JellybeanView.hidesEntity((ArmorStand) (Object) this)) cir.setReturnValue(false);
	}
}
