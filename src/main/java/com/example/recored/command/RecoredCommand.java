package com.example.recored.command;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.example.recored.RecoredPlugin;
import com.example.recored.game.MapConfig;
import com.example.recored.game.MapRegistry;
import com.example.recored.game.Region;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /recored ...}. Currently just the map-admin subset needed to define a map's bounding
 * box and save its reset baseline - {@code /recored map create|corner1|corner2|save|delete|list}.
 * The round engine (join/leave/start/etc., matching the fabric mod's full RecoredCommand) isn't
 * ported yet.
 */
public class RecoredCommand implements CommandExecutor, TabCompleter {

	private final RecoredPlugin plugin;

	public RecoredCommand(RecoredPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0 || !args[0].equalsIgnoreCase("map")) {
			sendHelp(sender);
			return true;
		}
		if (!(sender instanceof Player p)) {
			sender.sendMessage(ChatColor.RED + "Only players can use that.");
			return true;
		}
		if (!p.hasPermission("recored.admin")) {
			p.sendMessage(ChatColor.RED + "You don't have permission to do that.");
			return true;
		}
		if (args.length < 2) {
			sendHelp(sender);
			return true;
		}

		String sub = args[1].toLowerCase();
		String[] rest = java.util.Arrays.copyOfRange(args, 2, args.length);
		switch (sub) {
			case "create" -> create(p, rest);
			case "corner1" -> corner(p, rest, 1);
			case "corner2" -> corner(p, rest, 2);
			case "save" -> save(p, rest);
			case "delete" -> delete(p, rest);
			case "list" -> list(p);
			default -> sendHelp(sender);
		}
		return true;
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
		p.sendMessage(ChatColor.GREEN + "Created map '" + id + "'. Now stand at one corner of the map and run "
				+ "/recored map corner1 " + id + ", then the opposite corner and /recored map corner2 " + id
				+ ", then /recored map save " + id + " to capture its reset baseline.");
	}

	private void corner(Player p, String[] args, int which) {
		MapConfig map = require(p, args);
		if (map == null) {
			return;
		}
		if (which == 1) {
			map.corner1 = p.getLocation();
		} else {
			map.corner2 = p.getLocation();
		}
		p.sendMessage(ChatColor.GREEN + "Corner " + which + " for '" + map.id + "' set to your location.");
		if (map.hasBoundingBox()) {
			Region box = map.bounds();
			p.sendMessage(ChatColor.GRAY + "Box: " + box.describe() + " - run /recored map save " + map.id
					+ " to capture it as the reset baseline.");
		}
	}

	/** Captures every block currently inside the map's corner1/corner2 box as its reset baseline, and writes it to disk. */
	private void save(Player p, String[] args) {
		MapConfig map = require(p, args);
		if (map == null) {
			return;
		}
		if (!map.hasBoundingBox()) {
			p.sendMessage(ChatColor.RED + "Set corner1 and corner2 first: /recored map corner1|corner2 " + map.id);
			return;
		}
		map.snapshot.capture(map.bounds());
		try {
			map.snapshot.save(snapshotFile(map.id));
		} catch (IOException e) {
			plugin.getLogger().warning("Could not save map '" + map.id + "': " + e.getMessage());
			p.sendMessage(ChatColor.RED + "Captured it in memory, but couldn't write it to disk - see the server log. "
					+ "It won't survive a restart until this is fixed.");
			return;
		}
		Region box = map.bounds();
		p.sendMessage(ChatColor.GREEN + "Saved '" + map.id + "' as its reset baseline: " + box.describe());
	}

	private void delete(Player p, String[] args) {
		if (args.length < 1) {
			p.sendMessage(ChatColor.RED + "Usage: /recored map delete <id>");
			return;
		}
		String id = args[0];
		if (MapRegistry.INSTANCE.remove(id)) {
			snapshotFile(id).delete();
			p.sendMessage(ChatColor.GREEN + "Deleted map '" + id + "'.");
		} else {
			p.sendMessage(ChatColor.RED + "No such map (or it's currently active): " + id);
		}
	}

	private void list(Player p) {
		if (MapRegistry.INSTANCE.all().isEmpty()) {
			p.sendMessage(ChatColor.YELLOW + "No maps registered yet - /recored map create <id>");
			return;
		}
		p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Maps (" + MapRegistry.INSTANCE.all().size() + ")");
		for (MapConfig map : MapRegistry.INSTANCE.all()) {
			String line = "  " + map.id + " - " + (map.hasBoundingBox() ? "box set" : "NO BOX") + ", "
					+ (map.snapshot.isCaptured() ? "saved" : "NOT SAVED") + " - "
					+ (map.isReadyToPlay() ? "READY" : "not ready to play yet");
			p.sendMessage((map.isReadyToPlay() ? ChatColor.GREEN : ChatColor.RED) + line);
		}
	}

	private MapConfig require(Player p, String[] args) {
		if (args.length < 1) {
			p.sendMessage(ChatColor.RED + "Usage: /recored map <corner1|corner2|save> <id>");
			return null;
		}
		MapConfig map = MapRegistry.INSTANCE.get(args[0]);
		if (map == null) {
			p.sendMessage(ChatColor.RED + "No such map: " + args[0] + " (see /recored map list)");
		}
		return map;
	}

	private File snapshotFile(String id) {
		return new File(plugin.getDataFolder(), "maps/" + id + ".snapshot");
	}

	private void sendHelp(CommandSender sender) {
		sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Recored");
		sender.sendMessage(ChatColor.GRAY + "/recored map create <id>");
		sender.sendMessage(ChatColor.GRAY + "/recored map corner1 <id>" + ChatColor.DARK_GRAY + " - stand at one corner of the map");
		sender.sendMessage(ChatColor.GRAY + "/recored map corner2 <id>" + ChatColor.DARK_GRAY + " - stand at the opposite corner");
		sender.sendMessage(ChatColor.GRAY + "/recored map save <id>" + ChatColor.DARK_GRAY + " - capture the box as the reset baseline");
		sender.sendMessage(ChatColor.GRAY + "/recored map delete <id>");
		sender.sendMessage(ChatColor.GRAY + "/recored map list");
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		List<String> options = new ArrayList<>();
		if (args.length == 1) {
			options.add("map");
		} else if (args.length == 2) {
			options.addAll(List.of("create", "corner1", "corner2", "save", "delete", "list"));
		} else if (args.length == 3 && !args[1].equalsIgnoreCase("create") && !args[1].equalsIgnoreCase("list")) {
			for (MapConfig map : MapRegistry.INSTANCE.all()) {
				options.add(map.id);
			}
		}
		String current = args[args.length - 1].toLowerCase();
		return options.stream().filter(o -> o.toLowerCase().startsWith(current)).collect(Collectors.toList());
	}
}
