package com.example.cores.mixin;

import com.example.cores.game.GameManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block hardness has no Fabric API hook, so we go in through a Mixin. Vanilla
 * beacon hardness is 3.0F; obsidian is 50.0F. We bump only the specific beacon
 * blocks that are registered as cores - every other block keeps its default.
 *
 * <p>This is shared (common) code: it runs identically on client and server, so
 * once both have the jar the mining time matches. The client learns which
 * positions are cores from {@link com.example.cores.net.CoreSyncPayload}.
 *
 * <p>Yarn 26.2 maps the vanilla {@code AbstractBlock.AbstractBlockState} to
 * {@code BlockBehaviour.BlockStateBase} and {@code getHardness} to
 * {@code getDestroySpeed}.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public class BeaconHardnessMixin {

	@Inject(method = "getDestroySpeed", at = @At("HEAD"), cancellable = true)
	private void cores$hardenCoreBeacons(BlockGetter world, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		BlockBehaviour.BlockStateBase self = (BlockBehaviour.BlockStateBase) (Object) this;
		if (self.getBlock() == Blocks.BEACON && GameManager.INSTANCE.isHardenedCore(pos)) {
			cir.setReturnValue(50.0F); // obsidian hardness
		}
	}
}
