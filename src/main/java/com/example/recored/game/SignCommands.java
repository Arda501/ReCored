package com.example.recored.game;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Admin-configured "command signs": right-clicking a sign at a registered
 * position runs the attached command as the clicking player (see {@code
 * RecoredMod}'s sign-click {@code UseBlockCallback} registration and {@code
 * RecoredCommand}'s {@code sign set}/{@code sign remove} subcommands). The
 * text on the sign itself is whatever the admin writes with vanilla sign
 * editing - this only attaches a command to a position, nothing about the
 * sign's actual text. Persisted alongside maps/lobby, see {@link
 * MapPersistence}.
 */
public final class SignCommands {

	public static final SignCommands INSTANCE = new SignCommands();

	/** A sign's position, qualified by dimension (signs are almost always overworld here, but this stays correct regardless). */
	public record Key(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private final Map<Key, String> commands = new LinkedHashMap<>();

	private SignCommands() {
	}

	public void set(Level level, BlockPos pos, String command) {
		set(new Key(level.dimension(), pos.immutable()), command);
	}

	/** Raw-key variant used by {@link MapPersistence#load} (no live {@link Level} handy while deserializing). */
	public void set(Key key, String command) {
		commands.put(key, command);
	}

	/** @return {@code true} if a command was actually attached there to remove. */
	public boolean remove(Level level, BlockPos pos) {
		return commands.remove(new Key(level.dimension(), pos.immutable())) != null;
	}

	/** @return the command attached at {@code pos}, or {@code null} if none. */
	public String get(Level level, BlockPos pos) {
		return commands.get(new Key(level.dimension(), pos.immutable()));
	}

	public Map<Key, String> all() {
		return commands;
	}

	public void clear() {
		commands.clear();
	}
}
