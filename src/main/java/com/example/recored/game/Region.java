package com.example.recored.game;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * An axis-aligned, inclusive block box used for spawn protection and map snapshotting. Built
 * from any two opposite corners (order doesn't matter) - see {@code /recored pos1|pos2} and
 * {@code /recored map setregion}.
 */
public record Region(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

	public static Region of(Location a, Location b) {
		return new Region(a.getWorld(),
				Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
				Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()));
	}

	public boolean contains(Location loc) {
		return world.equals(loc.getWorld())
				&& loc.getBlockX() >= minX && loc.getBlockX() <= maxX
				&& loc.getBlockY() >= minY && loc.getBlockY() <= maxY
				&& loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ;
	}

	public Location min() {
		return new Location(world, minX, minY, minZ);
	}

	public Location max() {
		return new Location(world, maxX, maxY, maxZ);
	}

	public String describe() {
		return "(" + minX + "," + minY + "," + minZ + ") -> (" + maxX + "," + maxY + "," + maxZ + ")";
	}
}
