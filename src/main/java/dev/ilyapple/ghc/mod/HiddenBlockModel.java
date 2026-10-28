package dev.ilyapple.ghc.mod;

import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sugar cane and melon stem models that draw nothing at the positions {@link JellybeanView} hides, and draw as
 * usual everywhere else. Wrapping the baked model (Fabric's model loading API) works with Sodium too, which never
 * runs vanilla's section compiler. The same approach as MagicAddons' ShrunkPlantBlockModel.
 */
public final class HiddenBlockModel extends WrapperBlockStateModel {
	private static final Set<Block> WRAPPED = Set.of(Blocks.SUGAR_CANE, Blocks.MELON_STEM);

	private HiddenBlockModel(BlockStateModel wrapped) {
		super(wrapped);
	}

	static void register() {
		ModelLoadingPlugin.register(context -> context.modifyBlockModelAfterBake().register(ModelModifier.WRAP_PHASE,
				(model, bake) -> WRAPPED.contains(bake.state().getBlock()) ? new HiddenBlockModel(model) : model));
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<@Nullable Direction> cullTest) {
		if (JellybeanView.hides(pos)) return;
		super.emitQuads(emitter, level, pos, state, random, cullTest);
	}

	@Override
	public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		// A hidden block must not share cached geometry with the shown ones
		if (JellybeanView.hides(pos)) return null;
		return super.createGeometryKey(level, pos, state, random);
	}
}
