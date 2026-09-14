package com.example.recored.game;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Saves/loads {@link MapRegistry}'s maps, {@link GameManager}'s lobby, and {@link SignCommands}
 * to a plain YAML file under the plugin's data folder - {@code <datafolder>/recored.yml} - plus
 * each map's block-content baseline, saved separately as {@code <datafolder>/maps/<id>.snapshot}
 * (see {@link MapSnapshot}).
 *
 * <p>{@link MapRegistry}/{@link MapConfig} are otherwise pure in-memory objects - nothing about a
 * registered map (its id, corners, spawns, cores, regions or kits) is written anywhere on its
 * own. So without this class, restarting the server drops every registered map back to nothing
 * (its saved {@code .snapshot} file would still be sitting on disk, just orphaned).
 *
 * <p>{@link #save} is called after every mutating {@code /recored ...} command, not just on a
 * clean shutdown, so an ungraceful stop can't lose anything either. {@link #load} runs once, from
 * {@code RecoredPlugin#onEnable}.
 */
public final class MapPersistence {

	private MapPersistence() {
	}

	public static File snapshotFile(Plugin plugin, String mapId) {
		return new File(plugin.getDataFolder(), "maps/" + mapId + ".snapshot");
	}

	private static File dataFile(Plugin plugin) {
		return new File(plugin.getDataFolder(), "recored.yml");
	}

	public static void save(Plugin plugin) {
		YamlConfiguration config = new YamlConfiguration();
		GameManager gm = GameManager.INSTANCE;
		if (gm.lobbySpawn != null) {
			config.set("lobby.spawn", locToString(gm.lobbySpawn));
		}
		if (gm.lobbyRegion != null) {
			config.set("lobby.region", regionToString(gm.lobbyRegion));
		}

		for (MapConfig map : MapRegistry.INSTANCE.all()) {
			String base = "maps." + map.id + ".";
			if (map.corner1 != null) {
				config.set(base + "corner1", locToString(map.corner1));
			}
			if (map.corner2 != null) {
				config.set(base + "corner2", locToString(map.corner2));
			}
			for (Team team : Team.values()) {
				String teamBase = base + team.lowerName() + ".";
				if (map.teamSpawns.containsKey(team)) {
					config.set(teamBase + "spawn", locToString(map.teamSpawns.get(team)));
				}
				if (map.spawnRegions.containsKey(team)) {
					config.set(teamBase + "region", regionToString(map.spawnRegions.get(team)));
				}
				for (Side side : Side.values()) {
					Location pos = map.cores.get(team).get(side);
					if (pos != null) {
						config.set(teamBase + "cores." + side.argName(), locToString(pos));
					}
				}
				Kit kit = map.kits.get(team);
				if (kit != null && !kit.slots().isEmpty()) {
					config.set(teamBase + "kit.selectedSlot", kit.selectedSlot());
					for (Map.Entry<Integer, ItemStack> entry : kit.slots().entrySet()) {
						config.set(teamBase + "kit.slots." + entry.getKey(), entry.getValue());
					}
				}
			}
		}

		int i = 0;
		for (Map.Entry<Location, String> entry : SignCommands.INSTANCE.all().entrySet()) {
			String base = "signs." + i + ".";
			config.set(base + "pos", locToString(entry.getKey()));
			config.set(base + "command", entry.getValue());
			i++;
		}

		try {
			config.save(dataFile(plugin));
		} catch (IOException e) {
			plugin.getLogger().warning("Could not save recored.yml: " + e.getMessage());
		}
	}

	public static void load(Plugin plugin) {
		File file = dataFile(plugin);
		if (!file.exists()) {
			return;
		}
		YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
		GameManager gm = GameManager.INSTANCE;
		gm.lobbySpawn = stringToLoc(config.getString("lobby.spawn"));
		gm.lobbyRegion = stringToRegion(config.getString("lobby.region"));

		int mapCount = 0;
		ConfigurationSection mapsSection = config.getConfigurationSection("maps");
		if (mapsSection != null) {
			for (String id : mapsSection.getKeys(false)) {
				MapConfig map = new MapConfig(id);
				String base = "maps." + id + ".";
				map.corner1 = stringToLoc(config.getString(base + "corner1"));
				map.corner2 = stringToLoc(config.getString(base + "corner2"));
				for (Team team : Team.values()) {
					String teamBase = base + team.lowerName() + ".";
					Location spawn = stringToLoc(config.getString(teamBase + "spawn"));
					if (spawn != null) {
						map.teamSpawns.put(team, spawn);
					}
					Region region = stringToRegion(config.getString(teamBase + "region"));
					if (region != null) {
						map.spawnRegions.put(team, region);
					}
					for (Side side : Side.values()) {
						Location corePos = stringToLoc(config.getString(teamBase + "cores." + side.argName()));
						if (corePos != null) {
							map.cores.get(team).put(side, corePos);
						}
					}
					ConfigurationSection kitSlots = config.getConfigurationSection(teamBase + "kit.slots");
					if (kitSlots != null) {
						Map<Integer, ItemStack> slots = new HashMap<>();
						for (String slotKey : kitSlots.getKeys(false)) {
							ItemStack stack = kitSlots.getItemStack(slotKey);
							if (stack != null) {
								slots.put(Integer.parseInt(slotKey), stack);
							}
						}
						int selected = config.getInt(teamBase + "kit.selectedSlot", 0);
						map.kits.put(team, new Kit(slots, selected));
					}
				}
				try {
					map.snapshot.load(snapshotFile(plugin, id));
				} catch (IOException e) {
					plugin.getLogger().warning("Could not load saved blocks for map '" + id + "': " + e.getMessage());
				}
				MapRegistry.INSTANCE.register(map);
				mapCount++;
			}
		}

		int signCount = 0;
		ConfigurationSection signsSection = config.getConfigurationSection("signs");
		if (signsSection != null) {
			for (String key : signsSection.getKeys(false)) {
				Location pos = stringToLoc(config.getString("signs." + key + ".pos"));
				String command = config.getString("signs." + key + ".command");
				if (pos != null && command != null && !command.isBlank()) {
					SignCommands.INSTANCE.set(pos, command);
					signCount++;
				}
			}
		}

		plugin.getLogger().info("Loaded " + mapCount + " saved map(s), " + signCount + " command sign(s) and lobby config.");
	}

	// --- value type <-> string ---------------------------------------------

	private static String locToString(Location loc) {
		if (loc == null || loc.getWorld() == null) {
			return null;
		}
		return loc.getWorld().getName() + "," + loc.getX() + "," + loc.getY() + "," + loc.getZ() + "," + loc.getYaw() + "," + loc.getPitch();
	}

	private static Location stringToLoc(String s) {
		if (s == null) {
			return null;
		}
		String[] p = s.split(",");
		World world = Bukkit.getWorld(p[0]);
		if (world == null || p.length < 4) {
			return null;
		}
		float yaw = p.length > 4 ? Float.parseFloat(p[4]) : 0F;
		float pitch = p.length > 5 ? Float.parseFloat(p[5]) : 0F;
		return new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), yaw, pitch);
	}

	private static String regionToString(Region r) {
		return r.world().getName() + "," + r.minX() + "," + r.minY() + "," + r.minZ() + "," + r.maxX() + "," + r.maxY() + "," + r.maxZ();
	}

	private static Region stringToRegion(String s) {
		if (s == null) {
			return null;
		}
		String[] p = s.split(",");
		World world = Bukkit.getWorld(p[0]);
		if (world == null || p.length < 7) {
			return null;
		}
		return new Region(world, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
				Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
	}
}
