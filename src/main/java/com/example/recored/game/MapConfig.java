package com.example.recored.game;

import java.util.EnumMap;
import java.util.Map;

import org.bukkit.Location;

/**
 * A registered, reusable map "blueprint": a bounding box, per-team spawns, core positions,
 * spawn-protection regions and kits, plus the saved baseline used to reset the box after a round.
 * Never mutated during play - {@link GameManager#beginStart} copies its data into GameManager's
 * own working fields for the live round, so the same map can be played again with its original
 * core positions intact.
 */
public final class MapConfig {

	public final String id;

	/** Opposite corners of the save/reset bounding box - order doesn't matter, see {@link #bounds()}. */
	public Location corner1;
	public Location corner2;

	public final Map<Team, Location> teamSpawns = new EnumMap<>(Team.class);
	/**
	 * Each team's own left/right cores, tracked independently - RED and BLUE each have their own
	 * {@link Side#LEFT}/{@link Side#RIGHT} slot (up to four cores total on a map), set via
	 * {@code /recored map addcore <id> <red|blue> <l|r>}. A slot is unset until a core is
	 * registered for it.
	 */
	public final Map<Team, Map<Side, Location>> cores = new EnumMap<>(Team.class);
	public final Map<Team, Region> spawnRegions = new EnumMap<>(Team.class);
	public final Map<Team, Kit> kits = new EnumMap<>(Team.class);

	/** This map's saved reset baseline - captured by {@code /recored map save}, persisted to its own file. */
	public final MapSnapshot snapshot = new MapSnapshot();

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
	public Region bounds() {
		return hasBoundingBox() ? Region.of(corner1, corner2) : null;
	}

	/** Everything needed to actually play a round on this map. */
	public boolean isReadyToPlay() {
		if (!hasBoundingBox() || !snapshot.isCaptured()) {
			return false;
		}
		for (Team t : Team.values()) {
			if (!teamSpawns.containsKey(t) || cores.get(t).isEmpty()) {
				return false;
			}
		}
		return true;
	}
}
