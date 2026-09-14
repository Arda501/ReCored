package com.example.recored.game;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * A block-for-block, contents-and-all snapshot of a {@link Region}: whatever was there the moment
 * it was captured, restorable exactly on demand - including chest/barrel/furnace/etc. contents,
 * which plain {@link BlockData} alone doesn't cover (it's just orientation/type/waterlogged/etc.,
 * not what's inside a container). Used both as a round's live "undo everything" baseline (captured
 * fresh at round start, restored at round end) and, saved to disk, as a map's persistent reset
 * baseline (survives a server restart) - the direct equivalent of the fabric mod's saved structure
 * template, without needing the structure-template API.
 */
public class MapSnapshot {

	private Region region;
	private final List<BlockData> blocks = new ArrayList<>();
	/** Positional index (into {@link #blocks}, same order) -&gt; that container's slot -&gt; item, for every non-empty slot. */
	private final Map<Integer, Map<Integer, ItemStack>> containers = new HashMap<>();

	public boolean isCaptured() {
		return region != null && !blocks.isEmpty();
	}

	/** Captures every block in {@code region} - and any container's contents - in a fixed x/y/z order also used by {@link #restore}. */
	public void capture(Region region) {
		this.region = region;
		blocks.clear();
		containers.clear();
		int index = 0;
		for (int x = region.minX(); x <= region.maxX(); x++) {
			for (int y = region.minY(); y <= region.maxY(); y++) {
				for (int z = region.minZ(); z <= region.maxZ(); z++) {
					Block block = region.world().getBlockAt(x, y, z);
					blocks.add(block.getBlockData().clone());
					captureContainer(block, index);
					index++;
				}
			}
		}
	}

	private void captureContainer(Block block, int index) {
		if (!(block.getState() instanceof InventoryHolder holder)) {
			return;
		}
		ItemStack[] contents = holder.getInventory().getContents();
		Map<Integer, ItemStack> slots = new HashMap<>();
		for (int slot = 0; slot < contents.length; slot++) {
			if (contents[slot] != null && contents[slot].getType() != org.bukkit.Material.AIR) {
				slots.put(slot, contents[slot].clone());
			}
		}
		if (!slots.isEmpty()) {
			containers.put(index, slots);
		}
	}

	/**
	 * Puts every captured block back exactly as it was, including container contents - any
	 * container in the region is always cleared and reset to its captured state (empty, if the
	 * baseline had nothing in it), not just the ones that had items. A no-op if nothing was ever
	 * captured/loaded.
	 */
	public void restore() {
		if (!isCaptured()) {
			return;
		}
		int index = 0;
		for (int x = region.minX(); x <= region.maxX(); x++) {
			for (int y = region.minY(); y <= region.maxY(); y++) {
				for (int z = region.minZ(); z <= region.maxZ(); z++) {
					region.world().getBlockAt(x, y, z).setBlockData(blocks.get(index), false);
					index++;
				}
			}
		}
		// Container contents are restored only after every block in the region has its final
		// BlockData - setting a container's inventory in the same pass as setBlockData (even on
		// the very same block, right after) can silently be discarded, seemingly by whatever
		// re-settles the tile entity as placement finishes.
		index = 0;
		for (int x = region.minX(); x <= region.maxX(); x++) {
			for (int y = region.minY(); y <= region.maxY(); y++) {
				for (int z = region.minZ(); z <= region.maxZ(); z++) {
					restoreContainer(region.world().getBlockAt(x, y, z), index);
					index++;
				}
			}
		}
	}

	private void restoreContainer(Block block, int index) {
		BlockState state = block.getState();
		if (!(state instanceof InventoryHolder holder)) {
			return;
		}
		Inventory inventory = holder.getInventory();
		inventory.clear();
		Map<Integer, ItemStack> slots = containers.get(index);
		if (slots != null) {
			for (Map.Entry<Integer, ItemStack> entry : slots.entrySet()) {
				inventory.setItem(entry.getKey(), entry.getValue());
			}
		}
		state.update(true, false);
	}

	public void save(File file) throws IOException {
		if (!isCaptured()) {
			return;
		}
		YamlConfiguration config = new YamlConfiguration();
		config.set("world", region.world().getName());
		config.set("minX", region.minX());
		config.set("minY", region.minY());
		config.set("minZ", region.minZ());
		config.set("maxX", region.maxX());
		config.set("maxY", region.maxY());
		config.set("maxZ", region.maxZ());

		List<String> blockStrings = new ArrayList<>(blocks.size());
		for (BlockData data : blocks) {
			blockStrings.add(data.getAsString());
		}
		config.set("blocks", blockStrings);

		for (Map.Entry<Integer, Map<Integer, ItemStack>> entry : containers.entrySet()) {
			String base = "containers." + entry.getKey() + ".";
			for (Map.Entry<Integer, ItemStack> slot : entry.getValue().entrySet()) {
				config.set(base + slot.getKey(), slot.getValue());
			}
		}

		Files.createDirectories(file.getParentFile().toPath());
		config.save(file);
	}

	/** @return {@code false} if the file doesn't exist or its world isn't loaded (loads nothing either way). */
	public boolean load(File file) throws IOException {
		if (!file.exists()) {
			return false;
		}
		YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
		World world = Bukkit.getWorld(config.getString("world", ""));
		if (world == null) {
			return false;
		}
		region = new Region(world, config.getInt("minX"), config.getInt("minY"), config.getInt("minZ"),
				config.getInt("maxX"), config.getInt("maxY"), config.getInt("maxZ"));

		blocks.clear();
		for (String s : config.getStringList("blocks")) {
			blocks.add(Bukkit.createBlockData(s));
		}

		containers.clear();
		ConfigurationSection containersSection = config.getConfigurationSection("containers");
		if (containersSection != null) {
			for (String indexKey : containersSection.getKeys(false)) {
				ConfigurationSection slotsSection = containersSection.getConfigurationSection(indexKey);
				if (slotsSection == null) {
					continue;
				}
				Map<Integer, ItemStack> slots = new HashMap<>();
				for (String slotKey : slotsSection.getKeys(false)) {
					ItemStack stack = slotsSection.getItemStack(slotKey);
					if (stack != null) {
						slots.put(Integer.parseInt(slotKey), stack);
					}
				}
				if (!slots.isEmpty()) {
					containers.put(Integer.parseInt(indexKey), slots);
				}
			}
		}
		return true;
	}
}
