package com.example.recored.mixin;

import com.example.recored.game.GameManager;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The actual Q-drop-key / Ctrl+Q entry point:
 * {@code ServerGamePacketListenerImpl#handlePlayerAction} routes
 * {@code DROP_ITEM}/{@code DROP_ALL_ITEMS} straight to {@code
 * ServerPlayer#drop(boolean)} - which removes the item from the *selected
 * slot itself* (via {@code Inventory#removeFromSelected}) BEFORE ever
 * calling any {@code ItemStack}-taking {@code drop} overload. Cancelling
 * those (see {@code PreventReadyItemDropMixin}, which still covers other
 * drop paths - the inventory-screen cursor-stack-on-close drop, throwing an
 * item by clicking outside the window - both of which DO go through the
 * 2-arg {@code Player#drop(ItemStack, boolean)}) is too late for this one:
 * the item's already gone from the inventory by the time it would run. This
 * cancels at the true source instead, before the inventory is touched at
 * all.
 */
@Mixin(ServerPlayer.class)
public class PreventReadyItemDropKeyMixin {

	@Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
	private void cores$preventReadyItemDropKey(boolean all, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (GameManager.INSTANCE.isReadyMarkerStack(self.getInventory().getSelectedItem())) {
			ci.cancel();
		}
	}
}
