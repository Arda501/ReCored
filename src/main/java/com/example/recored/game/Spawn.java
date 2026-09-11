package com.example.recored.game;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A precise teleport target: position, facing direction and dimension. Used
 * both for a {@link MapConfig}'s per-team spawns and for the lobby spawn
 * ({@link GameManager#lobbySpawn}).
 */
public record Spawn(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {
	public BlockPos blockPos() {
		return BlockPos.containing(x, y, z);
	}
}
