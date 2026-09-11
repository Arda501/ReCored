package com.example.recored.game;

import java.util.Map;

import net.minecraft.world.item.ItemStack;

/**
 * A captured inventory: exact slot -&gt; stack (covers hotbar/main/armor/offhand),
 * plus the selected hotbar slot. Applying one always clears the destination
 * inventory first - see {@link GameManager}'s kit-giving logic.
 */
public record Kit(Map<Integer, ItemStack> slots, int selectedSlot) {
	public static final Kit EMPTY = new Kit(Map.of(), 0);
}
