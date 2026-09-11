package com.example.recored.game;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.recored.RecoredMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * A registered, reusable map "blueprint": a bounding box, per-team spawns,
 * core positions, spawn-protection regions and kits, plus the saved structure
 * used to reset the box after a round. Never mutated during play - {@link
 * GameManager#beginStart} copies its data into GameManager's own working
 * fields for the live round, so the same map can be played again with its
 * original core positions intact.
 */
public final class MapConfig {

	public final String id;

	/** Opposite corners of the save/reset bounding box - order doesn't matter, see {@link #bounds()}. */
	public BlockPos corner1;
	public BlockPos corner2;

	public final Map<Team, Spawn> teamSpawns = new EnumMap<>(Team.class);
	/**
	 * Each team's own left/right cores, tracked independently - RED and BLUE
	 * each have their own {@link Side#LEFT}/{@link Side#RIGHT} slot (up to
	 * four cores total on a map), set via {@code /recored map addcore <id>
	 * <red|blue> <l|r>}. A slot is unset (absent from the inner map) until
	 * a core is registered for it.
	 */
	public final Map<Team, Map<Side, BlockPos>> cores = new EnumMap<>(Team.class);
	public final Map<Team, SpawnRegion> spawnRegions = new EnumMap<>(Team.class);
	public final Map<Team, Kit> kits = new EnumMap<>(Team.class);

	public MapConfig(String id) {
		this.id = id;
		for (Team t : Team.values()) {
			cores.put(t, new EnumMap<>(Side.class));
			kits.put(t, Kit.EMPTY);
		}
	}

	public boolean hasBoundingBox() {
		return corner1 != null && corner2 != null;
	}

	/** Min/max box derived from {@link #corner1}/{@link #corner2}, or {@code null} if either isn't set. */
	public SpawnRegion bounds() {
		return hasBoundingBox() ? SpawnRegion.of(corner1, corner2) : null;
	}

	/** Everything needed to actually play a round on this map. */
	public boolean isReadyToPlay() {
		if (!hasBoundingBox()) {
			return false;
		}
		for (Team t : Team.values()) {
			if (!teamSpawns.containsKey(t) || cores.get(t).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Identifier the reset baseline is saved/loaded under (a world-generated structure, not a datapack asset). */
	public Identifier structureId() {
		return Identifier.fromNamespaceAndPath(RecoredMod.MOD_ID, "maps/" + id);
	}

	/** Save the current blocks inside the bounding box as the reset baseline. False if the box isn't set. */
	public boolean saveStructure(MinecraftServer server) {
		SpawnRegion box = bounds();
		if (box == null) {
			return false;
		}
		ServerLevel level = server.overworld();
		Vec3i size = new Vec3i(
			box.max().getX() - box.min().getX() + 1,
			box.max().getY() - box.min().getY() + 1,
			box.max().getZ() - box.min().getZ() + 1);
		StructureTemplateManager manager = server.getStructureManager();
		Identifier id = structureId();
		StructureTemplate template = manager.getOrCreate(id);
		template.fillFromWorld(level, box.min(), size, false, List.of());
		return manager.save(id);
	}

	/** Reload the saved baseline back over the bounding box, undoing anything broken/built during a round. */
	public boolean resetStructure(MinecraftServer server) {
		SpawnRegion box = bounds();
		if (box == null) {
			return false;
		}
		StructureTemplateManager manager = server.getStructureManager();
		Optional<StructureTemplate> template = manager.get(structureId());
		if (template.isEmpty()) {
			return false;
		}
		ServerLevel level = server.overworld();
		template.get().placeInWorld(level, box.min(), BlockPos.ZERO, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_ALL);
		return true;
	}
}
