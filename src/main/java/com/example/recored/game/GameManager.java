package com.example.recored.game;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Team.OptionStatus;

/**
 * Singleton holding every piece of mutable *round* state. Not thread-safe by design - all access
 * happens on the server thread (commands, block/entity events, and the end-of-tick handler all
 * run there). A straight Bukkit/Paper port of the fabric-example-mod-26.2 "Recored" gamemode's
 * {@code GameManager}: same design, same rules, just built on plain Paper events/scheduler instead
 * of Fabric callbacks and Mixins - see {@code RecoredListener} for exactly which vanilla event
 * replaces which mixin/callback.
 *
 * <p>Dropped relative to the original: the optional client-side "highlight your own/enemy cores"
 * networking sync (it needed a companion client mod - there's no equivalent on a vanilla-client
 * Paper server) and the matching client-side prediction cosmetic. Everything else - persistent
 * shared core mining, spawn protection, the ready-up auto-start, the per-team sidebar, the
 * respawn-delay - is fully intact, just server-authoritative from the start (which it always was
 * anyway).
 */
public final class GameManager {

	public static final GameManager INSTANCE = new GameManager();

	/** How long {@link Phase#STARTING} lasts before the round goes live. */
	public static final int START_DELAY_TICKS = 5 * 20;

	/** Name prefix for the vanilla scoreboard teams backing RED/BLUE colours. */
	private static final String SCOREBOARD_TEAM_PREFIX = "recored_";
	/** Name prefix for the per-team sidebar objectives. */
	private static final String HUD_OBJECTIVE_PREFIX = "recored_hud_";

	private static final int HUD_REFRESH_TICKS = 5;
	private static final int BLINK_HALF_PERIOD = 2;
	private static final int WARNING_SOUND_TICKS = 20;
	private static final double ENEMY_WARNING_RANGE = 10.0;
	private static final int MAX_HUD_LINES = 8;

	private GameManager() {
	}

	private Plugin plugin;

	/** Called once from {@code RecoredPlugin#onEnable}. */
	public void init(Plugin plugin) {
		this.plugin = plugin;
	}

	// --- state ---------------------------------------------------------------

	public Phase phase = Phase.WAITING;
	public final Map<UUID, Team> players = new HashMap<>();
	private final Set<UUID> readyPlayers = new HashSet<>();

	/** The lobby: where players wait/land whenever a round isn't RUNNING. Set with {@code /recored setlobby}. */
	public Location lobbySpawn;
	public Region lobbyRegion;

	/** This round's working state - copied fresh from the active {@link MapConfig} by {@link #beginStart}. */
	public final Map<Team, Location> spawns = new EnumMap<>(Team.class);
	public final Map<Team, Region> spawnRegions = new EnumMap<>(Team.class);
	public final Map<Team, Map<Side, Location>> cores = new EnumMap<>(Team.class);
	public final Map<Team, Kit> kits = new EnumMap<>(Team.class);

	/** Persistent per-core mining progress, {@code 0.0..1.0}, shared by whoever mines it. */
	public final Map<Location, Float> coreProgress = new HashMap<>();
	/** Which core (if any) each player is currently actively digging. */
	private final Map<UUID, Location> digging = new HashMap<>();

	private record DestroyedCore(Team team, Side side) {
	}

	/** Cores destroyed so far this round, remembered so their sidebar line keeps showing (struck through). */
	private final Map<Location, DestroyedCore> destroyedCores = new LinkedHashMap<>();

	private final Map<UUID, Integer> pendingRespawns = new HashMap<>();
	public int respawnDelayTicks = 20;
	/** How many seconds of continuous mining it takes to break a core. */
	public double coreMiningSeconds = 10.0;

	private final Map<Team, Objective> hudObjectives = new EnumMap<>(Team.class);
	private int hudTick = 0;
	private int roundElapsedTicks = 0;
	private int countdownTicksRemaining = -1;

	{
		for (Team t : Team.values()) {
			cores.put(t, new EnumMap<>(Side.class));
			kits.put(t, Kit.EMPTY);
		}
	}

	// --- queries ------------------------------------------------------------

	public Team teamOf(UUID player) {
		return players.get(player);
	}

	/** While RUNNING, the active map's team spawn regions are protected; otherwise the lobby region is. */
	public boolean isProtected(Location pos) {
		if (phase != Phase.RUNNING) {
			return lobbyRegion != null && lobbyRegion.contains(pos);
		}
		for (Region region : spawnRegions.values()) {
			if (region.contains(pos)) {
				return true;
			}
		}
		return false;
	}

	public Team coreOwnerAt(Location pos) {
		for (Team t : Team.values()) {
			if (cores.get(t).containsValue(pos)) {
				return t;
			}
		}
		return null;
	}

	public boolean isCore(Location pos) {
		return coreOwnerAt(pos) != null;
	}

	private Side sideOf(Team team, Location pos) {
		for (Map.Entry<Side, Location> entry : cores.get(team).entrySet()) {
			if (entry.getValue().equals(pos)) {
				return entry.getKey();
			}
		}
		return null;
	}

	public boolean readyToStart() {
		Map<Team, Integer> counts = new EnumMap<>(Team.class);
		for (Team t : Team.values()) {
			counts.put(t, 0);
		}
		for (Team t : players.values()) {
			counts.merge(t, 1, Integer::sum);
		}
		for (Team t : Team.values()) {
			if (counts.get(t) < 1) {
				return false;
			}
		}
		return MapRegistry.INSTANCE.hasReadyMap();
	}

	// --- transitions ------------------------------------------------------

	/**
	 * Picks the next map (round-robin), copies its blueprint into this round's working state, then
	 * immediately teleports + kits + survival-modes both teams and flips straight to
	 * {@link Phase#RUNNING} - the pre-round countdown already happened before this was called.
	 */
	public void beginStart() {
		MapConfig map = MapRegistry.INSTANCE.activateNext();
		if (map == null) {
			return;
		}

		spawns.clear();
		spawns.putAll(map.teamSpawns);
		spawnRegions.clear();
		spawnRegions.putAll(map.spawnRegions);
		kits.clear();
		kits.putAll(map.kits);
		for (Team t : Team.values()) {
			cores.get(t).clear();
			cores.get(t).putAll(map.cores.get(t));
		}
		destroyedCores.clear();
		coreProgress.clear();
		readyPlayers.clear();
		countdownTicksRemaining = -1;
		roundElapsedTicks = 0;
		phase = Phase.RUNNING;

		for (Player player : Bukkit.getOnlinePlayers()) {
			Team team = teamOf(player.getUniqueId());
			if (team == null) {
				continue;
			}
			player.setGameMode(GameMode.SURVIVAL);
			teleportToSpawn(player, team);
			giveKit(player, team);
		}
		refreshHud();
		broadcast(ChatColor.GREEN + "Recored: GO! Round started on " + map.id);
	}

	private void teleportTo(Player player, Location spawn) {
		if (spawn == null) {
			return;
		}
		player.teleport(spawn);
		player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
		player.setFallDistance(0F);
	}

	public void teleportToSpawn(Player player, Team team) {
		teleportTo(player, spawns.get(team));
	}

	public void sendToLobby(Player player) {
		teleportTo(player, lobbySpawn);
	}

	/**
	 * Death is real - starts a {@link #respawnDelayTicks} countdown, after which {@link
	 * #tickPendingRespawns} forces the actual respawn itself (no button click needed), which then
	 * gets redirected to the team spawn with a fresh kit.
	 */
	public void beginRespawnDelay(Player player) {
		pendingRespawns.put(player.getUniqueId(), respawnDelayTicks);
	}

	/** Called once vanilla's own respawn actually happens - sends the player straight on to their team spawn with a fresh kit. */
	public void finishRespawn(Player player, Team team) {
		teleportToSpawn(player, team);
		giveKit(player, team);
	}

	// --- ready-up / auto-start ----------------------------------------------

	/** Clears their inventory and gives the not-ready item. */
	public void giveReadyItem(Player player) {
		PlayerInventory inventory = player.getInventory();
		inventory.clear();
		inventory.setItem(ReadyItem.SLOT, ReadyItem.notReady(plugin));
	}

	/** Drops their ready state and re-checks the auto-start conditions - their leaving might restore them. */
	public void forgetReady(Player player) {
		readyPlayers.remove(player.getUniqueId());
		player.getInventory().clear();
		maybeStartCountdown();
	}

	/** Right-clicking the ready/not-ready item toggles it. */
	public void toggleReady(Player player) {
		UUID id = player.getUniqueId();
		if (teamOf(id) == null) {
			return;
		}
		PlayerInventory inventory = player.getInventory();
		if (readyPlayers.remove(id)) {
			inventory.setItem(ReadyItem.SLOT, ReadyItem.notReady(plugin));
			abortCountdown(player.getName() + " is no longer ready");
		} else {
			readyPlayers.add(id);
			inventory.setItem(ReadyItem.SLOT, ReadyItem.ready(plugin));
			maybeStartCountdown();
		}
	}

	/** Self-healing enforcement of the ready item, run periodically while a round isn't live. */
	private void enforceReadyItems() {
		for (UUID id : players.keySet()) {
			Player player = Bukkit.getPlayer(id);
			if (player == null) {
				continue;
			}
			PlayerInventory inventory = player.getInventory();
			boolean ready = readyPlayers.contains(id);
			for (int slot = 0; slot < inventory.getSize(); slot++) {
				if (slot == ReadyItem.SLOT) {
					continue;
				}
				if (ReadyItem.isReadyItem(plugin, inventory.getItem(slot))) {
					inventory.setItem(slot, null);
				}
			}
			ItemStack current = inventory.getItem(ReadyItem.SLOT);
			boolean correct = ReadyItem.isReadyItem(plugin, current)
					&& current.getType() == (ready ? Material.LIME_DYE : Material.GRAY_DYE);
			if (!correct) {
				inventory.setItem(ReadyItem.SLOT, ready ? ReadyItem.ready(plugin) : ReadyItem.notReady(plugin));
			}
		}
	}

	private int countTeam(Team team) {
		int n = 0;
		for (Team t : players.values()) {
			if (t == team) {
				n++;
			}
		}
		return n;
	}

	private boolean allReady() {
		for (UUID id : players.keySet()) {
			if (!readyPlayers.contains(id)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Checked every time a player readies up (or joins/leaves while others are already ready): if
	 * both teams are non-empty and the same size, a ready map is registered, and every rostered
	 * player is ready, kicks off the countdown. If teams are non-empty but uneven, says so instead
	 * of silently doing nothing.
	 */
	public void maybeStartCountdown() {
		if (phase != Phase.WAITING) {
			return;
		}
		int red = countTeam(Team.RED);
		int blue = countTeam(Team.BLUE);
		if (red == 0 && blue == 0) {
			return;
		}
		if (red != blue) {
			broadcast(ChatColor.RED + "Teams must be the same size to start (currently RED " + red + " / BLUE " + blue + ")");
			return;
		}
		if (!allReady()) {
			return;
		}
		if (!MapRegistry.INSTANCE.hasReadyMap()) {
			broadcast(ChatColor.RED + "Everyone's ready, but no map is fully configured yet - an admin needs to finish one (/recored map list).");
			return;
		}
		phase = Phase.STARTING;
		countdownTicksRemaining = START_DELAY_TICKS;
		broadcast(ChatColor.GREEN + "Recored: all ready - starting in " + (START_DELAY_TICKS / 20) + "s!");
	}

	private void abortCountdown(String reason) {
		if (phase != Phase.STARTING) {
			return;
		}
		phase = Phase.WAITING;
		countdownTicksRemaining = -1;
		broadcast(ChatColor.RED + "Countdown cancelled - " + reason);
	}

	/** Re-checks every tick that conditions still hold, aborting immediately if not. Nobody is teleported until it completes. */
	private void tickCountdown() {
		if (countdownTicksRemaining < 0) {
			return;
		}
		int red = countTeam(Team.RED);
		int blue = countTeam(Team.BLUE);
		if (red == 0 || blue == 0 || red != blue || !allReady()) {
			abortCountdown("the ready/team conditions are no longer met");
			return;
		}
		if (countdownTicksRemaining == 0) {
			countdownTicksRemaining = -1;
			beginStart();
			return;
		}
		if (countdownTicksRemaining % 20 == 0) {
			int secondsLeft = countdownTicksRemaining / 20;
			broadcast(ChatColor.YELLOW + Integer.toString(secondsLeft));
			for (Player player : Bukkit.getOnlinePlayers()) {
				player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0F, 1.0F);
			}
		}
		countdownTicksRemaining--;
	}

	/**
	 * Ready-up state only matters pre-round, so a disconnect while WAITING/STARTING drops the
	 * player from the roster entirely; a disconnect mid-RUNNING keeps their team membership
	 * untouched, so reconnecting mid-round still sends them back to their spawn.
	 */
	public void handleDisconnect(Player player) {
		digging.remove(player.getUniqueId());
		if (phase == Phase.RUNNING) {
			readyPlayers.remove(player.getUniqueId());
			return;
		}
		UUID id = player.getUniqueId();
		readyPlayers.remove(id);
		if (players.remove(id) != null) {
			leaveScoreboardTeam(player);
			maybeStartCountdown();
		}
	}

	/** Unconditionally resets the player's inventory to exactly the saved kit - cleared first, every slot, every time. */
	public void giveKit(Player player, Team team) {
		Kit kit = kits.getOrDefault(team, Kit.EMPTY);
		PlayerInventory inventory = player.getInventory();
		inventory.clear();
		inventory.setArmorContents(null);
		inventory.setItemInOffHand(null);
		for (Map.Entry<Integer, ItemStack> entry : kit.slots().entrySet()) {
			int slot = entry.getKey();
			ItemStack stack = entry.getValue().clone();
			if (slot >= 36 && slot <= 39) {
				// 39=helmet, 38=chest, 37=legs, 36=boots (Bukkit's fixed armor slot numbering)
				inventory.setItem(slot, stack);
			} else if (slot == 40) {
				inventory.setItemInOffHand(stack);
			} else {
				inventory.setItem(slot, stack);
			}
		}
		if (kit.selectedSlot() >= 0 && kit.selectedSlot() < 9) {
			inventory.setHeldItemSlot(kit.selectedSlot());
		}
		player.updateInventory();
	}

	/** Called from the block-break handler when a team loses its last core. */
	public void endGame(Team winner) {
		phase = Phase.ENDED;
		countdownTicksRemaining = -1;
		cleanupRound();
		playVictoryCelebration();
		broadcast(winner.name() + " team wins - all enemy cores destroyed!");
		broadcast(ChatColor.GRAY + "The map has been reset - /recored join to play again.");
		phase = Phase.WAITING;
	}

	private void playVictoryCelebration() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0F, 1.0F);
		}
		if (lobbySpawn == null || lobbySpawn.getWorld() == null) {
			return;
		}
		Location center = lobbySpawn.clone().add(0, 1, 0);
		lobbySpawn.getWorld().spawnParticle(Particle.FIREWORK, center, 150, 2.0, 1.5, 2.0, 0.3);
		lobbySpawn.getWorld().playSound(lobbySpawn, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.MASTER, 2.0F, 1.0F);
		lobbySpawn.getWorld().playSound(lobbySpawn, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundCategory.MASTER, 2.0F, 1.0F);
	}

	/** Admin abort: identical cleanup to a natural win. */
	public void reset() {
		phase = Phase.ENDED;
		countdownTicksRemaining = -1;
		cleanupRound();
		phase = Phase.WAITING;
	}

	/**
	 * Shared end-of-round cleanup: every participant is switched to Adventure mode, has their
	 * inventory wiped, is sent back to the lobby, and fully removed from their team; the active
	 * map's saved baseline is restored over its bounding box, and this round's entire working
	 * state is wiped.
	 */
	private void cleanupRound() {
		for (UUID uuid : players.keySet()) {
			Player player = Bukkit.getPlayer(uuid);
			if (player != null) {
				player.setGameMode(GameMode.ADVENTURE);
				player.getInventory().clear();
				sendToLobby(player);
			}
		}
		clearScoreboardTeams();

		MapConfig activeMap = MapRegistry.INSTANCE.activeMap();
		if (activeMap != null) {
			activeMap.snapshot.restore();
		}
		MapRegistry.INSTANCE.clearActive();

		players.clear();
		spawns.clear();
		spawnRegions.clear();
		for (Team t : Team.values()) {
			cores.get(t).clear();
			kits.put(t, Kit.EMPTY);
		}
		destroyedCores.clear();
		coreProgress.clear();
		digging.clear();
		pendingRespawns.clear();
		readyPlayers.clear();
		roundElapsedTicks = 0;
		refreshHud();
	}

	/** Wired to a repeating scheduler task from {@code RecoredPlugin}. */
	public void tick() {
		tickPendingRespawns();
		if (phase == Phase.STARTING) {
			tickCountdown();
		}
		if (phase == Phase.RUNNING) {
			roundElapsedTicks++;
			tickCoreMining();
		}
		hudTick++;
		if (hudTick % HUD_REFRESH_TICKS == 0) {
			refreshHud();
			if (phase == Phase.WAITING || phase == Phase.STARTING) {
				enforceReadyItems();
			}
		}
	}

	private void tickPendingRespawns() {
		if (pendingRespawns.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, Integer>> it = pendingRespawns.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Integer> entry = it.next();
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null) {
				it.remove();
				continue;
			}
			int ticksLeft = entry.getValue();
			if (ticksLeft <= 0) {
				it.remove();
				if (!player.isOnline() || player.getHealth() > 0.0) {
					continue; // already respawned some other way
				}
				player.spigot().respawn();
				continue;
			}
			entry.setValue(ticksLeft - 1);
		}
	}

	// --- persistent core mining --------------------------------------------

	/**
	 * Called when a player starts damaging a core (a real one to mine - ownership is already
	 * checked by the caller). Unlike the original fabric mod, there's no need to also fake a
	 * mining-speed attribute here: that trick existed purely to keep a vanilla client's own local
	 * break prediction in sync with the server, and this port never lets that prediction run for a
	 * core in the first place (the listener cancels the vanilla break attempt outright and drives
	 * everything - including the crack overlay, via {@link Player#sendBlockDamage} - itself).
	 */
	public void startDigging(Player player, Location pos) {
		digging.put(player.getUniqueId(), pos);
	}

	/** Called on release/abort. */
	public void stopDigging(Player player, Location pos) {
		digging.remove(player.getUniqueId());
	}

	/** Drop bookkeeping for a player who disconnected mid-dig. */
	public void clearDigging(UUID playerId) {
		digging.remove(playerId);
	}

	private void tickCoreMining() {
		if (digging.isEmpty()) {
			return;
		}
		for (Map.Entry<UUID, Location> entry : new ArrayList<>(digging.entrySet())) {
			UUID playerId = entry.getKey();
			Location pos = entry.getValue();
			if (!pos.equals(digging.get(playerId))) {
				continue;
			}
			Player player = Bukkit.getPlayer(playerId);
			if (player == null || coreOwnerAt(pos) == null) {
				digging.remove(playerId);
				continue;
			}
			applyMiningTick(player, pos);
		}
	}

	/** One tick's worth of mining progress on {@code pos} from {@code player}, finishing the core if it crosses 100%. */
	private void applyMiningTick(Player player, Location pos) {
		Team owner = coreOwnerAt(pos);
		if (owner == null) {
			return;
		}
		float perTick = (float) (1.0 / 20.0 / coreMiningSeconds);
		float total = Math.min(1.0F, coreProgress.getOrDefault(pos, 0.0F) + perTick);
		if (total >= 1.0F) {
			breakCore(pos, player);
			return;
		}
		coreProgress.put(pos, total);
		int percent = Math.round((1.0F - total) * 100.0F);
		Side side = sideOf(owner, pos);
		String label = owner.name() + (side != null ? " " + side.label() : "") + " Core";
		player.sendActionBar(ChatColor.WHITE + "Mining " + owner.colour() + label + ChatColor.WHITE + ": " + percent + "%");
		for (Player nearby : pos.getWorld().getPlayers()) {
			if (nearby.getLocation().distanceSquared(pos) <= 32 * 32) {
				nearby.sendBlockDamage(pos, total);
			}
		}
	}

	/** Actually remove a core: no item drop, break sound/particles only, win-check, and clear all mining bookkeeping for that position. */
	public void breakCore(Location pos, Player breaker) {
		Team owner = coreOwnerAt(pos);
		if (owner == null) {
			return;
		}
		Side side = sideOf(owner, pos);
		cores.get(owner).remove(side);
		destroyedCores.put(pos, new DestroyedCore(owner, side));
		clearCoreProgress(pos);
		pos.getBlock().setType(Material.AIR);
		playDestructionEffect(pos);
		respawnDelayTicks = Math.max(respawnDelayTicks, 60);

		if (cores.get(owner).isEmpty()) {
			endGame(owner.opposite());
		} else {
			refreshHud();
		}
	}

	private void clearCoreProgress(Location pos) {
		coreProgress.remove(pos);
		digging.values().removeIf(pos::equals);
	}

	private void playDestructionEffect(Location pos) {
		Location center = pos.clone().add(0.5, 0.5, 0.5);
		pos.getWorld().spawnParticle(Particle.FIREWORK, center, 80, 0.6, 0.6, 0.6, 0.2);
		pos.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, center, 1, 0.0, 0.0, 0.0, 0.0);
		pos.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 4.0F, 1.0F);
	}

	// --- HUD: per-team sidebar + enemy-nearby warning -----------------------

	private Scoreboard board() {
		return Bukkit.getScoreboardManager().getMainScoreboard();
	}

	/** Registers the two per-team sidebar objectives. Call once on plugin enable. */
	public void setupHud() {
		Scoreboard scoreboard = board();
		for (Team team : Team.values()) {
			String name = HUD_OBJECTIVE_PREFIX + team.lowerName();
			Objective objective = scoreboard.getObjective(name);
			if (objective == null) {
				objective = scoreboard.registerNewObjective(name, Criteria.DUMMY,
						net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
								.deserialize(team.colour() + "" + ChatColor.BOLD + team.name() + " TEAM"));
			}
			hudObjectives.put(team, objective);
			ensureScoreboardTeam(team);
		}
		clearScoreboardTeams();
	}

	/**
	 * Refreshes both sidebars. Each team's objective (only ever visible to that team's own
	 * viewers, via {@code DisplaySlot.SIDEBAR_TEAM_*}) lists both teams' cores - the viewer's own
	 * team's first, the enemy's below - so health% is effectively global, while the
	 * enemy-nearby blink + proximity warning stay restricted to a team's own cores.
	 */
	private void refreshHud() {
		Scoreboard scoreboard = board();
		if (phase != Phase.RUNNING) {
			for (Team team : Team.values()) {
				scoreboard.clearSlot(team.sidebarSlot());
				List<String> previous = lastHudEntries.remove(team);
				if (previous != null) {
					for (String entry : previous) {
						scoreboard.resetScores(entry);
					}
				}
			}
			return;
		}
		for (Team team : Team.values()) {
			Objective objective = hudObjectives.get(team);
			if (objective != null) {
				objective.setDisplaySlot(team.sidebarSlot());
			}
		}

		boolean blinkOn = (hudTick / (HUD_REFRESH_TICKS * BLINK_HALF_PERIOD)) % 2 == 0;
		boolean playWarningSound = hudTick % WARNING_SOUND_TICKS < HUD_REFRESH_TICKS;

		Map<Location, Boolean> enemyNear = new HashMap<>();
		for (Team owner : Team.values()) {
			for (Location pos : cores.get(owner).values()) {
				boolean near = hasEnemyNearby(owner, pos);
				enemyNear.put(pos, near);
				if (near && playWarningSound) {
					pos.getWorld().playSound(pos.clone().add(0.5, 0.5, 0.5), Sound.BLOCK_NOTE_BLOCK_HARP, SoundCategory.BLOCKS, 3.0F, 1.4F);
				}
			}
		}

		record CoreRow(Location pos, Side side, boolean destroyed) {
		}
		Map<Team, List<CoreRow>> rowsByTeam = new EnumMap<>(Team.class);
		for (Team team : Team.values()) {
			List<CoreRow> rows = new ArrayList<>();
			for (Side side : Side.values()) {
				Location pos = cores.get(team).get(side);
				if (pos != null && rows.size() < MAX_HUD_LINES) {
					rows.add(new CoreRow(pos, side, false));
				}
			}
			for (Map.Entry<Location, DestroyedCore> entry : destroyedCores.entrySet()) {
				if (entry.getValue().team() != team || rows.size() >= MAX_HUD_LINES) {
					continue;
				}
				rows.add(new CoreRow(entry.getKey(), entry.getValue().side(), true));
			}
			rowsByTeam.put(team, rows);
		}

		for (Team viewerTeam : Team.values()) {
			Objective objective = hudObjectives.get(viewerTeam);
			if (objective == null) {
				continue;
			}

			// Clear exactly what this team's objective showed last refresh - entries live on the
			// whole (shared) Scoreboard, not scoped per-objective, so this must be precise rather
			// than a blanket sweep, or it would also wipe the other team's current lines.
			List<String> previous = lastHudEntries.get(viewerTeam);
			if (previous != null) {
				for (String entry : previous) {
					scoreboard.resetScores(entry);
				}
			}

			Team enemyTeam = viewerTeam.opposite();
			List<CoreRow> ownRows = rowsByTeam.get(viewerTeam);
			List<CoreRow> enemyRows = rowsByTeam.get(enemyTeam);
			int totalLines = ownRows.size() + enemyRows.size();

			List<String> current = new ArrayList<>();
			String timer = timerLine();
			objective.getScore(timer).setScore(totalLines + 1);
			current.add(timer);

			int line = totalLines;
			for (CoreRow row : ownRows) {
				String entry = coreLine(viewerTeam, row.side(), row.pos(), row.destroyed(), enemyNear.getOrDefault(row.pos(), false), blinkOn, line);
				objective.getScore(entry).setScore(line);
				current.add(entry);
				line--;
			}
			for (CoreRow row : enemyRows) {
				String entry = coreLine(enemyTeam, row.side(), row.pos(), row.destroyed(), false, false, line);
				objective.getScore(entry).setScore(line);
				current.add(entry);
				line--;
			}
			lastHudEntries.put(viewerTeam, current);
		}
	}

	private final Map<Team, List<String>> lastHudEntries = new EnumMap<>(Team.class);

	/** "Time: M:SS" since the round went RUNNING - always the topmost line. */
	private String timerLine() {
		int totalSeconds = roundElapsedTicks / 20;
		int minutes = totalSeconds / 60;
		int seconds = totalSeconds % 60;
		return ChatColor.GRAY + "" + ChatColor.BOLD + String.format("Time: %d:%02d", minutes, seconds) + uniqueSuffix(0);
	}

	/** One sidebar line: "RED Left Core: 87%", or "RED Left Core: DESTROYED" once it's gone. */
	private String coreLine(Team owner, Side side, Location pos, boolean destroyed, boolean enemyNearby, boolean blinkOn, int uniqueSalt) {
		String label = owner.name() + " " + side.label() + " Core";
		String text;
		if (destroyed) {
			text = owner.colour() + "■ " + ChatColor.RED + "" + ChatColor.STRIKETHROUGH + label + ": DESTROYED";
		} else {
			int healthPercent = Math.max(0, Math.round((1.0F - coreProgress.getOrDefault(pos, 0.0F)) * 100.0F));
			ChatColor healthColour = healthPercent <= 20 ? ChatColor.RED : healthPercent <= 50 ? ChatColor.YELLOW : ChatColor.GREEN;
			ChatColor colour = enemyNearby && blinkOn ? ChatColor.WHITE : healthColour;
			String bold = enemyNearby ? "" + ChatColor.BOLD : "";
			text = owner.colour() + "■ " + bold + colour + label + ": " + healthPercent + "%";
		}
		return clamp(text, 34) + uniqueSuffix(uniqueSalt);
	}

	private String uniqueSuffix(int salt) {
		ChatColor[] colours = ChatColor.values();
		return "" + ChatColor.RESET + colours[Math.abs(salt) % colours.length];
	}

	private String clamp(String s, int max) {
		return s.length() > max ? s.substring(0, max) : s;
	}

	private boolean hasEnemyNearby(Team owner, Location pos) {
		double rangeSq = ENEMY_WARNING_RANGE * ENEMY_WARNING_RANGE;
		for (Player player : Bukkit.getOnlinePlayers()) {
			Team playerTeam = teamOf(player.getUniqueId());
			if (playerTeam == null || playerTeam == owner || !player.getWorld().equals(pos.getWorld())) {
				continue;
			}
			if (player.getLocation().distanceSquared(pos) <= rangeSq) {
				return true;
			}
		}
		return false;
	}

	// --- scoreboard team colouring (tab list + nametag) ---------------------

	private org.bukkit.scoreboard.Team ensureScoreboardTeam(Team team) {
		Scoreboard scoreboard = board();
		String name = SCOREBOARD_TEAM_PREFIX + team.lowerName();
		org.bukkit.scoreboard.Team scoreboardTeam = scoreboard.getTeam(name);
		if (scoreboardTeam == null) {
			scoreboardTeam = scoreboard.registerNewTeam(name);
			scoreboardTeam.setColor(team.colour());
			scoreboardTeam.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY, OptionStatus.ALWAYS);
		}
		return scoreboardTeam;
	}

	public void joinScoreboardTeam(Player player, Team team) {
		ensureScoreboardTeam(team).addEntry(player.getName());
	}

	public void leaveScoreboardTeam(Player player) {
		for (Team t : Team.values()) {
			ensureScoreboardTeam(t).removeEntry(player.getName());
		}
	}

	private void clearScoreboardTeams() {
		for (Team team : Team.values()) {
			org.bukkit.scoreboard.Team scoreboardTeam = ensureScoreboardTeam(team);
			for (String member : new ArrayList<>(scoreboardTeam.getEntries())) {
				scoreboardTeam.removeEntry(member);
			}
		}
	}

	private void broadcast(String message) {
		Bukkit.broadcastMessage(ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "Recored" + ChatColor.DARK_GRAY + "] " + message);
	}
}
