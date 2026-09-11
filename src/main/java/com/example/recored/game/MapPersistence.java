package com.example.recored.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.example.recored.RecoredMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.resources.RegistryOps;

/**
 * Saves/loads {@link MapRegistry}'s maps and {@link GameManager}'s lobby to a
 * plain compressed-NBT file under the world's own {@code data/} folder (the
 * same directory vanilla stores its own persistent per-world data in) -
 * <code>&lt;world&gt;/data/{@value RecoredMod#MOD_ID}_maps.dat</code>.
 *
 * <p>{@link MapRegistry}/{@link MapConfig} are otherwise pure in-memory Java
 * objects - nothing about a registered map (its id, corners, spawns, cores,
 * regions or kits) is written anywhere on its own. Only the *block contents*
 * of a map's bounding box get persisted independently, by {@code /recored map
 * save} (as a world-generated structure - see {@link MapConfig#saveStructure}).
 * So without this class, restarting the server drops every registered map
 * back to nothing (the structure file would still be sitting on disk, just
 * orphaned - no {@link MapConfig} left pointing at it) - which is exactly what
 * "my saved map was gone after restarting" was.
 *
 * <p>{@link #save} is called after every mutating {@code /recored map ...} /
 * {@code /recored setlobby} command (see {@code MapCommand}/{@code
 * RecoredCommand}), not just on a clean shutdown, so an ungraceful stop
 * (crash, kill) can't lose anything either. {@link #load} runs once, from
 * {@code ServerLifecycleEvents.SERVER_STARTED}.
 *
 * <p>Also covers {@link SignCommands} (the {@code /recored sign set|remove}
 * command-sign registry) - same file, same save-on-every-mutation/load-once
 * pattern, for the same reason.
 */
public final class MapPersistence {

	private static final String FILE_NAME = RecoredMod.MOD_ID + "_maps.dat";

	private MapPersistence() {
	}

	public static void save(MinecraftServer server) {
		try {
			CompoundTag root = new CompoundTag();
			GameManager gm = GameManager.INSTANCE;
			if (gm.lobbySpawn != null) {
				root.put("lobby_spawn", writeSpawn(gm.lobbySpawn));
			}
			if (gm.lobbyRegion != null) {
				root.put("lobby_region", writeRegion(gm.lobbyRegion));
			}
			ListTag mapList = new ListTag();
			for (MapConfig map : MapRegistry.INSTANCE.all()) {
				mapList.add(writeMap(map, server.registryAccess()));
			}
			root.put("maps", mapList);

			ListTag signList = new ListTag();
			for (Map.Entry<SignCommands.Key, String> entry : SignCommands.INSTANCE.all().entrySet()) {
				signList.add(writeSign(entry.getKey(), entry.getValue()));
			}
			root.put("signs", signList);

			Path file = file(server);
			Files.createDirectories(file.getParent());
			NbtIo.writeCompressed(root, file);
		} catch (IOException e) {
			RecoredMod.LOGGER.error("Failed to save Recored maps/lobby", e);
		}
	}

	public static void load(MinecraftServer server) {
		Path file = file(server);
		if (!Files.exists(file)) {
			return;
		}
		try {
			CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			GameManager gm = GameManager.INSTANCE;
			CompoundTag lobbySpawn = (CompoundTag) root.get("lobby_spawn");
			if (lobbySpawn != null) {
				gm.lobbySpawn = readSpawn(lobbySpawn);
			}
			CompoundTag lobbyRegion = (CompoundTag) root.get("lobby_region");
			if (lobbyRegion != null) {
				gm.lobbyRegion = readRegion(lobbyRegion);
			}
			int mapCount = 0;
			for (Tag tag : root.getListOrEmpty("maps")) {
				if (tag instanceof CompoundTag mapTag) {
					MapRegistry.INSTANCE.register(readMap(mapTag, server.registryAccess()));
					mapCount++;
				}
			}
			int signCount = 0;
			for (Tag tag : root.getListOrEmpty("signs")) {
				if (tag instanceof CompoundTag signTag) {
					readSign(signTag);
					signCount++;
				}
			}
			RecoredMod.LOGGER.info("Loaded {} saved map(s), {} command sign(s) and lobby config", mapCount, signCount);
		} catch (IOException e) {
			RecoredMod.LOGGER.error("Failed to load Recored maps/lobby - starting with none registered", e);
		}
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.DATA).resolve(FILE_NAME);
	}

	// --- MapConfig <-> NBT ---------------------------------------------------

	private static CompoundTag writeMap(MapConfig map, HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", map.id);
		if (map.corner1 != null) {
			tag.put("corner1", writeBlockPos(map.corner1));
		}
		if (map.corner2 != null) {
			tag.put("corner2", writeBlockPos(map.corner2));
		}
		CompoundTag spawns = new CompoundTag();
		for (Map.Entry<Team, Spawn> entry : map.teamSpawns.entrySet()) {
			spawns.put(entry.getKey().lowerName(), writeSpawn(entry.getValue()));
		}
		tag.put("spawns", spawns);
		CompoundTag cores = new CompoundTag();
		for (Map.Entry<Team, Map<Side, BlockPos>> entry : map.cores.entrySet()) {
			CompoundTag sides = new CompoundTag();
			for (Map.Entry<Side, BlockPos> sideEntry : entry.getValue().entrySet()) {
				sides.put(sideEntry.getKey().argName(), writeBlockPos(sideEntry.getValue()));
			}
			cores.put(entry.getKey().lowerName(), sides);
		}
		tag.put("cores", cores);
		CompoundTag regions = new CompoundTag();
		for (Map.Entry<Team, SpawnRegion> entry : map.spawnRegions.entrySet()) {
			regions.put(entry.getKey().lowerName(), writeRegion(entry.getValue()));
		}
		tag.put("regions", regions);
		CompoundTag kits = new CompoundTag();
		for (Map.Entry<Team, Kit> entry : map.kits.entrySet()) {
			kits.put(entry.getKey().lowerName(), writeKit(entry.getValue(), registries));
		}
		tag.put("kits", kits);
		return tag;
	}

	private static MapConfig readMap(CompoundTag tag, HolderLookup.Provider registries) {
		MapConfig map = new MapConfig(tag.getStringOr("id", "unknown"));
		CompoundTag corner1 = (CompoundTag) tag.get("corner1");
		if (corner1 != null) {
			map.corner1 = readBlockPos(corner1);
		}
		CompoundTag corner2 = (CompoundTag) tag.get("corner2");
		if (corner2 != null) {
			map.corner2 = readBlockPos(corner2);
		}
		CompoundTag spawns = tag.getCompoundOrEmpty("spawns");
		CompoundTag cores = tag.getCompoundOrEmpty("cores");
		CompoundTag regions = tag.getCompoundOrEmpty("regions");
		CompoundTag kits = tag.getCompoundOrEmpty("kits");
		for (Team team : Team.values()) {
			CompoundTag spawnTag = (CompoundTag) spawns.get(team.lowerName());
			if (spawnTag != null) {
				map.teamSpawns.put(team, readSpawn(spawnTag));
			}
			CompoundTag sides = cores.getCompoundOrEmpty(team.lowerName());
			for (Side side : Side.values()) {
				CompoundTag posTag = (CompoundTag) sides.get(side.argName());
				if (posTag != null) {
					map.cores.get(team).put(side, readBlockPos(posTag));
				}
			}
			CompoundTag regionTag = (CompoundTag) regions.get(team.lowerName());
			if (regionTag != null) {
				map.spawnRegions.put(team, readRegion(regionTag));
			}
			CompoundTag kitTag = (CompoundTag) kits.get(team.lowerName());
			if (kitTag != null) {
				map.kits.put(team, readKit(kitTag, registries));
			}
		}
		return map;
	}

	// --- SignCommands <-> NBT --------------------------------------------------

	private static CompoundTag writeSign(SignCommands.Key key, String command) {
		CompoundTag tag = new CompoundTag();
		tag.putString("dimension", key.dimension().identifier().toString());
		tag.put("pos", writeBlockPos(key.pos()));
		tag.putString("command", command);
		return tag;
	}

	private static void readSign(CompoundTag tag) {
		Identifier dimensionId = Identifier.parse(tag.getStringOr("dimension", "minecraft:overworld"));
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
		CompoundTag posTag = (CompoundTag) tag.get("pos");
		if (posTag == null) {
			return;
		}
		BlockPos pos = readBlockPos(posTag);
		String command = tag.getStringOr("command", null);
		if (command == null || command.isBlank()) {
			return;
		}
		SignCommands.INSTANCE.set(new SignCommands.Key(dimension, pos), command);
	}

	// --- value type <-> NBT ---------------------------------------------------

	private static CompoundTag writeBlockPos(BlockPos pos) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("x", pos.getX());
		tag.putInt("y", pos.getY());
		tag.putInt("z", pos.getZ());
		return tag;
	}

	private static BlockPos readBlockPos(CompoundTag tag) {
		return new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
	}

	private static CompoundTag writeRegion(SpawnRegion region) {
		CompoundTag tag = new CompoundTag();
		tag.put("min", writeBlockPos(region.min()));
		tag.put("max", writeBlockPos(region.max()));
		return tag;
	}

	private static SpawnRegion readRegion(CompoundTag tag) {
		return SpawnRegion.of(readBlockPos((CompoundTag) tag.get("min")), readBlockPos((CompoundTag) tag.get("max")));
	}

	private static CompoundTag writeSpawn(Spawn spawn) {
		CompoundTag tag = new CompoundTag();
		tag.putString("dimension", spawn.dimension().identifier().toString());
		tag.putDouble("x", spawn.x());
		tag.putDouble("y", spawn.y());
		tag.putDouble("z", spawn.z());
		tag.putFloat("yaw", spawn.yaw());
		tag.putFloat("pitch", spawn.pitch());
		return tag;
	}

	private static Spawn readSpawn(CompoundTag tag) {
		Identifier dimensionId = Identifier.parse(tag.getStringOr("dimension", "minecraft:overworld"));
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
		return new Spawn(
			dimension,
			tag.getDoubleOr("x", 0.0),
			tag.getDoubleOr("y", 0.0),
			tag.getDoubleOr("z", 0.0),
			tag.getFloatOr("yaw", 0.0F),
			tag.getFloatOr("pitch", 0.0F));
	}

	private static CompoundTag writeKit(Kit kit, HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("selectedSlot", kit.selectedSlot());
		RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
		ListTag slots = new ListTag();
		for (Map.Entry<Integer, ItemStack> entry : kit.slots().entrySet()) {
			Tag itemTag = ItemStack.CODEC.encodeStart(ops, entry.getValue()).result().orElse(null);
			if (itemTag == null) {
				continue; // shouldn't happen for our own captured stacks, but don't lose the whole kit over one bad item
			}
			CompoundTag entryTag = new CompoundTag();
			entryTag.putInt("slot", entry.getKey());
			entryTag.put("item", itemTag);
			slots.add(entryTag);
		}
		tag.put("slots", slots);
		return tag;
	}

	private static Kit readKit(CompoundTag tag, HolderLookup.Provider registries) {
		RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
		Map<Integer, ItemStack> slots = new HashMap<>();
		for (Tag entryTag : tag.getListOrEmpty("slots")) {
			if (!(entryTag instanceof CompoundTag entry)) {
				continue;
			}
			int slot = entry.getIntOr("slot", -1);
			Tag itemTag = entry.get("item");
			if (slot < 0 || itemTag == null) {
				continue;
			}
			ItemStack stack = ItemStack.CODEC.parse(ops, itemTag).result().orElse(ItemStack.EMPTY);
			if (!stack.isEmpty()) {
				slots.put(slot, stack);
			}
		}
		return new Kit(slots, tag.getIntOr("selectedSlot", 0));
	}
}
