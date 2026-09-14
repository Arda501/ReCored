package com.example.recored.game;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * A block-for-block snapshot of a {@link Region}: whatever was there the moment it was captured,
 * restorable exactly on demand. Used both as a round's live "undo everything" baseline (captured
 * fresh at round start, restored at round end) and, saved to disk, as a map's persistent reset
 * baseline (survives a server restart) - the direct equivalent of the fabric mod's saved
 * structure template, without needing the structure-template API.
 */
public class MapSnapshot {

	private Region region;
	private final List<BlockData> blocks = new ArrayList<>();

	public boolean isCaptured() {
		return region != null && !blocks.isEmpty();
	}

	/** Captures every block in {@code region}, in a fixed x/y/z order also used by {@link #restore}. */
	public void capture(Region region) {
		this.region = region;
		blocks.clear();
		for (int x = region.minX(); x <= region.maxX(); x++) {
			for (int y = region.minY(); y <= region.maxY(); y++) {
				for (int z = region.minZ(); z <= region.maxZ(); z++) {
					blocks.add(region.world().getBlockAt(x, y, z).getBlockData().clone());
				}
			}
		}
	}

	/** Puts every captured block back exactly as it was. A no-op if nothing was ever captured/loaded. */
	public void restore() {
		if (!isCaptured()) {
			return;
		}
		int i = 0;
		for (int x = region.minX(); x <= region.maxX(); x++) {
			for (int y = region.minY(); y <= region.maxY(); y++) {
				for (int z = region.minZ(); z <= region.maxZ(); z++) {
					Block block = region.world().getBlockAt(x, y, z);
					block.setBlockData(blocks.get(i++), false);
				}
			}
		}
	}

	public void save(File file) throws IOException {
		if (!isCaptured()) {
			return;
		}
		Files.createDirectories(file.getParentFile().toPath());
		try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
			writer.write(region.world().getName() + "," + region.minX() + "," + region.minY() + "," + region.minZ()
					+ "," + region.maxX() + "," + region.maxY() + "," + region.maxZ());
			writer.newLine();
			for (BlockData data : blocks) {
				writer.write(data.getAsString());
				writer.newLine();
			}
		}
	}

	/** @return {@code false} if the file doesn't exist or its world isn't loaded (loads nothing either way). */
	public boolean load(File file) throws IOException {
		if (!file.exists()) {
			return false;
		}
		try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			String header = reader.readLine();
			if (header == null) {
				return false;
			}
			String[] parts = header.split(",");
			World world = Bukkit.getWorld(parts[0]);
			if (world == null) {
				return false;
			}
			region = new Region(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
					Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), Integer.parseInt(parts[6]));
			blocks.clear();
			String line;
			while ((line = reader.readLine()) != null) {
				blocks.add(Bukkit.createBlockData(line));
			}
			return true;
		}
	}
}
