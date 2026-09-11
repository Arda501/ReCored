package com.example.recored.mixin;

import com.example.recored.game.GameManager;
import com.example.recored.game.Phase;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a team's own core behave like an adventure-mode-restricted block for
 * that team specifically. {@code Player#blockActionRestricted} is exactly what
 * both the server ({@code ServerPlayerGameMode}) and the client's own local
 * {@code MultiPlayerGameMode} independently check before starting to mine
 * anything (it's the very same check adventure mode uses) - so returning
 * {@code true} here stops the mining animation/crack overlay/local prediction
 * from starting client-side at all, not just the eventual break server-side
 * (which {@code CoreMiningMixin} already guarantees). Same effect as an
 * unbreakable block, from the player's point of view.
 *
 * <p>{@code Player} is shared (common) code, so this one mixin covers both the
 * server's {@code ServerPlayer} and the client's {@code LocalPlayer}.
 * Ownership is checked differently per side: server-side against the
 * authoritative {@code GameManager#cores}/{@code #teamOf} (parameterized by
 * the specific player, since a server has many players on different teams at
 * once); client-side against {@code GameManager#ownCores}, synced from the
 * server specifically for the local player (see {@code CoreSyncPayload}).
 *
 * <p>Doesn't affect creative players - vanilla itself skips this check for
 * them (see {@code ServerPlayerGameMode#handleBlockBreakAction}'s instabuild
 * branch), which is why {@code CoreMiningMixin} and the safety net in {@code
 * RecoredMod} independently guard the creative path too.
 */
@Mixin(Player.class)
public class CoreProtectionMixin {

	@Inject(method = "blockActionRestricted", at = @At("HEAD"), cancellable = true)
	private void cores$restrictOwnCore(Level level, BlockPos pos, GameType gameType, CallbackInfoReturnable<Boolean> cir) {
		GameManager gm = GameManager.INSTANCE;
		if (gm.phase != Phase.RUNNING || !gm.isHardenedCore(pos)) {
			return;
		}
		Player self = (Player) (Object) this;
		boolean ownCore = self instanceof ServerPlayer serverPlayer
			? gm.coreOwnerAt(pos) == gm.teamOf(serverPlayer.getUUID())
			: gm.isOwnCore(pos);
		if (ownCore) {
			cir.setReturnValue(true);
		}
	}
}
