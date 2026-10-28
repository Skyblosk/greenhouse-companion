package dev.ilyapple.ghc.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayInvoker {
	@Invoker("getBlockState")
	BlockState ghc$getBlockState();
}
