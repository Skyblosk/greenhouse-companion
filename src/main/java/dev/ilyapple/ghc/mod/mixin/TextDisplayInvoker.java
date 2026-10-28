package dev.ilyapple.ghc.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;

@Mixin(Display.TextDisplay.class)
public interface TextDisplayInvoker {
	@Invoker("getText")
	Component ghc$getText();
}
