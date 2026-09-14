package com.example.recored.game;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The "ready up" indicator every waiting-lobby player holds in hotbar slot 0: right-clicking it
 * toggles readiness. A gray dye means not ready, a lime dye means ready.
 */
public final class ReadyItem {

	public static final int SLOT = 0;

	private ReadyItem() {
	}

	private static NamespacedKey key(Plugin plugin) {
		return new NamespacedKey(plugin, "ready_item");
	}

	public static ItemStack notReady(Plugin plugin) {
		return build(plugin, Material.GRAY_DYE, ChatColor.RED + "" + ChatColor.BOLD + "Not Ready");
	}

	public static ItemStack ready(Plugin plugin) {
		return build(plugin, Material.LIME_DYE, ChatColor.GREEN + "" + ChatColor.BOLD + "Ready");
	}

	private static ItemStack build(Plugin plugin, Material material, String name) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		meta.setDisplayName(name);
		meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE, (byte) 1);
		stack.setItemMeta(meta);
		return stack;
	}

	public static boolean isReadyItem(Plugin plugin, ItemStack stack) {
		if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
			return false;
		}
		return stack.getItemMeta().getPersistentDataContainer().has(key(plugin), PersistentDataType.BYTE);
	}
}
