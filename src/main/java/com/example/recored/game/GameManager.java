package com.example.recored.game;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.example.recored.RecoredMod;
import com.example.recored.net.CoreSyncPayload;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jspecify.annotations.Nullable;

/**
 * Singleton holding every piece of mutable *round* state. Not thread-safe by
 * design - all access happens on the server thread (commands, block-break
 * events, the digging mixin and the end-of-tick handler all run there).
 *
 * <p>{@link #spawns}, {@link #cores}, {@link #spawnRegions} and {@link #kits}
 * are the *live working copy* for whichever round is in progress - populated
 * fresh from the active {@link MapConfig} by {@link #beginStart}, and mutated
 * during play (cores get removed as they're broken). The {@link MapConfig}
 * itself is never touched, so the same map can be played again unchanged.
 */
public final class GameManager {

	public static final GameManager INSTANCE = new GameManager();

	/** How long {@link Phase#STARTING} lasts before the round goes live. */
	public static final int START_DELAY_TICKS = 5 * 20;

	/** Name prefix for the vanilla scoreboard teams backing RED/BLUE colours. */
	private static final String SCOREBOARD_TEAM_PREFIX = "recored_";

	/** Name prefix for the per-team sidebar objectives - see {@link #setupHud}. */
	private static final String HUD_OBJECTIVE_PREFIX = "recored_hud_";

	/** How often (in ticks) the sidebar/enemy-proximity effects refresh. */
	private static final int HUD_REFRESH_TICKS = 5;
	/** Half-cycle length of the "enemy nearby" blink, in HUD refreshes. */
	private static final int BLINK_HALF_PERIOD = 2;
	/** How often (in ticks) the warning note repeats while an enemy is in range. */
	private static final int WARNING_SOUND_TICKS = 20;
	/** How close an enemy has to be to a core to trigger the blink/warning sound. */
	private static final double ENEMY_WARNING_RANGE = 10.0;
	/** Highest core index shown per section (own/enemy) of a sidebar - plenty for any reasonable map. */
	private static final int MAX_HUD_LINES = 8;
	/** Score-holder name suffix for the elapsed-time line - see {@link #timerLine}. Deliberately not "_line_"-prefixed so the numeric clearing loop never touches it. */
	private static final String TIMER_LINE_NAME = "_timer";

	/** Hotbar slot the ready-up item lives in - see {@link #enforceReadyItems}. */
	private static final int READY_ITEM_SLOT = 0;
	/** {@code CustomData} tag key marking an item stack as our ready/not-ready indicator - see {@link #isReadyMarkerStack}. */
	private static final String READY_MARKER_KEY = "recored_ready_item";

	/**
	 * Beacon's real, unmodified vanilla hardness ({@code Blocks.BEACON}'s own
	 * {@code .strength(3.0F)}) - what a vanilla client (with no mod installed)
	 * actually perceives, since nothing overrides it anymore. See {@link
	 * #coreHardness} for how the *effective* mining time is actually tripled
	 * without touching this.
	 */
	private static final float VANILLA_BEACON_HARDNESS = 3.0F;

	/** Id of the transient {@code Attributes.BLOCK_BREAK_SPEED} modifier applied while actively mining a core - see {@link #startDigging}. */
	private static final Identifier MINING_SLOWDOWN_ID = Identifier.fromNamespaceAndPath(RecoredMod.MOD_ID, "core_mining_slowdown");

	/** Generous reach used only to decide whether to *pre-arm* the mining slowdown - see {@link #tickMiningSlowdownPriming}. */
	private static final double MINING_SLOWDOWN_PRIME_REACH = 8.0;

	/**
	 * A completed core doesn't need to reach a mathematically exact
	 * {@code 1.0} - see {@link #applyMiningTick}. A small tolerance absorbs
	 * whatever tiny gap is left between a vanilla client's own local
	 * prediction and this server's real progress, without giving up any
	 * meaningful amount of the intended mining duration.
	 */
	private static final float MINING_COMPLETE_THRESHOLD = 0.98F;

	private GameManager() {
	}

	// --- state ---------------------------------------------------------------

	public Phase phase = Phase.WAITING;
	public final Map<UUID, Team> players = new HashMap<>();

	/** The lobby: where players wait/land whenever a round isn't RUNNING. Set with {@code /recored setlobby}. */
	public Spawn lobbySpawn;
	public SpawnRegion lobbyRegion;

	/** This round's working state - see the class doc. */
	public final Map<Team, Spawn> spawns = new EnumMap<>(Team.class);
	public final Map<Team, SpawnRegion> spawnRegions = new EnumMap<>(Team.class);
	/**
	 * Mutable - each team's own left/right core slots (see {@link
	 * MapConfig#cores}), copied fresh from the active map by {@link
	 * #beginStart}; a slot's entry is removed the instant that core breaks.
	 */
	public final Map<Team, Map<Side, BlockPos>> cores = new EnumMap<>(Team.class);
	public final Map<Team, Kit> kits = new EnumMap<>(Team.class);

	/**
	 * Flattened view of every core position, regardless of team. Consulted by
	 * {@code CoreProtectionMixin} to decide whether a block is a core at all,
	 * and the only game state a client is told about (via {@link
	 * CoreSyncPayload}) - purely optional, since clients don't need this mod
	 * installed; a modded client that does opt in gets the client-side "can't
	 * even start mining your own core" cosmetic on top. Rebuilt from {@link
	 * #cores} on the server by {@link #syncCores}; replaced wholesale on the
	 * client by {@link #applyClientSync}.
	 */
	public final Set<BlockPos> hardenedCores = new HashSet<>();

	/**
	 * Client-side only: which of {@link #hardenedCores} belong to the local
	 * player's own team, per the last sync (see {@link #applyClientSync}). Used
	 * by {@code CoreProtectionMixin} so a player's own core behaves like an
	 * adventure-mode-restricted block for them specifically - not consulted
	 * server-side (there, ownership is checked per-player directly against
	 * {@link #cores}, since a server has many players with different teams at
	 * once, unlike a client's single {@code GameManager.INSTANCE}).
	 */
	public final Set<BlockPos> ownCores = new HashSet<>();

	/**
	 * Persistent per-core mining progress, {@code 0.0..1.0}. Unlike vanilla's
	 * per-player digging state, this lives on the core itself: it survives a
	 * player stopping, switching target, or logging off, and is shared by
	 * whoever mines it. Driven by {@code CoreMiningMixin} + {@link #tickCoreMining}.
	 */
	public final Map<BlockPos, Float> coreProgress = new HashMap<>();

	/** Which core (if any) each player is currently holding left-click on. */
	private final Map<UUID, BlockPos> digging = new HashMap<>();

	/**
	 * Cores destroyed so far this round, remembered with their original owner
	 * team + side so their sidebar line keeps showing - struck through and
	 * red, see {@link #coreLine} - instead of disappearing the way {@link
	 * #cores} (still the authoritative "still standing" set, used for
	 * mining/win checks) does when a core is removed from it. Insertion order
	 * = display order among a team's destroyed rows. Cleared each round end.
	 */
	private final Map<BlockPos, DestroyedCore> destroyedCores = new LinkedHashMap<>();

	/** @param team the core's original owner @param side which of that team's slots it was */
	private record DestroyedCore(Team team, Side side) {
	}

	/**
	 * Players who've marked themselves ready (holding the {@link #readyStack}
	 * item) since joining a team. Only meaningful pre-round ({@link
	 * Phase#WAITING}/{@link Phase#STARTING}) - cleared every round end; see
	 * {@link #toggleReady}, {@link #maybeStartCountdown}.
	 */
	private final Set<UUID> readyPlayers = new HashSet<>();

	/** The per-team sidebar objective - see {@link #setupHud}. */
	private final Map<Team, Objective> hudObjectives = new EnumMap<>(Team.class);

	/** Drives the HUD refresh throttle and the enemy-nearby blink phase. */
	private int hudTick = 0;

	/**
	 * Ticks since the current round went {@link Phase#RUNNING} - drives the
	 * sidebar's elapsed-time line (see {@link #timerLine}). Uncapped (counts
	 * up indefinitely, no round time limit); reset in {@link #beginStart} and
	 * {@link #cleanupRound}.
	 */
	private int roundElapsedTicks = 0;

	/**
	 * The mining duration a core should <em>behave</em> as if it had, expressed
	 * as an equivalent hardness (total time = {@code coreHardness * 30} ticks,
	 * same formula as vanilla; beacons aren't in the pickaxe-mineable tag, so
	 * tool choice never gives a speed bonus - {@code 9.0F} is 270 ticks/13.5s,
	 * triple vanilla's own {@code 3.0F} beacon default, with any tool alike).
	 *
	 * <p>This does <b>not</b> touch the beacon's actual registered hardness
	 * ({@link #VANILLA_BEACON_HARDNESS} stays what it really is, everywhere) -
	 * since clients don't have this mod installed, only the server could ever
	 * see an overridden hardness, and a vanilla client mining "faster" than
	 * the server thinks is exactly what used to cause the block to visibly
	 * break and pop back repeatedly. Instead, {@link #startDigging} applies a
	 * transient {@code Attributes.BLOCK_BREAK_SPEED} modifier - a real,
	 * synced-to-the-client vanilla mechanic - to slow the miner down by
	 * exactly {@code VANILLA_BEACON_HARDNESS / coreHardness} while they're
	 * actively digging a core. Both the vanilla client's own local prediction
	 * and this server's {@link #applyMiningTick} read the *same* real block
	 * hardness and the *same* synced attribute value, so they agree on the
	 * total time throughout - not just eventually.
	 */
	public float coreHardness = 9.0F;

	/**
	 * How long a respawned team member is held before being sent on to their
	 * team's spawn (see {@link #beginRespawnDelay}, {@link #tickPendingRespawns}).
	 * Real death/respawn happens instantly via vanilla (the
	 * {@code immediate_respawn} gamerule, set at server start) - this is just the
	 * extra beat before they land in the fight. Fixed at 20 ticks (1s) by
	 * default; change live with {@code /recored respawndelay}. Bumped to at least
	 * 60 ticks (3s) the first time any core falls - see {@link #breakCore}.
	 */
	public int respawnDelayTicks = 20;

	private final Map<UUID, Integer> pendingRespawns = new HashMap<>();

	/**
	 * Ticks left in the pre-round ready countdown ({@link Phase#STARTING});
	 * {@code -1} when not counting down. Players are NOT teleported yet during
	 * this countdown (unlike the old design) - see {@link #tickCountdown};
	 * the actual teleport/kit/go-live happens in {@link #beginStart} once it
	 * completes.
	 */
	private int startCountdown = -1;

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

	/**
	 * While RUNNING, the active map's team spawn regions are protected; at any
	 * other time (nobody should be on a map then anyway - see {@link
	 * #cleanupRound}) it's the lobby region instead.
	 */
	public boolean isProtected(BlockPos pos) {
		if (phase != Phase.RUNNING) {
			return lobbyRegion != null && lobbyRegion.contains(pos);
		}
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
			if (cores.get(t).containsValue(pos)) {
				return t;
			}
		}
		return null;
	}

	public boolean isCore(BlockPos pos) {
		return coreOwnerAt(pos) != null;
	}

	/** @return which of {@code team}'s slots {@code pos} occupies, or {@code null} if it isn't (one of) their core(s). */
	private Side sideOf(Team team, BlockPos pos) {
		for (Map.Entry<Side, BlockPos> entry : cores.get(team).entrySet()) {
			if (entry.getValue().equals(pos)) {
				return entry.getKey();
			}
		}
		return null;
	}

	/** Consulted by {@code CoreProtectionMixin} on both sides. */
	public boolean isHardenedCore(BlockPos pos) {
		return hardenedCores.contains(pos);
	}

	/** Client-side only - see {@link #ownCores}. */
	public boolean isOwnCore(BlockPos pos) {
		return ownCores.contains(pos);
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
	 * Picks the next map (round-robin, see {@link MapRegistry#activateNext()}),
	 * copies its blueprint into this round's working state, then immediately
	 * teleports + kits + survival-modes both teams and flips straight to
	 * {@link Phase#RUNNING} - the pre-round countdown already happened before
	 * this was called (see {@link #tickCountdown}), so there's no additional
	 * delay here. Called either automatically once the ready countdown
	 * completes, or directly by admin {@code /recored start} (an instant
	 * override that bypasses the ready system entirely - caller should check
	 * {@link #readyToStart()} first in that case).
	 */
	public void beginStart(MinecraftServer server) {
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
		readyPlayers.clear();
		startCountdown = -1;
		roundElapsedTicks = 0;
		phase = Phase.RUNNING;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Team team = teamOf(player.getUUID());
			if (team == null) {
				continue;
			}
			player.setGameMode(GameType.SURVIVAL);
			teleportToSpawn(player, team);
			giveKit(player, team);
		}
		syncCores(server);
		refreshHud(server);
		broadcast(server, Component.literal("Recored: GO! Round started on " + map.id).withStyle(ChatFormatting.GREEN));
	}

	private void teleportTo(ServerPlayer player, @Nullable Spawn spawn) {
		if (spawn == null) {
			return;
		}
		ServerLevel level = player.level().getServer().getLevel(spawn.dimension());
		if (level == null) {
			return;
		}
		player.teleportTo(level, spawn.x(), spawn.y(), spawn.z(), Set.<Relative>of(), spawn.yaw(), spawn.pitch(), false);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
	}

	/** Teleport a player to their team's (active map) spawn, clearing momentum so they don't slide/fall on arrival. */
	public void teleportToSpawn(ServerPlayer player, Team team) {
		teleportTo(player, spawns.get(team));
	}

	/** Teleport a player to the lobby spawn. */
	public void sendToLobby(ServerPlayer player) {
		teleportTo(player, lobbySpawn);
	}

	/**
	 * Death is real (nothing cancelled) - the player sees the normal death screen
	 * like vanilla. Call this from {@code ServerLivingEntityEvents.AFTER_DEATH}:
	 * it starts a {@link #respawnDelayTicks} countdown, after which {@link
	 * #tickPendingRespawns} forces the actual respawn itself (no button click
	 * needed) straight through to {@link #finishRespawn} - so the whole
	 * experience is "die, see the death screen for a beat, land at team spawn",
	 * with no vanilla-spawn stopover in between.
	 */
	public void beginRespawnDelay(ServerPlayer player) {
		pendingRespawns.put(player.getUUID(), respawnDelayTicks);
	}

	/**
	 * Once vanilla's own respawn actually happens (whether we forced it below, or
	 * some other path did), immediately - no further delay - send the player on
	 * to their team spawn with a fresh kit. Call from {@code
	 * ServerPlayerEvents.AFTER_RESPAWN} while RUNNING; outside a round just send
	 * them to the lobby instead (see {@link #sendToLobby}).
	 */
	public void finishRespawn(ServerPlayer player, Team team) {
		teleportToSpawn(player, team);
		// Always reissue the kit - the inventory is reset to exactly the saved
		// kit every respawn, no exceptions, regardless of what they were
		// carrying (given away, dropped, picked up mid-round, ...).
		giveKit(player, team);
	}

	// --- ready-up / auto-start ----------------------------------------------

	/**
	 * The ready/not-ready item the moment a player joins a team - see {@link
	 * #giveReadyItem}, {@link #toggleReady}.
	 */
	private static ItemStack notReadyStack() {
		ItemStack stack = new ItemStack(Items.CLAY_BALL);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Not ready").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		markAsReadyItem(stack);
		return stack;
	}

	/** What right-clicking {@link #notReadyStack} swaps it for. */
	private static ItemStack readyStack() {
		ItemStack stack = new ItemStack(Items.SLIME_BALL);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Ready").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
		markAsReadyItem(stack);
		return stack;
	}

	private static void markAsReadyItem(ItemStack stack) {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(READY_MARKER_KEY, true);
		CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
	}

	/**
	 * Whether {@code stack} is our ready/not-ready indicator specifically -
	 * checked via a {@code CustomData} marker tag (not just item type/name),
	 * so a legitimately-obtained plain clay ball/slime ball never gets treated
	 * as one. Consulted by the toggle handler, the drop-prevention mixin, and
	 * {@link #enforceReadyItems}.
	 */
	public boolean isReadyMarkerStack(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBooleanOr(READY_MARKER_KEY, false);
	}

	/** Put the not-ready item in a freshly-joined team member's hand slot. Clears their inventory first - see {@link RecoredCommand#join}. */
	public void giveReadyItem(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		inventory.clearContent();
		inventory.setItem(READY_ITEM_SLOT, notReadyStack());
		player.containerMenu.broadcastChanges();
	}

	/**
	 * Called from {@code /recored leave}: drops their ready state and clears
	 * the item along with the rest of their inventory, then re-checks the
	 * auto-start conditions (their leaving might be exactly what makes the
	 * remaining roster equal-sized/all-ready).
	 */
	public void forgetReady(MinecraftServer server, ServerPlayer player) {
		readyPlayers.remove(player.getUUID());
		player.getInventory().clearContent();
		player.containerMenu.broadcastChanges();
		maybeStartCountdown(server);
	}

	/**
	 * Right-clicking the ready/not-ready item toggles it - called from {@code
	 * RecoredMod}'s {@code UseItemCallback} registration once it's confirmed
	 * the held stack is {@link #isReadyMarkerStack}. Ready re-checks the
	 * auto-start conditions immediately; un-readying aborts an in-progress
	 * countdown immediately too (everyone has to *stay* ready through it).
	 */
	public void toggleReady(ServerPlayer player) {
		UUID id = player.getUUID();
		if (teamOf(id) == null) {
			return; // shouldn't happen (the item only exists once you're on a team), safety net
		}
		Inventory inventory = player.getInventory();
		MinecraftServer server = player.level().getServer();
		if (readyPlayers.remove(id)) {
			inventory.setItem(READY_ITEM_SLOT, notReadyStack());
			player.containerMenu.broadcastChanges();
			abortCountdown(server, player.getScoreboardName() + " is no longer ready");
		} else {
			readyPlayers.add(id);
			inventory.setItem(READY_ITEM_SLOT, readyStack());
			player.containerMenu.broadcastChanges();
			maybeStartCountdown(server);
		}
	}

	/**
	 * Self-healing enforcement of the ready item, run periodically (see
	 * {@link #tick}) for every rostered player while a round isn't live: puts
	 * the correct stack (matching their current ready state) back in {@link
	 * #READY_ITEM_SLOT} if it's missing/wrong, and strips any stray copies
	 * found elsewhere in their inventory. This - plus the drop-prevention
	 * mixin blocking the item at the source - is what makes it effectively
	 * un-droppable/un-movable: even if something slips past the mixin, it
	 * snaps back within a fraction of a second.
	 */
	private void enforceReadyItems(MinecraftServer server) {
		for (UUID id : players.keySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player == null) {
				continue;
			}
			Inventory inventory = player.getInventory();
			boolean ready = readyPlayers.contains(id);
			boolean changed = false;
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				if (slot == READY_ITEM_SLOT) {
					continue;
				}
				if (isReadyMarkerStack(inventory.getItem(slot))) {
					inventory.setItem(slot, ItemStack.EMPTY);
					changed = true;
				}
			}
			ItemStack current = inventory.getItem(READY_ITEM_SLOT);
			boolean correct = ready ? current.getItem() == Items.SLIME_BALL : current.getItem() == Items.CLAY_BALL;
			if (!correct || !isReadyMarkerStack(current)) {
				inventory.setItem(READY_ITEM_SLOT, ready ? readyStack() : notReadyStack());
				changed = true;
			}
			if (changed) {
				player.containerMenu.broadcastChanges();
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
	 * Checked every time a player readies up (or joins/leaves while others are
	 * already ready): if both teams are non-empty and the same size, a ready
	 * map is registered, and every rostered player is ready, kicks off the 5s
	 * countdown. If teams are non-empty but uneven, broadcasts that they need
	 * to match instead (rather than starting or silently doing nothing).
	 */
	private void maybeStartCountdown(MinecraftServer server) {
		if (server == null || phase != Phase.WAITING) {
			return;
		}
		int red = countTeam(Team.RED);
		int blue = countTeam(Team.BLUE);
		if (red == 0 && blue == 0) {
			return;
		}
		if (red != blue) {
			broadcast(server, Component.literal(
				"Teams must be the same size to start (currently RED " + red + " / BLUE " + blue + ")").withStyle(ChatFormatting.RED));
			return;
		}
		if (!allReady()) {
			return; // still waiting on someone - each player's own item already shows this, no need to announce it
		}
		if (!MapRegistry.INSTANCE.hasReadyMap()) {
			broadcast(server, Component.literal(
				"Everyone's ready, but no map is fully configured yet - an admin needs to finish one (/recored map list).")
				.withStyle(ChatFormatting.RED));
			return;
		}
		phase = Phase.STARTING;
		startCountdown = START_DELAY_TICKS;
		broadcast(server, Component.literal(
			"Recored: all ready - starting in " + (START_DELAY_TICKS / 20) + "s!").withStyle(ChatFormatting.GREEN));
	}

	/** Cancel an in-progress ready countdown back to {@link Phase#WAITING} - a no-op if one isn't running. */
	private void abortCountdown(@Nullable MinecraftServer server, String reason) {
		if (phase != Phase.STARTING) {
			return;
		}
		phase = Phase.WAITING;
		startCountdown = -1;
		if (server != null) {
			broadcast(server, Component.literal("Countdown cancelled - " + reason).withStyle(ChatFormatting.RED));
		}
	}

	/**
	 * Ticks the ready countdown (see {@link #maybeStartCountdown}). Re-checks
	 * every tick that conditions still hold - team sizes still equal and
	 * non-zero, everyone still ready - aborting immediately if not ("the game
	 * begins as long as all remain ready"). Nobody is teleported until it
	 * actually completes, unlike the old STARTING design.
	 */
	private void tickCountdown(MinecraftServer server) {
		if (startCountdown < 0) {
			return;
		}
		int red = countTeam(Team.RED);
		int blue = countTeam(Team.BLUE);
		if (red == 0 || blue == 0 || red != blue || !allReady()) {
			abortCountdown(server, "the ready/team conditions are no longer met");
			return;
		}
		if (startCountdown == 0) {
			startCountdown = -1;
			beginStart(server);
			return;
		}
		if (startCountdown % 20 == 0) {
			int secondsLeft = startCountdown / 20;
			broadcast(server, Component.literal(String.valueOf(secondsLeft)).withStyle(ChatFormatting.YELLOW));
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.NOTE_BLOCK_PLING, SoundSource.MASTER, 1.0F, 1.0F);
			}
		}
		startCountdown--;
	}

	/**
	 * Called from {@code ServerPlayConnectionEvents.DISCONNECT}. Ready-up
	 * state only matters pre-round, so a disconnect while WAITING/STARTING
	 * drops the player from the roster entirely (keeps team-size math and
	 * "all ready" honest, and aborts any running countdown they were part
	 * of); a disconnect mid-RUNNING keeps their team membership untouched, as
	 * before, so reconnecting mid-round still sends them back to their spawn.
	 */
	public void handleDisconnect(MinecraftServer server, ServerPlayer player) {
		clearDigging(player.getUUID());
		if (phase == Phase.RUNNING) {
			readyPlayers.remove(player.getUUID());
			return;
		}
		UUID id = player.getUUID();
		readyPlayers.remove(id);
		if (players.remove(id) != null) {
			leaveScoreboardTeam(server, player);
			maybeStartCountdown(server); // team composition changed - re-check (also aborts the countdown via tickCountdown next tick if it breaks conditions)
		}
	}

	/**
	 * Unconditionally resets the player's inventory to exactly the saved kit -
	 * cleared first, every slot, every time, even if the kit is empty. Never
	 * leaves anything from their prior inventory in place: with {@code
	 * keep_inventory} on, vanilla would otherwise carry their pre-death
	 * inventory straight into the respawned player (so a manually dropped/given-
	 * away item would just stay gone) - this always overwrites that.
	 */
	private void giveKit(ServerPlayer player, Team team) {
		Kit kit = kits.getOrDefault(team, Kit.EMPTY);
		Inventory inventory = player.getInventory();
		inventory.clearContent();
		for (Map.Entry<Integer, ItemStack> entry : kit.slots().entrySet()) {
			int slot = entry.getKey();
			ItemStack stack = entry.getValue().copy();
			EquipmentSlot equipmentSlot = Inventory.EQUIPMENT_SLOT_MAPPING.get(slot);
			if (equipmentSlot != null) {
				// Armor/offhand need to go through the entity setter so attribute
				// modifiers (armor toughness, attack speed, ...) get applied.
				player.setItemSlot(equipmentSlot, stack);
			} else {
				inventory.setItem(slot, stack);
			}
		}
		if (kit.selectedSlot() >= 0 && kit.selectedSlot() < 9) {
			inventory.setSelectedSlot(kit.selectedSlot());
		}
		player.containerMenu.broadcastChanges();
	}

	/** Called from the block-break handler when a team loses its last core. */
	public void endGame(MinecraftServer server, Team winner) {
		phase = Phase.ENDED;
		startCountdown = -1;
		cleanupRound(server); // everyone's already back in the lobby by the time the celebration below plays
		playVictoryCelebration(server);
		broadcast(server, Component.literal(winner.name() + " team wins - all enemy cores destroyed!")
			.withStyle(winner.colour()));
		broadcast(server, Component.literal("The map has been reset - /recored join to play again.").withStyle(ChatFormatting.GRAY));
		phase = Phase.WAITING;
	}

	/**
	 * A victory sound for every connected player (played centred on each of
	 * them individually, so it's heard clearly regardless of where they are -
	 * not just whoever's near the lobby), plus a firework particle burst +
	 * launch/blast sounds at the lobby spawn specifically (everyone's already
	 * standing there by the time this runs, via {@link #cleanupRound}).
	 */
	private void playVictoryCelebration(MinecraftServer server) {
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0F, 1.0F);
		}
		if (lobbySpawn == null) {
			return;
		}
		ServerLevel level = server.getLevel(lobbySpawn.dimension());
		if (level == null) {
			return;
		}
		double x = lobbySpawn.x();
		double y = lobbySpawn.y();
		double z = lobbySpawn.z();
		level.sendParticles(ParticleTypes.FIREWORK, x, y + 1.0, z, 150, 2.0, 1.5, 2.0, 0.3);
		level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.MASTER, 2.0F, 1.0F);
		level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.MASTER, 2.0F, 1.0F);
	}

	/** Admin abort: identical cleanup to a natural win (see {@link #cleanupRound}). */
	public void reset(MinecraftServer server) {
		phase = Phase.ENDED;
		startCountdown = -1;
		cleanupRound(server);
		phase = Phase.WAITING;
	}

	/**
	 * Shared end-of-round cleanup, run by both a natural win and a manual
	 * {@link #reset} - the two are otherwise identical: every participant is
	 * switched to Adventure mode, has their inventory wiped, is sent back to
	 * the lobby, and is fully removed from their team (roster entry +
	 * backing scoreboard team) - nobody is left "still on a team" or holding
	 * a stale ready-up item after a round ends, which used to only happen on
	 * a manual reset (a natural win previously kept the roster "for a quick
	 * rematch", which was the actual cause of players occasionally finding
	 * themselves still teamed up, clay ball and all, after a game). The
	 * active map's saved structure is reloaded over its bounding box (undoing
	 * anything broken/built), the map is freed up for a future round, and
	 * this round's entire working state ({@link #spawns}, {@link #cores},
	 * {@link #kits}, mining progress, digging, pending respawns, ready
	 * state) is wiped. Ends with a fresh {@link #syncCores} + {@link
	 * #refreshHud} so clients/the sidebar reflect the now-empty state
	 * immediately rather than showing stale data until the next round.
	 */
	private void cleanupRound(MinecraftServer server) {
		if (server != null) {
			for (UUID uuid : players.keySet()) {
				ServerPlayer player = server.getPlayerList().getPlayer(uuid);
				if (player != null) {
					player.setGameMode(GameType.ADVENTURE);
					player.getInventory().clearContent();
					player.containerMenu.broadcastChanges();
					sendToLobby(player);
				}
			}
			// A team-wide sweep, not a per-player leaveScoreboardTeam call -
			// deliberately: a player who disconnected mid-round (RUNNING keeps
			// their roster entry for reconnect support - see handleDisconnect)
			// has no live ServerPlayer here, so a per-player call would have
			// silently skipped them, leaving their *vanilla* team membership
			// stuck forever (our own players map is still cleared below
			// either way) - exactly the "still on a team after a game"
			// symptom, but only when the round ended while they were offline.
			// This instead removes every current member of either backing
			// team by name, the same proven pattern setupHud already uses for
			// the equivalent post-restart case.
			clearScoreboardTeams(server);
		}

		MapConfig activeMap = MapRegistry.INSTANCE.activeMap();
		if (activeMap != null && server != null) {
			activeMap.resetStructure(server);
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
		if (server != null) {
			syncCores(server);
			refreshHud(server);
		}
	}

	/**
	 * Rebuild {@link #hardenedCores} from {@link #cores} and push a fresh
	 * {@link CoreSyncPayload} to every connected client. Call after any change to
	 * {@link #cores}, {@link #phase}, or a player's team. Sent per-player (not
	 * one shared broadcast) since each payload also carries the receiver's own
	 * team's core positions specifically.
	 */
	public void syncCores(MinecraftServer server) {
		hardenedCores.clear();
		for (Team t : Team.values()) {
			hardenedCores.addAll(cores.get(t).values());
		}
		if (server == null) {
			return;
		}
		List<BlockPos> allCores = new ArrayList<>(hardenedCores);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!ServerPlayNetworking.canSend(player, CoreSyncPayload.TYPE)) {
				continue;
			}
			Team team = teamOf(player.getUUID());
			List<BlockPos> own = team != null ? List.copyOf(cores.get(team).values()) : List.of();
			ServerPlayNetworking.send(player, new CoreSyncPayload(phase.ordinal(), allCores, own));
		}
	}

	/** Client-side: overwrite local state from a server snapshot. */
	public void applyClientSync(Phase newPhase, List<BlockPos> corePositions, List<BlockPos> ownCorePositions) {
		phase = newPhase;
		hardenedCores.clear();
		hardenedCores.addAll(corePositions);
		ownCores.clear();
		ownCores.addAll(ownCorePositions);
	}

	/** Wired to {@code ServerTickEvents.END_SERVER_TICK}. */
	public void tick(MinecraftServer server) {
		tickPendingRespawns(server);
		if (phase == Phase.STARTING) {
			tickCountdown(server);
		}
		if (phase == Phase.RUNNING) {
			roundElapsedTicks++;
			tickMiningSlowdownPriming(server);
			tickCoreMining(server);
		}
		hudTick++;
		if (hudTick % HUD_REFRESH_TICKS == 0) {
			refreshHud(server);
			if (phase == Phase.WAITING || phase == Phase.STARTING) {
				enforceReadyItems(server);
			}
		}
	}

	/** Count down each dead, waiting-to-respawn player; force their actual respawn once the delay elapses. */
	private void tickPendingRespawns(MinecraftServer server) {
		if (pendingRespawns.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, Integer>> it = pendingRespawns.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Integer> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				it.remove(); // logged off mid-delay
				continue;
			}
			int ticksLeft = entry.getValue();
			if (ticksLeft <= 0) {
				it.remove();
				forceRespawn(player);
				continue;
			}
			entry.setValue(ticksLeft - 1);
		}
	}

	/**
	 * Trigger a dead player's respawn ourselves, without them clicking anything -
	 * runs the exact same code vanilla runs for a manual "Respawn" click
	 * ({@code ServerGamePacketListenerImpl#handleClientCommand}, via a synthetic
	 * packet), so it's fully correct (position reset, client load timer, the
	 * connection's player reference, ...) with no logic of our own to get wrong.
	 * Vanilla puts them at the world/bed spawn first; {@code
	 * ServerPlayerEvents.AFTER_RESPAWN} -&gt; {@link #finishRespawn} immediately
	 * redirects them to their team spawn, same tick.
	 */
	private void forceRespawn(ServerPlayer player) {
		if (player.getHealth() > 0.0F) {
			return; // already respawned some other way (e.g. they clicked Respawn themselves just before this fired)
		}
		player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
	}

	// --- persistent core mining --------------------------------------------

	/** Called from {@code CoreMiningMixin} when a player starts holding left-click on a core. */
	public void startDigging(ServerPlayer player, BlockPos pos) {
		digging.put(player.getUUID(), pos);
		startMiningSlowdown(player);
	}

	/**
	 * Applies (or refreshes) the {@code Attributes.BLOCK_BREAK_SPEED} slowdown
	 * that makes a vanilla client's own local mining prediction agree with
	 * this server's real, longer mining time - see {@link #coreHardness}'s
	 * doc. Idempotent - safe to call repeatedly while already digging.
	 */
	private void startMiningSlowdown(ServerPlayer player) {
		AttributeInstance attribute = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
		if (attribute == null) {
			return;
		}
		double multiplier = VANILLA_BEACON_HARDNESS / coreHardness;
		attribute.addOrUpdateTransientModifier(
			new AttributeModifier(MINING_SLOWDOWN_ID, multiplier - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	/** Removes the mining slowdown applied by {@link #startMiningSlowdown} - safe to call even if it was never applied. */
	private void stopMiningSlowdown(@Nullable ServerPlayer player) {
		if (player == null) {
			return;
		}
		AttributeInstance attribute = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
		if (attribute != null) {
			attribute.removeModifier(MINING_SLOWDOWN_ID);
		}
	}

	/**
	 * Applying the slowdown reactively - only once a player actually starts
	 * digging - leaves a real gap: the attribute change has to round-trip
	 * back to the client (a synced vanilla attribute, but still a packet)
	 * before that client's *own* local mining prediction starts using it.
	 * Until it arrives, the client keeps predicting at the old, un-slowed
	 * speed, racing ahead of the server by roughly one network round-trip's
	 * worth of progress - which is exactly what made a core feel "stuck"
	 * just under 100%, needing an extra manual re-click to actually finish.
	 *
	 * <p>So every tick, for every rostered player, this pre-arms the same
	 * modifier the moment they're simply *looking at* an enemy core -
	 * before they've clicked at all - so by the time they actually start
	 * digging, the attribute has almost always already finished
	 * round-tripping. Cheap (one raycast per rostered player per tick,
	 * negligible at this mod's scale) and side-effect-free: it never
	 * touches anyone not currently sighted on an enemy core, so ordinary
	 * mining elsewhere is never slowed.
	 */
	private void tickMiningSlowdownPriming(MinecraftServer server) {
		for (UUID uuid : players.keySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player == null) {
				continue;
			}
			Team team = teamOf(uuid);
			HitResult hit = player.pick(MINING_SLOWDOWN_PRIME_REACH, 1.0F, false);
			boolean sightedOnEnemyCore = hit.getType() == HitResult.Type.BLOCK
				&& coreOwnerAt(((BlockHitResult) hit).getBlockPos()) == team.opposite();
			if (sightedOnEnemyCore) {
				startMiningSlowdown(player);
			} else if (!digging.containsKey(uuid)) {
				stopMiningSlowdown(player);
			}
		}
	}

	/**
	 * Called from {@code CoreMiningMixin} on release/abort.
	 *
	 * <p>The client predicts completion independently (it runs the exact same
	 * per-tick formula locally) and can reach "done" a hair before our own
	 * end-of-tick accumulator does - if its resulting STOP_DESTROY_BLOCK lands in
	 * the same server tick, it arrives (packets are handled at the start of the
	 * tick) <em>before</em> {@link #tickCoreMining} runs (end of tick), so the
	 * digger would already be gone from {@link #digging} and that tick's
	 * contribution would be skipped entirely - the block gets stuck just under
	 * 100% forever (never actually breaks server-side) while the client's
	 * speculative removal gets rolled back the moment the server's ack arrives,
	 * i.e. exactly "broke client-side, then popped back". Applying one last
	 * contribution right here, before removing them from tracking, closes that
	 * gap.
	 */
	public void stopDigging(ServerPlayer player, BlockPos pos) {
		digging.remove(player.getUUID());
		// Order matters: compute this last contribution BEFORE lifting the
		// slowdown attribute, so it's calculated at the same effective speed
		// every other tick was - removing it first would credit this tick at
		// the un-slowed (3x too fast) rate instead.
		applyMiningTick(player.level().getServer(), player, pos);
		stopMiningSlowdown(player);
	}

	/** Drop bookkeeping for a player who disconnected mid-dig (progress is unaffected). */
	public void clearDigging(UUID playerId) {
		digging.remove(playerId);
	}

	private void tickCoreMining(MinecraftServer server) {
		if (digging.isEmpty()) {
			return;
		}

		// Snapshot first: applyMiningTick can finish a core (breakCore ->
		// clearCoreProgress), which removes entries from `digging` - mutating the
		// map we're iterating would throw ConcurrentModificationException.
		for (Map.Entry<UUID, BlockPos> entry : new ArrayList<>(digging.entrySet())) {
			UUID playerId = entry.getKey();
			BlockPos pos = entry.getValue();
			if (!pos.equals(digging.get(playerId))) {
				continue; // already cleaned up earlier this tick (e.g. its core just broke)
			}
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null || coreOwnerAt(pos) == null) {
				digging.remove(playerId); // logged off, or the core is already gone
				stopMiningSlowdown(player);
				continue;
			}
			if (player.level().getBlockState(pos).getBlock() != Blocks.BEACON) {
				digging.remove(playerId);
				stopMiningSlowdown(player);
				continue;
			}
			applyMiningTick(server, player, pos);
		}
	}

	/** One tick's worth of mining progress on {@code pos} from {@code player}, finishing the core if it crosses 100%. */
	private void applyMiningTick(MinecraftServer server, ServerPlayer player, BlockPos pos) {
		Team owner = coreOwnerAt(pos);
		if (owner == null) {
			return; // already broken
		}
		ServerLevel level = player.level();
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() != Blocks.BEACON) {
			return;
		}
		// The exact same formula (and the exact same real block hardness +
		// synced attribute value) a vanilla client uses for its own local
		// prediction - see coreHardness's doc for why that's deliberate.
		float perTick = state.getDestroyProgress(player, level, pos);
		float total = Math.min(1.0F, coreProgress.getOrDefault(pos, 0.0F) + perTick);
		if (total >= MINING_COMPLETE_THRESHOLD) {
			breakCore(server, server.overworld(), pos, player);
		} else {
			coreProgress.put(pos, total);
			// "Above the hotbar" progress feedback for the miner specifically -
			// an action-bar message, sent fresh each tick they're digging (each
			// one resets the fade timer, so it stays continuously visible).
			int percent = Math.round((1.0F - total) * 100.0F);
			Side side = sideOf(owner, pos);
			String label = owner.name() + (side != null ? " " + side.label() : "") + " Core";
			player.sendSystemMessage(Component.literal("Mining ").append(Component.literal(label)
				.withStyle(owner.colour())).append(Component.literal(": " + percent + "%")), true);
		}
	}

	/**
	 * Actually remove a core: no item drop, break sound/particles only, win-check,
	 * and clear all mining bookkeeping/overlays for that position.
	 */
	public void breakCore(MinecraftServer server, ServerLevel level, BlockPos pos, @Nullable Entity breaker) {
		Team owner = coreOwnerAt(pos);
		if (owner == null) {
			return; // already handled (e.g. two contributions crossed the threshold same tick)
		}
		Side side = sideOf(owner, pos);
		cores.get(owner).remove(side);
		// Keeps its sidebar line - see coreLine - instead of just vanishing.
		destroyedCores.put(pos, new DestroyedCore(owner, side));
		clearCoreProgress(server, pos);
		level.destroyBlock(pos, false, breaker, 512); // false = no item drop
		playDestructionEffect(level, pos);
		// The fight gets a beat slower once cores start falling.
		respawnDelayTicks = Math.max(respawnDelayTicks, 60);

		if (cores.get(owner).isEmpty()) {
			endGame(server, owner.opposite());
		} else {
			syncCores(server);
		}
		refreshHud(server);
	}

	/** Also lifts the mining slowdown (see {@link #startMiningSlowdown}) from anyone who was mining {@code pos} when it broke. */
	private void clearCoreProgress(MinecraftServer server, BlockPos pos) {
		coreProgress.remove(pos);
		Iterator<Map.Entry<UUID, BlockPos>> it = digging.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, BlockPos> entry = it.next();
			if (entry.getValue().equals(pos)) {
				stopMiningSlowdown(server.getPlayerList().getPlayer(entry.getKey()));
				it.remove();
			}
		}
	}

	/** Fireworks-like particle burst + an explosion boom at a destroyed core. */
	private void playDestructionEffect(ServerLevel level, BlockPos pos) {
		double x = pos.getX() + 0.5;
		double y = pos.getY() + 0.5;
		double z = pos.getZ() + 0.5;
		level.sendParticles(ParticleTypes.FIREWORK, x, y, z, 80, 0.6, 0.6, 0.6, 0.2);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
		level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 4.0F, 1.0F);
	}

	// --- HUD: per-team sidebar + enemy-nearby warning -----------------------

	/**
	 * Registers the two per-team sidebar objectives, shown only to players on the
	 * matching-coloured scoreboard team ({@code DisplaySlot.TEAM_RED}/{@code
	 * TEAM_BLUE} - vanilla routes these per-viewer automatically, based on
	 * {@code TeamColor}, so a red-team player only ever sees the red objective,
	 * and a player on no team sees neither). Call once per server start (a fresh
	 * {@code ServerScoreboard} each time); reuses an objective already loaded
	 * from a saved scoreboard.dat instead of re-creating it.
	 *
	 * <p>Also clears any stale RED/BLUE scoreboard-team membership left over
	 * from before the restart: {@link #players} (our own roster) is always
	 * empty right after a restart, but the *vanilla* team membership backing
	 * it persists in scoreboard.dat independently - without this, a player who
	 * was on a team last time the server was up would still see that team's
	 * sidebar (and coloured name) on rejoin despite never having run {@code
	 * /recored join} this session.
	 *
	 * <p>Also purges any score entry on a team's objective that isn't in our
	 * current {@code "<team>_line_<n>"} naming scheme - a saved scoreboard.dat
	 * can carry entries left over from an earlier version of this mod (e.g. a
	 * long-gone {@code "<team>_core_<n>"} scheme), which would otherwise sit
	 * on the sidebar forever alongside the real lines, since nothing else ever
	 * touches an entry under a name {@link #refreshHud} doesn't recognise.
	 * {@link #refreshHud} itself decides whether the sidebar is even shown at
	 * all (only while {@link Phase#RUNNING}) - this call doesn't display it.
	 */
	public void setupHud(MinecraftServer server) {
		Scoreboard scoreboard = server.getScoreboard();

		// Orphaned objectives from before the mod was renamed from "Cores" to
		// "Recored" - dead weight, never assigned to a display slot by any
		// version of this code anymore, but they'd sit in scoreboard.dat
		// forever otherwise.
		for (String legacyName : new String[] {"cores_hud_red", "cores_hud_blue"}) {
			Objective legacy = scoreboard.getObjective(legacyName);
			if (legacy != null) {
				scoreboard.removeObjective(legacy);
			}
		}

		for (Team team : Team.values()) {
			String name = HUD_OBJECTIVE_PREFIX + team.lowerName();
			Objective objective = scoreboard.getObjective(name);
			if (objective == null) {
				objective = scoreboard.addObjective(
					name,
					ObjectiveCriteria.DUMMY,
					Component.literal(team.name() + " TEAM").withStyle(team.colour(), ChatFormatting.BOLD),
					ObjectiveCriteria.RenderType.INTEGER,
					false,
					BlankFormat.INSTANCE);
			}
			hudObjectives.put(team, objective);

			String currentLinePrefix = team.lowerName() + "_line_";
			String timerName = team.lowerName() + TIMER_LINE_NAME;
			for (PlayerScoreEntry entry : new ArrayList<>(scoreboard.listPlayerScores(objective))) {
				if (!entry.owner().startsWith(currentLinePrefix) && !entry.owner().equals(timerName)) {
					scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly(entry.owner()), objective);
				}
			}

			PlayerTeam scoreboardTeam = ensureScoreboardTeam(server, team);
			for (String staleMember : new ArrayList<>(scoreboardTeam.getPlayers())) {
				scoreboard.removePlayerFromTeam(staleMember, scoreboardTeam);
			}
		}
	}

	/**
	 * Refreshes both sidebars. Each team's objective (still only ever visible
	 * to that team's own viewers, via {@code DisplaySlot.TEAM_RED}/{@code
	 * TEAM_BLUE}) now lists <em>both</em> teams' cores - the viewer's own
	 * team's first (top), the enemy team's below (bottom) - so health% is
	 * effectively global (everyone can see both sides' core health), while
	 * the enemy-nearby blink + proximity warning note stay restricted to a
	 * team's own cores specifically (an enemy standing near *their* core is
	 * actionable news for them; near the enemy's own core, it isn't, and
	 * showing it would leak that team's movements to the opposing sidebar).
	 * A destroyed core keeps its row (struck through, red - see {@link
	 * #coreLine}) instead of disappearing; each row is also marked with a
	 * small coloured square for its owning team.
	 *
	 * <p>The sidebar itself is only ever shown while {@link Phase#RUNNING} -
	 * joining a team (or sitting in the lobby waiting/readying up) shouldn't
	 * already show a HUD for a round that hasn't started. Outside RUNNING
	 * this unassigns both display slots and clears every line, then returns;
	 * {@link #cleanupRound}'s own call into this right after a round ends is
	 * what makes that transition immediate rather than waiting up to {@link
	 * #HUD_REFRESH_TICKS}.
	 */
	private void refreshHud(MinecraftServer server) {
		if (server == null) {
			return;
		}
		Scoreboard scoreboard = server.getScoreboard();
		if (phase != Phase.RUNNING) {
			for (Team team : Team.values()) {
				Objective objective = hudObjectives.get(team);
				if (objective == null) {
					continue;
				}
				scoreboard.setDisplayObjective(team.teamColor().displaySlot(), null);
				scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly(team.lowerName() + TIMER_LINE_NAME), objective);
				for (int line = 0; line < MAX_HUD_LINES * 2; line++) {
					scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly(team.lowerName() + "_line_" + line), objective);
				}
			}
			return;
		}
		for (Team team : Team.values()) {
			Objective objective = hudObjectives.get(team);
			if (objective != null) {
				scoreboard.setDisplayObjective(team.teamColor().displaySlot(), objective);
			}
		}

		boolean blinkOn = (hudTick / (HUD_REFRESH_TICKS * BLINK_HALF_PERIOD)) % 2 == 0;
		boolean playWarningSound = hudTick % WARNING_SOUND_TICKS < HUD_REFRESH_TICKS;
		ServerLevel level = server.overworld();

		// Enemy-nearby is a property of a still-standing core itself (owner vs.
		// everyone else), computed once per core regardless of how many
		// sidebars end up showing it, so the warning note doesn't get
		// triggered/played twice (once per objective) for the same core.
		Map<BlockPos, Boolean> enemyNear = new HashMap<>();
		for (Team owner : Team.values()) {
			for (BlockPos pos : cores.get(owner).values()) {
				boolean near = hasEnemyNearby(server, owner, pos);
				enemyNear.put(pos, near);
				if (near && playWarningSound) {
					level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
						SoundEvents.NOTE_BLOCK_HARP, SoundSource.BLOCKS, 3.0F, 1.4F);
				}
			}
		}

		// Each team's full row set (still-standing cores - left then right -
		// then destroyed ones) is the same regardless of which sidebar ends up
		// showing it (as an "own" or "enemy" section) - build it once per team.
		Map<Team, List<CoreRow>> rowsByTeam = new EnumMap<>(Team.class);
		for (Team team : Team.values()) {
			List<CoreRow> rows = new ArrayList<>();
			for (Side side : Side.values()) {
				BlockPos pos = cores.get(team).get(side);
				if (pos != null && rows.size() < MAX_HUD_LINES) {
					rows.add(new CoreRow(pos, side, false));
				}
			}
			for (Map.Entry<BlockPos, DestroyedCore> entry : destroyedCores.entrySet()) {
				if (entry.getValue().team() != team) {
					continue;
				}
				if (rows.size() >= MAX_HUD_LINES) {
					break;
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
			Team enemyTeam = viewerTeam.opposite();
			List<CoreRow> ownRows = rowsByTeam.get(viewerTeam);
			List<CoreRow> enemyRows = rowsByTeam.get(enemyTeam);
			int totalLines = ownRows.size() + enemyRows.size();

			// Elapsed-time line always sits on top - a score comfortably above
			// any core row's (which top out at totalLines) guarantees that
			// regardless of how many rows are actually showing.
			ScoreHolder timerHolder = ScoreHolder.forNameOnly(viewerTeam.lowerName() + TIMER_LINE_NAME);
			ScoreAccess timerAccess = scoreboard.getOrCreatePlayerScore(timerHolder, objective, true);
			timerAccess.set(totalLines + 1);
			timerAccess.display(timerLine());

			int line = 0;
			for (int i = 0; i < ownRows.size(); i++, line++) {
				CoreRow row = ownRows.get(i);
				setHudLine(scoreboard, objective, viewerTeam, line, totalLines,
					coreLine(viewerTeam, row.side(), row.pos(), row.destroyed(), enemyNear.getOrDefault(row.pos(), false), blinkOn));
			}
			// Enemy section never blinks/bolds - see the method doc above.
			for (int i = 0; i < enemyRows.size(); i++, line++) {
				CoreRow row = enemyRows.get(i);
				setHudLine(scoreboard, objective, viewerTeam, line, totalLines,
					coreLine(enemyTeam, row.side(), row.pos(), row.destroyed(), false, false));
			}
			// Clear any lines left over from a previous, longer refresh (round
			// just ended, a core count shrank, ...) - fixed upper bound is
			// simplest and cheap; resetting an already-absent line is a no-op.
			for (; line < MAX_HUD_LINES * 2; line++) {
				scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly(viewerTeam.lowerName() + "_line_" + line), objective);
			}
		}
	}

	/** One sidebar row's source data: the core's position, which of its team's slots it is, and whether it's still standing. */
	private record CoreRow(BlockPos pos, Side side, boolean destroyed) {
	}

	private void setHudLine(Scoreboard scoreboard, Objective objective, Team viewerTeam, int line, int totalLines, MutableComponent text) {
		ScoreHolder holder = ScoreHolder.forNameOnly(viewerTeam.lowerName() + "_line_" + line);
		ScoreAccess access = scoreboard.getOrCreatePlayerScore(holder, objective, true);
		access.set(totalLines - line); // higher score = higher up the sidebar (vanilla sorts descending)
		access.display(text);
	}

	/**
	 * "Time: M:SS" since the round went RUNNING - uncapped, same for both
	 * teams (identical wall-clock elapsed time), always the topmost line.
	 */
	private MutableComponent timerLine() {
		int totalSeconds = roundElapsedTicks / 20;
		int minutes = totalSeconds / 60;
		int seconds = totalSeconds % 60;
		return Component.literal(String.format("Time: %d:%02d", minutes, seconds)).withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD);
	}

	/**
	 * One sidebar line: {@code "■ <RED|BLUE> <Left|Right> Core: <health%>"},
	 * or {@code "■ <RED|BLUE> <Left|Right> Core: DESTROYED"} in red/struck-
	 * through once it's gone - it keeps its row rather than disappearing.
	 * Both the team and the side are always named, since a team's Left/Right
	 * cores are tracked independently of the *other* team's Left/Right cores
	 * - "Left Core" alone would be ambiguous once both sections of a sidebar
	 * can show one.
	 */
	private MutableComponent coreLine(Team owner, Side side, BlockPos pos, boolean destroyed, boolean enemyNearby, boolean blinkOn) {
		MutableComponent marker = Component.literal("■ ").withStyle(owner.colour());
		String label = owner.name() + " " + side.label() + " Core";
		if (destroyed) {
			return marker.append(Component.literal(label + ": DESTROYED").withStyle(ChatFormatting.RED, ChatFormatting.STRIKETHROUGH));
		}
		int healthPercent = Math.max(0, Math.round((1.0F - coreProgress.getOrDefault(pos, 0.0F)) * 100.0F));
		ChatFormatting healthColour = healthPercent <= 20 ? ChatFormatting.RED
			: healthPercent <= 50 ? ChatFormatting.YELLOW
			: ChatFormatting.GREEN;
		ChatFormatting colour = enemyNearby && blinkOn ? ChatFormatting.WHITE : healthColour;
		MutableComponent line = marker.append(Component.literal(label + ": " + healthPercent + "%").withStyle(colour));
		if (enemyNearby) {
			line.withStyle(ChatFormatting.BOLD);
		}
		return line;
	}

	private boolean hasEnemyNearby(MinecraftServer server, Team owner, BlockPos pos) {
		double x = pos.getX() + 0.5;
		double y = pos.getY() + 0.5;
		double z = pos.getZ() + 0.5;
		double rangeSq = ENEMY_WARNING_RANGE * ENEMY_WARNING_RANGE;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Team playerTeam = teamOf(player.getUUID());
			if (playerTeam == null || playerTeam == owner) {
				continue;
			}
			if (player.distanceToSqr(x, y, z) <= rangeSq) {
				return true;
			}
		}
		return false;
	}

	// --- scoreboard team colouring (tab list + nametag) ---------------------

	private PlayerTeam ensureScoreboardTeam(MinecraftServer server, Team team) {
		Scoreboard scoreboard = server.getScoreboard();
		String name = SCOREBOARD_TEAM_PREFIX + team.lowerName();
		PlayerTeam playerTeam = scoreboard.getPlayerTeam(name);
		if (playerTeam == null) {
			playerTeam = scoreboard.addPlayerTeam(name);
			playerTeam.setColor(Optional.of(team.teamColor()));
		}
		return playerTeam;
	}

	public void joinScoreboardTeam(MinecraftServer server, ServerPlayer player, Team team) {
		server.getScoreboard().addPlayerToTeam(player.getScoreboardName(), ensureScoreboardTeam(server, team));
	}

	public void leaveScoreboardTeam(MinecraftServer server, ServerPlayer player) {
		server.getScoreboard().removePlayerFromTeam(player.getScoreboardName());
	}

	/**
	 * Removes every current member of both backing scoreboard teams, by name -
	 * unlike {@link #leaveScoreboardTeam}, this needs no live {@link
	 * ServerPlayer} for whoever it removes, so it also catches a player who
	 * disconnected mid-round and hasn't reconnected yet. Used by {@link
	 * #cleanupRound} for exactly that reason; {@link #setupHud} does the same
	 * thing for the equivalent post-restart case.
	 */
	private void clearScoreboardTeams(MinecraftServer server) {
		Scoreboard scoreboard = server.getScoreboard();
		for (Team team : Team.values()) {
			PlayerTeam scoreboardTeam = ensureScoreboardTeam(server, team);
			for (String member : new ArrayList<>(scoreboardTeam.getPlayers())) {
				scoreboard.removePlayerFromTeam(member, scoreboardTeam);
			}
		}
	}

	private void broadcast(MinecraftServer server, Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
		RecoredMod.LOGGER.info("[Recored] {}", message.getString());
	}
}
