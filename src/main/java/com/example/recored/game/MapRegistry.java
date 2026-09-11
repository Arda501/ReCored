package com.example.recored.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds every registered {@link MapConfig} and picks which one is active for
 * the current round. Round-robin over registration order, skipping any map
 * that isn't fully configured ({@link MapConfig#isReadyToPlay()}).
 */
public final class MapRegistry {

	public static final MapRegistry INSTANCE = new MapRegistry();

	/** Insertion order matters - it's the round-robin cycle order. */
	private final Map<String, MapConfig> maps = new LinkedHashMap<>();

	private String activeMapId;
	private int cursor = 0;

	private MapRegistry() {
	}

	public boolean exists(String id) {
		return maps.containsKey(id);
	}

	public MapConfig create(String id) {
		MapConfig config = new MapConfig(id);
		maps.put(id, config);
		return config;
	}

	/** Register an already-built config (e.g. one just deserialized by {@link MapPersistence}). */
	public void register(MapConfig config) {
		maps.put(config.id, config);
	}

	public MapConfig get(String id) {
		return maps.get(id);
	}

	public Collection<MapConfig> all() {
		return maps.values();
	}

	public boolean hasReadyMap() {
		for (MapConfig map : maps.values()) {
			if (map.isReadyToPlay()) {
				return true;
			}
		}
		return false;
	}

	public MapConfig activeMap() {
		return activeMapId != null ? maps.get(activeMapId) : null;
	}

	/**
	 * Pick and activate the next ready map after the last one played,
	 * wrapping around. Returns {@code null} if none are ready.
	 */
	public MapConfig activateNext() {
		if (maps.isEmpty()) {
			return null;
		}
		List<MapConfig> ordered = new ArrayList<>(maps.values());
		int count = ordered.size();
		for (int i = 0; i < count; i++) {
			int index = (cursor + i) % count;
			MapConfig candidate = ordered.get(index);
			if (candidate.isReadyToPlay()) {
				cursor = index + 1;
				activeMapId = candidate.id;
				return candidate;
			}
		}
		return null;
	}

	public void clearActive() {
		activeMapId = null;
	}

	/** Unregister a map. Refuses to remove the currently-active one (finish/reset the round first). */
	public boolean remove(String id) {
		if (id.equals(activeMapId)) {
			return false;
		}
		return maps.remove(id) != null;
	}
}
