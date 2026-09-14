package com.example.recored.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.recored.RecoredPlugin;
import com.example.recored.game.GameManager;
import com.example.recored.game.MapPersistence;
import com.example.recored.game.MapRegistry;
import com.example.recored.game.Phase;
import com.example.recored.game.Region;
import com.example.recored.game.SignCommands;
import com.example.recored.game.Team;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

/**
 * All {@code /recored ...} subcommands except {@code map ...}, which {@link MapCommand} builds
 * (shares this class's raycast helper, admin gate and pos1/pos2 selection).
 */
public class RecoredCommand implements CommandExecutor, TabCompleter {

	private static final double RAYCAST_REACH = 24.0;

	/** Per-player WorldEdit-style scratch selection (pos1/pos2). Shared with {@link MapCommand}. */
	static final Map<UUID, Location[]> SELECTIONS = new HashMap<>();

	private final RecoredPlugin plugin;
	private final MapCommand mapCommand;

	public RecoredCommand(RecoredPlugin plugin) {
		this.plugin = plugin;
		this.mapCommand = new MapCommand(plugin);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			sendHelp(sender);
			return true;
		}
		String sub = args[0].toLowerCase();
		if (sub.equals("map")) {
			mapCommand.handle(sender, args);
			return true;
		}

		switch (sub) {
			case "join" -> join(sender, args);
			case "leave" -> leave(sender);
			case "status" -> status(sender);
			case "pos1" -> setPos(sender, 0);
			case "pos2" -> setPos(sender, 1);
			case "setlobby" -> admin(sender, this::setLobby);
			case "start" -> admin(sender, this::start);
			case "reset" -> admin(sender, this::reset);
			case "respawndelay" -> admin(sender, args, this::setRespawnDelay);
			case "coretime" -> admin(sender, args, this::setCoreTime);
			case "sign" -> admin(sender, args, this::sign);
			default -> sendHelp(sender);
		}
		return true;
	}

	private interface PlayerAction {
		void run(Player p);
	}

	private interface PlayerArgsAction {
		void run(Player p, String[] args);
	}

	private void admin(CommandSender sender, PlayerAction action) {
		admin(sender, null, (p, a) -> action.run(p));
	}

	private void admin(CommandSender sender, String[] args, PlayerArgsAction action) {
		if (!(sender instanceof Player p)) {
			sender.sendMessage(ChatColor.RED + "Only players can use that.");
			return;
		}
		if (!p.hasPermission("recored.admin")) {
			p.sendMessage(ChatColor.RED + "You don't have permission to do that.");
			return;
		}
		action.run(p, args);
	}

	// --- open to everyone ---------------------------------------------------

	private void join(CommandSender sender, String[] args) {
		if (!(sender instanceof Player p)) {
			sender.sendMessage(ChatColor.RED + "Only players can join.");
			return;
		}
		if (args.length < 2 || (!args[1].equalsIgnoreCase("red") && !args[1].equalsIgnoreCase("blue"))) {
			p.sendMessage(ChatColor.RED + "Usage: /recored join <red|blue>");
			return;
		}
		GameManager gm = GameManager.INSTANCE;
		if (gm.phase != Phase.WAITING) {
			p.sendMessage(ChatColor.RED + "Can only join while WAITING (currently " + gm.phase + ")");
			return;
		}
		Team team = Team.valueOf(args[1].toUpperCase());
		gm.players.put(p.getUniqueId(), team);
		gm.joinScoreboardTeam(p, team);
		gm.giveReadyItem(p);
		p.sendMessage(ChatColor.GREEN + "Joined " + team.colour() + team.name() + ChatColor.GREEN
				+ " team - right-click your item when you're ready.");
	}

	private void leave(CommandSender sender) {
		if (!(sender instanceof Player p)) {
			sender.sendMessage(ChatColor.RED + "Only players can leave.");
			return;
		}
		GameManager gm = GameManager.INSTANCE;
		if (gm.phase != Phase.WAITING) {
			p.sendMessage(ChatColor.RED + "Can only leave while WAITING (currently " + gm.phase + ")");
			return;
		}
		if (gm.players.remove(p.getUniqueId()) == null) {
			p.sendMessage(ChatColor.RED + "You are not on a team.");
			return;
		}
		gm.leaveScoreboardTeam(p);
		gm.forgetReady(p);
		p.sendMessage(ChatColor.GREEN + "Left your team.");
	}

	private void status(CommandSender sender) {
		GameManager gm = GameManager.INSTANCE;
		sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Recored status");
		sender.sendMessage(ChatColor.GRAY + "  phase: " + ChatColor.WHITE + gm.phase);
		sender.sendMessage(ChatColor.GRAY + "  respawn delay: " + ChatColor.WHITE + (gm.respawnDelayTicks / 20.0) + "s");
		sender.sendMessage(ChatColor.GRAY + "  core mining time: " + ChatColor.WHITE + gm.coreMiningSeconds + "s");
		sender.sendMessage(ChatColor.GRAY + "  lobby: " + ChatColor.WHITE + (gm.lobbySpawn != null ? "spawn set" : "NO SPAWN")
				+ ", " + (gm.lobbyRegion != null ? "region set" : "no region"));
		var active = MapRegistry.INSTANCE.activeMap();
		sender.sendMessage(ChatColor.GRAY + "  maps: " + ChatColor.WHITE + MapRegistry.INSTANCE.all().size()
				+ " registered, active: " + (active != null ? active.id : "none"));
		for (Team team : Team.values()) {
			long members = gm.players.values().stream().filter(t -> t == team).count();
			String line = "  " + team.name() + ": " + members + " player(s)"
					+ (gm.phase == Phase.RUNNING ? ", " + gm.cores.get(team).size() + " core(s) remaining this round" : "");
			sender.sendMessage(team.colour() + line);
		}
	}

	private void setPos(CommandSender sender, int index) {
		if (!(sender instanceof Player p)) {
			sender.sendMessage(ChatColor.RED + "Only players can use that.");
			return;
		}
		if (!p.hasPermission("recored.admin")) {
			p.sendMessage(ChatColor.RED + "You don't have permission to do that.");
			return;
		}
		Block block = lookedAtBlock(p);
		if (block == null) {
			p.sendMessage(ChatColor.RED + "You must be looking at a block.");
			return;
		}
		SELECTIONS.computeIfAbsent(p.getUniqueId(), k -> new Location[2])[index] = block.getLocation();
		p.sendMessage(ChatColor.GREEN + "pos" + (index + 1) + " = " + fmt(block.getLocation()));
	}

	// --- admin ---------------------------------------------------------------

	private void setLobby(Player p) {
		Location[] sel = SELECTIONS.get(p.getUniqueId());
		if (sel == null || sel[0] == null || sel[1] == null) {
			p.sendMessage(ChatColor.RED + "Set both corners first with /recored pos1 and /recored pos2");
			return;
		}
		GameManager gm = GameManager.INSTANCE;
		gm.lobbySpawn = p.getLocation();
		gm.lobbyRegion = Region.of(sel[0], sel[1]);
		MapPersistence.save(plugin);
		p.sendMessage(ChatColor.GREEN + "Lobby spawn set to " + fmt(gm.lobbySpawn) + ", region " + gm.lobbyRegion.describe());
	}

	private void start(Player p) {
		GameManager gm = GameManager.INSTANCE;
		if (gm.phase != Phase.WAITING) {
			p.sendMessage(ChatColor.RED + "Game is not in WAITING (" + gm.phase + ")");
			return;
		}
		if (!gm.readyToStart()) {
			p.sendMessage(ChatColor.RED + "Not ready: need a player on each team and at least one fully-configured map "
					+ "(bounding box + save, both team spawns, both teams' cores - see /recored map list)");
			return;
		}
		gm.beginStart();
	}

	private void reset(Player p) {
		GameManager.INSTANCE.reset();
		p.sendMessage(ChatColor.GREEN + "Recored reset to WAITING.");
	}

	private void setRespawnDelay(Player p, String[] args) {
		if (args.length < 2) {
			p.sendMessage(ChatColor.RED + "Usage: /recored respawndelay <seconds>");
			return;
		}
		try {
			double seconds = Double.parseDouble(args[1]);
			int ticks = (int) Math.round(seconds * 20.0);
			GameManager.INSTANCE.respawnDelayTicks = ticks;
			p.sendMessage(ChatColor.GREEN + "Respawn delay set to " + ticks + " ticks (" + (ticks / 20.0) + "s).");
		} catch (NumberFormatException e) {
			p.sendMessage(ChatColor.RED + "'" + args[1] + "' isn't a number.");
		}
	}

	private void setCoreTime(Player p, String[] args) {
		if (args.length < 2) {
			p.sendMessage(ChatColor.RED + "Usage: /recored coretime <seconds>");
			return;
		}
		try {
			double seconds = Double.parseDouble(args[1]);
			GameManager.INSTANCE.coreMiningSeconds = Math.max(0.5, seconds);
			p.sendMessage(ChatColor.GREEN + "Core mining time set to " + GameManager.INSTANCE.coreMiningSeconds + "s.");
		} catch (NumberFormatException e) {
			p.sendMessage(ChatColor.RED + "'" + args[1] + "' isn't a number.");
		}
	}

	private void sign(Player p, String[] args) {
		if (args.length < 2) {
			p.sendMessage(ChatColor.RED + "Usage: /recored sign <set <command...>|remove>");
			return;
		}
		Block block = lookedAtBlock(p);
		if (block == null || !(block.getState() instanceof Sign)) {
			p.sendMessage(ChatColor.RED + "You must be looking at a sign.");
			return;
		}
		if (args[1].equalsIgnoreCase("remove")) {
			if (SignCommands.INSTANCE.remove(block.getLocation())) {
				MapPersistence.save(plugin);
				p.sendMessage(ChatColor.GREEN + "Removed the command from that sign.");
			} else {
				p.sendMessage(ChatColor.RED + "That sign has no command attached.");
			}
			return;
		}
		if (args[1].equalsIgnoreCase("set")) {
			if (args.length < 3) {
				p.sendMessage(ChatColor.RED + "Usage: /recored sign set <command...>");
				return;
			}
			String cmd = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
			SignCommands.INSTANCE.set(block.getLocation(), cmd);
			MapPersistence.save(plugin);
			p.sendMessage(ChatColor.GREEN + "That sign now runs: /" + cmd);
			return;
		}
		p.sendMessage(ChatColor.RED + "Usage: /recored sign <set <command...>|remove>");
	}

	// --- helpers, shared with MapCommand -------------------------------------

	static Block lookedAtBlock(Player p) {
		RayTraceResult result = p.rayTraceBlocks(RAYCAST_REACH);
		return result != null ? result.getHitBlock() : null;
	}

	static String fmt(Location loc) {
		return String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ());
	}

	private void sendHelp(CommandSender sender) {
		sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Recored");
		sender.sendMessage(ChatColor.GRAY + "/recored join <red|blue>" + ChatColor.DARK_GRAY + " - join a team");
		sender.sendMessage(ChatColor.GRAY + "/recored leave");
		sender.sendMessage(ChatColor.GRAY + "/recored status");
		sender.sendMessage(ChatColor.DARK_GRAY + "Right-click your item in the lobby to ready up.");
		if (sender.hasPermission("recored.admin")) {
			sender.sendMessage(ChatColor.GRAY + "/recored pos1|pos2" + ChatColor.DARK_GRAY + " - look at a block, mark a corner");
			sender.sendMessage(ChatColor.GRAY + "/recored setlobby" + ChatColor.DARK_GRAY + " - lobby spawn = here, region = pos1/pos2");
			sender.sendMessage(ChatColor.GRAY + "/recored start" + ChatColor.DARK_GRAY + " - force-start (bypasses ready-up)");
			sender.sendMessage(ChatColor.GRAY + "/recored reset" + ChatColor.DARK_GRAY + " - abort the round, reset to WAITING");
			sender.sendMessage(ChatColor.GRAY + "/recored respawndelay <seconds>");
			sender.sendMessage(ChatColor.GRAY + "/recored coretime <seconds>");
			sender.sendMessage(ChatColor.GRAY + "/recored sign set <command...>|remove" + ChatColor.DARK_GRAY + " - look at a sign");
			sender.sendMessage(ChatColor.GRAY + "/recored map ..." + ChatColor.DARK_GRAY + " - see /recored map for map setup");
		}
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		List<String> options = new ArrayList<>();
		if (args.length >= 1 && args[0].equalsIgnoreCase("map")) {
			return mapCommand.tabComplete(sender, args);
		}
		if (args.length == 1) {
			options.addAll(List.of("join", "leave", "status"));
			if (sender.hasPermission("recored.admin")) {
				options.addAll(List.of("pos1", "pos2", "setlobby", "start", "reset", "respawndelay", "coretime", "sign", "map"));
			}
		} else if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
			options.addAll(List.of("red", "blue"));
		} else if (args.length == 2 && args[0].equalsIgnoreCase("sign")) {
			options.addAll(List.of("set", "remove"));
		}
		String current = args[args.length - 1].toLowerCase();
		return options.stream().filter(o -> o.toLowerCase().startsWith(current)).collect(Collectors.toList());
	}
}
