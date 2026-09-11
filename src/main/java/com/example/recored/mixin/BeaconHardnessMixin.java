package com.example.recored.mixin;

import com.example.recored.game.GameManager;

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
 * beacon hardness is 3.0F; we bump only the specific beacon blocks that are
 * registered as cores, to {@link GameManager#coreHardness} - every other block
 * keeps its default.
 *
 * <p>Note beacons aren't in the pickaxe-mineable tag, so tool choice never
 * changes their mining speed (a diamond pickaxe mines one exactly as fast as a
 * bare hand). With the vanilla formula ({@code getDestroyProgress}) that makes
 * total mining time a flat {@code hardness * 30} ticks - see
 * {@code GameManager#coreHardness}'s doc for the current target.
 *
 * <p>This is shared (common) code: it runs identically on client and server, so
 * once both have the jar the mining time matches. The client learns which
 * positions are cores from {@link com.example.recored.net.CoreSyncPayload}.
 * (The actual mine-to-completion timing is driven server-side by
 * {@code GameManager#tickCoreMining} via {@code CoreMiningMixin}, not by this
 * hardness value directly being timed out client-side - see
 * {@code ClientCoreProgressMixin} for why the client's own local crack
 * prediction is suppressed for cores.)
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
			cir.setReturnValue(GameManager.INSTANCE.coreHardness);
		}
	}
}
