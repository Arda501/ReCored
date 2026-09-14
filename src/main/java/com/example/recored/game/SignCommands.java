package com.example.recored.game;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.Location;

/**
 * Admin-configured "command signs": right-clicking a sign at a registered position runs the
 * attached command as the clicking player (see {@code RecoredListener}'s sign-click handler and
 * {@code RecoredCommand}'s {@code sign set}/{@code sign remove} subcommands). The text on the
 * sign itself is whatever the admin writes with vanilla sign editing - this only attaches a
 * command to a position, nothing about the sign's actual text. Persisted alongside maps/lobby.
 */
public final class SignCommands {

	public static final SignCommands INSTANCE = new SignCommands();

	private final Map<Location, String> commands = new LinkedHashMap<>();

	private SignCommands() {
	}

	public void set(Location pos, String command) {
		commands.put(key(pos), command);
	}

	/** @return {@code true} if a command was actually attached there to remove. */
	public boolean remove(Location pos) {
		return commands.remove(key(pos)) != null;
	}

	/** @return the command attached at {@code pos}, or {@code null} if none. */
	public String get(Location pos) {
		return commands.get(key(pos));
	}

	public Map<Location, String> all() {
		return commands;
	}

	private Location key(Location pos) {
		return new Location(pos.getWorld(), pos.getBlockX(), pos.getBlockY(), pos.getBlockZ());
	}
}
