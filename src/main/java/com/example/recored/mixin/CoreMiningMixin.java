package com.example.recored.mixin;

import com.example.recored.game.GameManager;
import com.example.recored.game.Phase;
import com.example.recored.game.Team;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla mining progress lives on {@code ServerPlayerGameMode} - one player's
 * session, reset on every START_DESTROY_BLOCK. Cores need the opposite: progress
 * that belongs to the *block*, survives a player letting go or logging off, and
 * is contributed to by whoever is currently swinging at it. So for a core (while
 * the round is RUNNING, and the player isn't in creative) we cancel vanilla's
 * handling entirely and hand START/STOP/ABORT off to {@link GameManager}, which
 * accumulates progress itself in {@link GameManager#coreProgress} (see
 * {@code tickCoreMining}) and reflects it on a floating text hologram above the
 * core (see {@code GameManager#updateCoreDisplay}).
 *
 * <p>Also enforces that a team can never mine its own core: for that case we
 * cancel the action outright and never start tracking it at all (not even the
 * vanilla creative-insta-mine fallback reaches it - see the early return below).
 */
@Mixin(ServerPlayerGameMode.class)
public class CoreMiningMixin {

	@Shadow
	@Final
	protected ServerPlayer player;

	@Inject(method = "handleBlockBreakAction", at = @At("HEAD"), cancellable = true)
	private void cores$interceptCoreDigging(
		BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction direction, int maxBuildHeight, int sequence, CallbackInfo ci
	) {
		GameManager gm = GameManager.INSTANCE;
		if (gm.phase != Phase.RUNNING || !gm.isCore(pos)) {
			return; // not a core scenario - let vanilla handle it
		}

		Team owner = gm.coreOwnerAt(pos);
		Team minerTeam = gm.teamOf(this.player.getUUID());
		if (owner == minerTeam) {
			// Can't mine your own core, full stop - not even a scratch, and not
			// even creative gets through this one.
			ci.cancel();
			if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
				this.player.sendSystemMessage(
					Component.literal("You can't mine your own team's core!").withStyle(ChatFormatting.RED), true);
			}
			return;
		}

		if (this.player.getAbilities().instabuild || !this.player.isWithinBlockInteractionRange(pos, 1.0)) {
			// Not our concern: let vanilla handle it. Creative insta-mine still
			// ends up in PlayerBlockBreakEvents.BEFORE, which also blocks drops
			// for cores - see RecoredMod.
			return;
		}

		ci.cancel();
		switch (action) {
			case START_DESTROY_BLOCK -> gm.startDigging(this.player, pos.immutable());
			case STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> gm.stopDigging(this.player, pos.immutable());
			default -> {
			}
		}
	}
}
