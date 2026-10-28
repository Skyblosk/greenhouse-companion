package dev.ilyapple.ghc.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import dev.ilyapple.ghc.mod.GreenhouseCompanion;

/**
 * Break protection: the client never sends the dig, use or attack for a protected plant. Hooking the game mode
 * rather than the mouse catches held-button sweeps too, since every newly targeted block goes through
 * {@code startDestroyBlock} or {@code continueDestroyBlock}.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void ghc$protectStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (GreenhouseCompanion.protection().blockBreak(pos)) cir.setReturnValue(false);
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void ghc$protectContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (GreenhouseCompanion.protection().blockBreak(pos)) cir.setReturnValue(false);
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void ghc$protectUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		if (GreenhouseCompanion.protection().blockUse(hit.getBlockPos())) cir.setReturnValue(InteractionResult.FAIL);
	}

	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void ghc$protectAttack(Player player, Entity target, CallbackInfo ci) {
		if (GreenhouseCompanion.protection().attack(target)) ci.cancel();
	}

	@Inject(method = "interact", at = @At("HEAD"), cancellable = true)
	private void ghc$protectInteract(Player player, Entity target, EntityHitResult hit, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (GreenhouseCompanion.protection().interact(target)) cir.setReturnValue(InteractionResult.FAIL);
	}
}
