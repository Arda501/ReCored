package com.example.recored.game;

import java.util.Map;

import org.bukkit.inventory.ItemStack;

/**
 * A captured inventory: exact slot -&gt; stack (covers hotbar/main; armor and offhand use Bukkit's
 * own fixed slot numbers, see {@link GameManager#giveKit}), plus the selected hotbar slot.
 * Applying one always clears the destination inventory first.
 */
public record Kit(Map<Integer, ItemStack> slots, int selectedSlot) {
	public static final Kit EMPTY = new Kit(Map.of(), 0);
}
