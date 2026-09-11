package com.example.cores.game;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.cores.CoresMod;
import com.example.cores.net.CoreSyncPayload;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Singleton holding every piece of mutable round state. Not thread-safe by
 * design - all access happens on the server thread (commands, block-break
 * events and the end-of-tick handler all run there).
 */
public final class GameManager {

	public static final GameManager INSTANCE = new GameManager();

	/** How long {@link Phase#STARTING} lasts before the round goes live. */
	public static final int START_DELAY_TICKS = 5 * 20;

	private GameManager() {
	}

	public record Spawn(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {
		public BlockPos blockPos() {
			return BlockPos.containing(x, y, z);
		}
	}

	// --- state ---------------------------------------------------------------

	public Phase phase = Phase.WAITING;
	public final Map<UUID, Team> players = new HashMap<>();
	public final Map<Team, Spawn> spawns = new EnumMap<>(Team.class);
	public final Map<Team, SpawnRegion> spawnRegions = new EnumMap<>(Team.class);
	/** Mutable - core positions are removed as they are broken. */
	public final Map<Team, List<BlockPos>> cores = new EnumMap<>(Team.class);
	public final Map<Team, List<ItemStack>> kits = new EnumMap<>(Team.class);

	/**
	 * Flattened view of every core position, regardless of team. This is the set
	 * {@code BeaconHardnessMixin} consults, and the only game state the client is
	 * told about (via {@link CoreSyncPayload}). Rebuilt from {@link #cores} on the
	 * server by {@link #syncCores}; replaced wholesale on the client by
	 * {@link #applyClientSync}.
	 */
	public final Set<BlockPos> hardenedCores = new HashSet<>();

	/** Ticks left in the STARTING countdown; {@code -1} when not counting down. */
	private int startCountdown = -1;

	{
		for (Team t : Team.values()) {
			cores.put(t, new ArrayList<>());
			kits.put(t, new ArrayList<>());
		}
	}

	// --- queries ------------------------------------------------------------

	public Team teamOf(UUID player) {
		return players.get(player);
	}

	public boolean isProtected(BlockPos pos) {
		for (SpawnRegion region : spawnRegions.values()) {
			if (region.contains(pos)) {
				return true;
			}
		}
		return false;
	}

	/** @return the team that owns a core at {@code pos}, or {@code null}. */
	public Team coreOwnerAt(BlockPos pos) {
		for (Team t : Team.values()) {
			if (cores.get(t).contains(pos)) {
				return t;
			}
		}
		return null;
	}

	public boolean isCore(BlockPos pos) {
		return coreOwnerAt(pos) != null;
	}

	/** Consulted by {@code BeaconHardnessMixin} on both sides. */
	public boolean isHardenedCore(BlockPos pos) {
		return hardenedCores.contains(pos);
	}

	public boolean readyToStart() {
		if (spawns.size() < Team.values().length) {
			return false;
		}
		Map<Team, Integer> counts = new EnumMap<>(Team.class);
		for (Team t : Team.values()) {
			counts.put(t, 0);
		}
		for (Team t : players.values()) {
			counts.merge(t, 1, Integer::sum);
		}
		for (Team t : Team.values()) {
			if (counts.get(t) < 1 || cores.get(t).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	// --- transitions ------------------------------------------------------

	/**
	 * Teleport + kit both teams immediately, then flip to {@link Phase#RUNNING}
	 * after {@link #START_DELAY_TICKS} via the server-tick handler.
	 */
	public void beginStart(MinecraftServer server) {
		phase = Phase.STARTING;
		startCountdown = START_DELAY_TICKS;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Team team = teamOf(player.getUUID());
			if (team == null) {
				continue;
			}
			Spawn spawn = spawns.get(team);
			ServerLevel level = server.getLevel(spawn.dimension());
			if (level != null) {
				player.teleportTo(level, spawn.x(), spawn.y(), spawn.z(), Set.<Relative>of(), spawn.yaw(), spawn.pitch(), false);
			}
			giveKit(player, team);
		}
		syncCores(server);
		broadcast(server, Component.literal("Cores: round starting in " + (START_DELAY_TICKS / 20) + "s"));
	}

	private void giveKit(ServerPlayer player, Team team) {
		List<ItemStack> kit = kits.get(team);
		if (kit.isEmpty()) {
			return;
		}
		player.getInventory().clearContent();
		for (ItemStack stack : kit) {
			player.getInventory().add(stack.copy());
		}
		player.containerMenu.broadcastChanges();
	}

	/** Called from the block-break handler when a team loses its last core. */
	public void endGame(MinecraftServer server, Team winner) {
		phase = Phase.ENDED;
		startCountdown = -1;
		syncCores(server);
		broadcast(server, Component.literal(winner.name() + " team wins - all enemy cores destroyed!")
			.withStyle(winner.colour()));
		broadcast(server, Component.literal("Run /cores reset to play again.").withStyle(ChatFormatting.GRAY));
	}

	public void reset(MinecraftServer server) {
		phase = Phase.WAITING;
		startCountdown = -1;
		players.clear();
		spawns.clear();
		spawnRegions.clear();
		for (Team t : Team.values()) {
			cores.get(t).clear();
			kits.get(t).clear();
		}
		syncCores(server);
	}

	/**
	 * Rebuild {@link #hardenedCores} from {@link #cores} and push a fresh
	 * {@link CoreSyncPayload} to every connected client. Call after any change to
	 * {@link #cores} or {@link #phase}.
	 */
	public void syncCores(MinecraftServer server) {
		hardenedCores.clear();
		for (Team t : Team.values()) {
			hardenedCores.addAll(cores.get(t));
		}
		if (server == null) {
			return;
		}
		CoreSyncPayload payload = CoreSyncPayload.snapshot();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, CoreSyncPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	/** Client-side: overwrite local state from a server snapshot. */
	public void applyClientSync(Phase newPhase, List<BlockPos> corePositions) {
		phase = newPhase;
		hardenedCores.clear();
		hardenedCores.addAll(corePositions);
	}

	/** Wired to {@code ServerTickEvents.END_SERVER_TICK}. */
	public void tick(MinecraftServer server) {
		if (phase != Phase.STARTING || startCountdown < 0) {
			return;
		}
		if (startCountdown == 0) {
			phase = Phase.RUNNING;
			startCountdown = -1;
			syncCores(server);
			broadcast(server, Component.literal("Cores: GO!").withStyle(ChatFormatting.GREEN));
			return;
		}
		if (startCountdown % 20 == 0) {
			broadcast(server, Component.literal(String.valueOf(startCountdown / 20)).withStyle(ChatFormatting.YELLOW));
		}
		startCountdown--;
	}

	private void broadcast(MinecraftServer server, Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
		CoresMod.LOGGER.info("[Cores] {}", message.getString());
	}
}
