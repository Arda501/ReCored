package com.example.recored.mixin;

import com.example.recored.game.GameManager;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Covers the *other* vanilla drop paths for the ready/not-ready item (see
 * {@code GameManager#giveReadyItem}) - dropping the cursor stack when an
 * inventory screen closes, and throwing an item by clicking outside the
 * window - both of which go through this exact 2-arg {@code Player#drop}.
 * The Q-drop key / Ctrl+Q does NOT go through this method at all - see
 * {@code PreventReadyItemDropKeyMixin} for that one specifically (it removes
 * the item from the inventory itself before ever calling any {@code drop}
 * overload, so cancelling here would already be too late for it).
 *
 * <p>Moving the item to a different inventory slot isn't blocked here (that's
 * a pure inventory-screen action with no single vanilla choke point as clean
 * as this one) - {@code GameManager#enforceReadyItems} instead periodically
 * snaps it back to its designated slot and strips any stray copies, which
 * covers that case within a fraction of a second.
 */
@Mixin(Player.class)
public class PreventReadyItemDropMixin {

	@Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;",
		at = @At("HEAD"), cancellable = true)
	private void cores$preventReadyItemDrop(ItemStack itemStack, boolean thrownFromHand, CallbackInfoReturnable<ItemEntity> cir) {
		if (GameManager.INSTANCE.isReadyMarkerStack(itemStack)) {
			cir.setReturnValue(null);
		}
	}
}
