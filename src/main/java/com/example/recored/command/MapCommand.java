package com.example.recored.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.recored.RecoredPlugin;
import com.example.recored.game.Kit;
import com.example.recored.game.MapConfig;
import com.example.recored.game.MapPersistence;
import com.example.recored.game.MapRegistry;
import com.example.recored.game.Region;
import com.example.recored.game.Side;
import com.example.recored.game.Team;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * {@code /recored map ...} - registers and configures {@link MapConfig}s. Every subcommand takes
 * an explicit {@code <id>} (no "map currently being edited" state to lose track of).
 */
final class MapCommand {

	private final RecoredPlugin plugin;

	MapCommand(RecoredPlugin plugin) {
		this.plugin = plugin;
	}

	/** {@code args[0]} is "map"; {@code args[1]} (if present) is the subcommand. */
	void handle(CommandSender sender, String[] args) {
		if (!(sender instanceof Player p)) {
			sender.sendMessage(ChatColor.RED + "Only players can use that.");
			return;
		}
		if (!p.hasPermission("recored.admin")) {
			p.sendMessage(ChatColor.RED + "You don't have permission to do that.");
			return;
		}
		if (args.length < 2) {
			sendHelp(p);
			return;
		}
		String sub = args[1].toLowerCase();
		String[] rest = java.util.Arrays.copyOfRange(args, 2, args.length);
		switch (sub) {
			case "create" -> create(p, rest);
			case "corner1" -> corner(p, rest, 1);
			case "corner2" -> corner(p, rest, 2);
			case "setspawn" -> setSpawn(p, rest);
			case "addcore" -> addCore(p, rest);
			case "removecore" -> removeCore(p, rest);
			case "setregion" -> setRegion(p, rest);
			case "setkit" -> setKit(p, rest);
			case "save" -> save(p, rest);
			case "delete" -> delete(p, rest);
			case "list" -> list(p);
			default -> sendHelp(p);
		}
	}

	private void create(Player p, String[] args) {
		if (args.length < 1) {
			p.sendMessage(ChatColor.RED + "Usage: /recored map create <id>");
			return;
		}
		String id = args[0];
		if (MapRegistry.INSTANCE.exists(id)) {
			p.sendMessage(ChatColor.RED + "Map '" + id + "' already exists.");
			return;
		}
		MapRegistry.INSTANCE.create(id);
		MapPersistence.save(plugin);
		p.sendMessage(ChatColor.GREEN + "Created map '" + id + "'. Now set corner1/corner2, setspawn/addcore/setregion for "
				+ "each team, then map save " + id + ".");
	}

	private void corner(Player p, String[] args, int which) {
		MapConfig map = require(p, args);
		if (map == null) {
			return;
		}
		Block block = RecoredCommand.lookedAtBlock(p);
		if (block == null) {
			p.sendMessage(ChatColor.RED + "You must be looking at a block.");
			return;
		}
		if (which == 1) {
			map.corner1 = block.getLocation();
		} else {
			map.corner2 = block.getLocation();
		}
		MapPersistence.save(plugin);
		p.sendMessage(ChatColor.GREEN + map.id + " corner" + which + " = " + RecoredCommand.fmt(block.getLocation()));
		if (map.hasBoundingBox()) {
			p.sendMessage(ChatColor.GRAY + "Box: " + map.bounds().describe() + " - run /recored map save " + map.id + " to capture it.");
		}
	}

	private void setSpawn(Player p, String[] args) {
		if (args.length < 2 || teamArg(args[1]) == null) {
			p.sendMessage(ChatColor.RED + "Usage: /recored map setspawn <id> <red|blue>");
			return;
		}
		MapConfig map = require(p, args);
		if (map == null) {
			return;
		}
		Team team = teamArg(args[1]);
		map.teamSpawns.put(team, p.getLocation());
		MapPersistence.save(plugin);
		p.sendMessage(ChatColor.GREEN + map.id + " " + team.name() + " spawn set to " + RecoredCommand.fmt(p.getLocation()));
	}

	private void addCore(Player p, String[] args) {
		if (args.length < 3 || teamArg(args[1]) == null || sideArg(args[2]) == null) {
			p.sendMessage(ChatColor.RED + "Usage: /recored map addcore <id> <red|blue> <l|r>");
			return;
		}
		MapConfig map = require(p, args);
		if (map == null) {
			return;
		}
		Team team = teamArg(args[1]);
		Side side = sideArg(args[2]);
		Block block = RecoredCommand.lookedAtBlock(p);
		if (block == null) {
			p.sendMessage(ChatColor.RED + "You must be looking at a block.");
			return;
		}
		Location pos = block.getLocation();
		boolean alreadyCoreElsewhere = map.cores.values().stream().anyMatch(sides -> sides.containsValue(pos));
		if (alreadyCoreElsewhere) {
			p.sendMessage(ChatColor.RED + "That block is already a core on this map.");
			return;
		}
		map.cores.get(team).put(side, pos);
		MapPersistence.save(plugin);
		p.sendMessage(ChatColor.GREEN + "Set " + map.id + " " + team.name() + " " + side.label() + " core at " + RecoredCommand.fmt(pos));
	}

	private void removeCore(Player p, String[] args) {
		if (args.length < 3 || teamArg(args[1]) == null || sideArg(args[2]) == null) {
			p.sendMessage(ChatColor.RED + "Usage: /recored map removecore <id> <red|blue> <l|r>");
			return;
		}
		MapConfig map = require(p, args);
		if (map == null) {
			return;
		}
		Team team = teamArg(args[1]);
		Side side = sideArg(args[2]);
		Location removed = map.cores.get(team).remove(side);
		if (removed == null) {
			p.sendMessage(ChatColor.RED + "No " + team.name() + " " + side.label() + " core set on " + map.id);
			return;
		}
		MapPersistence.save(plugin);
		p.sendMessage(ChatColor.GREEN + "Removed " + map.id + " " + team.name() + " " + side.label() + " core (was at " + RecoredCommand.fmt(removed) + ")");
	}

	private void setRegion(Player p, String[] args) {
		if (args.length < 2 || teamArg(args[1]) == null) {
			p.sendMessage(ChatColor.RED + "Usage: /recored map setregion <id> <red|blue> (set pos1/pos2 first)");
			return;
		}
		MapConfig map = require(p, args);
		if (map == null) {
			return;
		}
		Team team = teamArg(args[1]);
		Location[] sel = RecoredCommand.SELECTIONS.get(p.getUniqueId());
		if (sel == null || sel[0] == null || sel[1] == null) {
			p.sendMessage(ChatColor.RED + "Set both corners first with /recored pos1 and /recored pos2");
			return;
		}
		Region region = Region.of(sel[0], sel[1]);
		map.spawnRegions.put(team, region);
		MapPersistence.save(plugin);
		p.sendMessage(ChatColor.GREEN + map.id + " " + team.name() + " spawn region set: " + region.describe());
	}

	private void setKit(Player p, String[] args) {
		if (args.length < 2 || teamArg(args[1]) == null) {
			p.sendMessage(ChatColor.RED + "Usage: /recored map setkit <id> <red|blue> (captures your current inventory)");
			return;
		}
		MapConfig map = require(p, args);
		if (map == null) {
			return;
		}
		Team team = teamArg(args[1]);
		PlayerInventory inv = p.getInventory();
		Map<Integer, ItemStack> slots = new HashMap<>();
		for (int slot = 0; slot < inv.getSize(); slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack != null && stack.getType() != org.bukkit.Material.AIR) {
				slots.put(slot, stack.clone());
			}
		}
		map.kits.put(team, new Kit(slots, inv.getHeldItemSlot()));
		MapPersistence.save(plugin);
		p.sendMessage(ChatColor.GREEN + "Captured " + slots.size() + " item stack(s) as the " + map.id + " " + team.name() + " kit.");
	}

	private void save(Player p, String[] args) {
		MapConfig map = require(p, args);
		if (map == null) {
			return;
		}
		if (!map.hasBoundingBox()) {
			p.sendMessage(ChatColor.RED + "Set corner1 and corner2 first.");
			return;
		}
		map.snapshot.capture(map.bounds());
		try {
			map.snapshot.save(MapPersistence.snapshotFile(plugin, map.id));
		} catch (IOException e) {
			plugin.getLogger().warning("Could not save map '" + map.id + "': " + e.getMessage());
			p.sendMessage(ChatColor.RED + "Captured it in memory, but couldn't write it to disk - see the server log.");
			return;
		}
		p.sendMessage(ChatColor.GREEN + "Saved " + map.id + " reset baseline: " + map.bounds().describe());
	}

	private void delete(Player p, String[] args) {
		if (args.length < 1) {
			p.sendMessage(ChatColor.RED + "Usage: /recored map delete <id>");
			return;
		}
		String id = args[0];
		if (MapRegistry.INSTANCE.remove(id)) {
			MapPersistence.snapshotFile(plugin, id).delete();
			MapPersistence.save(plugin);
			p.sendMessage(ChatColor.GREEN + "Deleted map '" + id + "'.");
		} else {
			p.sendMessage(ChatColor.RED + "No such map (or it's the active one - finish/reset the round first): " + id);
		}
	}

	private void list(Player p) {
		var maps = MapRegistry.INSTANCE.all();
		var active = MapRegistry.INSTANCE.activeMap();
		p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Maps (" + maps.size() + ")");
		if (maps.isEmpty()) {
			p.sendMessage(ChatColor.GRAY + "  none - /recored map create <id>");
		}
		for (MapConfig map : maps) {
			String line = "  " + map.id + (map == active ? " [ACTIVE]" : "") + " - "
					+ (map.hasBoundingBox() ? "box set" : "NO BOX") + ", "
					+ describeTeam(map, Team.RED) + ", " + describeTeam(map, Team.BLUE)
					+ ", " + (map.snapshot.isCaptured() ? "saved" : "NOT SAVED")
					+ " - " + (map.isReadyToPlay() ? "READY" : "NOT READY");
			p.sendMessage((map.isReadyToPlay() ? ChatColor.GREEN : ChatColor.RED) + line);
		}
	}

	private String describeTeam(MapConfig map, Team team) {
		Map<Side, Location> sides = map.cores.get(team);
		String coreSummary = "L" + (sides.containsKey(Side.LEFT) ? "✓" : "✗") + " R" + (sides.containsKey(Side.RIGHT) ? "✓" : "✗");
		return team.name() + ": " + (map.teamSpawns.containsKey(team) ? "spawn✓" : "NO SPAWN")
				+ " " + coreSummary + ", "
				+ (map.spawnRegions.containsKey(team) ? "region✓" : "no region") + ", "
				+ (map.kits.get(team).slots().isEmpty() ? "no kit" : map.kits.get(team).slots().size() + "-item kit");
	}

	private MapConfig require(Player p, String[] args) {
		if (args.length < 1) {
			p.sendMessage(ChatColor.RED + "Missing map id.");
			return null;
		}
		MapConfig map = MapRegistry.INSTANCE.get(args[0]);
		if (map == null) {
			p.sendMessage(ChatColor.RED + "No such map: " + args[0] + " (see /recored map list)");
		}
		return map;
	}

	private Team teamArg(String s) {
		if ("red".equalsIgnoreCase(s)) {
			return Team.RED;
		}
		if ("blue".equalsIgnoreCase(s)) {
			return Team.BLUE;
		}
		return null;
	}

	private Side sideArg(String s) {
		if ("l".equalsIgnoreCase(s)) {
			return Side.LEFT;
		}
		if ("r".equalsIgnoreCase(s)) {
			return Side.RIGHT;
		}
		return null;
	}

	private void sendHelp(Player p) {
		p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Recored maps");
		p.sendMessage(ChatColor.GRAY + "/recored map create <id>");
		p.sendMessage(ChatColor.GRAY + "/recored map corner1|corner2 <id>" + ChatColor.DARK_GRAY + " - look at a block");
		p.sendMessage(ChatColor.GRAY + "/recored map setspawn <id> <red|blue>" + ChatColor.DARK_GRAY + " - stand here");
		p.sendMessage(ChatColor.GRAY + "/recored map addcore|removecore <id> <red|blue> <l|r>" + ChatColor.DARK_GRAY + " - look at a block");
		p.sendMessage(ChatColor.GRAY + "/recored map setregion <id> <red|blue>" + ChatColor.DARK_GRAY + " - uses your pos1/pos2");
		p.sendMessage(ChatColor.GRAY + "/recored map setkit <id> <red|blue>" + ChatColor.DARK_GRAY + " - captures your inventory");
		p.sendMessage(ChatColor.GRAY + "/recored map save <id>" + ChatColor.DARK_GRAY + " - capture corner1/corner2 as the reset baseline");
		p.sendMessage(ChatColor.GRAY + "/recored map delete <id>");
		p.sendMessage(ChatColor.GRAY + "/recored map list");
	}

	List<String> tabComplete(CommandSender sender, String[] args) {
		List<String> options = new ArrayList<>();
		if (args.length == 2) {
			options.addAll(List.of("create", "corner1", "corner2", "setspawn", "addcore", "removecore", "setregion", "setkit", "save", "delete", "list"));
		} else if (args.length == 3 && !args[1].equalsIgnoreCase("create") && !args[1].equalsIgnoreCase("list")) {
			for (MapConfig map : MapRegistry.INSTANCE.all()) {
				options.add(map.id);
			}
		} else if (args.length == 4 && List.of("setspawn", "addcore", "removecore", "setregion", "setkit").contains(args[1].toLowerCase())) {
			options.addAll(List.of("red", "blue"));
		} else if (args.length == 5 && (args[1].equalsIgnoreCase("addcore") || args[1].equalsIgnoreCase("removecore"))) {
			options.addAll(List.of("l", "r"));
		}
		String current = args[args.length - 1].toLowerCase();
		return options.stream().filter(o -> o.toLowerCase().startsWith(current)).collect(Collectors.toList());
	}
}
