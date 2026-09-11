package com.example.recored.game;

import net.minecraft.core.BlockPos;

/**
 * An axis-aligned, inclusive block box used for spawn protection. Built from any
 * two opposite corners (order doesn't matter) - see {@code /recored pos1|pos2}
 * and {@code /recored map setregion}.
 */
public record SpawnRegion(BlockPos min, BlockPos max) {

	public static SpawnRegion of(BlockPos a, BlockPos b) {
		return new SpawnRegion(
			new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
			new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))
		);
	}

	public boolean contains(BlockPos pos) {
		return pos.getX() >= min.getX() && pos.getX() <= max.getX()
			&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
			&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}
}
