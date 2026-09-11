package com.example.recored;

import com.example.recored.command.RecoredCommand;
import com.example.recored.game.GameManager;
import com.example.recored.game.MapPersistence;
import com.example.recored.game.Phase;
import com.example.recored.game.SignCommands;
import com.example.recored.game.Team;
import com.example.recored.net.CoreSyncPayload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the "Recored" team-objective gamemode. Wires together the
 * moving pieces:
 *
 * <ul>
 *   <li>{@link GameManager} - all mutable round state (singleton); its working
 *       spawns/cores/regions/kits are populated each round from whichever
 *       {@code MapConfig} {@code MapRegistry} picks</li>
 *   <li>{@link RecoredCommand} + {@code MapCommand} - {@code /recored ...} to
 *       configure maps/lobby, join and control the round</li>
 *   <li>the {@code PlayerBlockBreakEvents.BEFORE} listener below - spawn/lobby
 *       protection and "last core broken = game over" logic</li>
 *   <li>{@code BeaconHardnessMixin}/{@code CoreMiningMixin}/{@code
 *       CoreProtectionMixin} - core mining behaviour</li>
 *   <li>{@link SignCommands} + the sign-click {@code UseBlockCallback} below -
 *       {@code /recored sign set|remove}-configured "command signs"</li>
 *   <li>the {@code UseItemCallback}/{@code ServerLivingEntityEvents.
 *       ALLOW_DAMAGE} listeners below - the ready-up item toggle and the
 *       RUNNING-only, rostered-players-only PVP gate</li>
 * </ul>
 */
public class RecoredMod implements ModInitializer {

	public static final String MOD_ID = "recored";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Payload used to mirror core positions + phase to clients so their
		// block-break prediction (and the hardness mixin) matches the server.
		PayloadTypeRegistry.clientboundPlay().register(CoreSyncPayload.TYPE, CoreSyncPayload.CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			RecoredCommand.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(GameManager.INSTANCE::tick);

		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			GameManager gm = GameManager.INSTANCE;

			// On the client this listener only runs for break prediction - all
			// authoritative mutation happens on the server, so bail out client-side.
			if (level.isClientSide()) {
				return true;
			}

			// Survival mining of a core never reaches here while RUNNING -
			// CoreMiningMixin intercepts it and drives GameManager's own
			// persistent progress tracker instead. This is a safety net for any
			// break that still goes through vanilla's normal path (creative
			// insta-mine, in particular): handle the removal ourselves (no item
			// drop) and cancel vanilla's.
			if (gm.phase == Phase.RUNNING && gm.isCore(pos) && level instanceof ServerLevel serverLevel) {
				Team owner = gm.coreOwnerAt(pos);
				Team breakerTeam = gm.teamOf(player.getUUID());
				if (owner == breakerTeam) {
					return false; // can't break your own core - not even in creative
				}
				gm.breakCore(level.getServer(), serverLevel, pos, player);
				return false;
			}

			// Spawn protection always applies, not just while RUNNING: the active
			// map's team spawn regions while a round is live, the lobby region at
			// any other time (see GameManager#isProtected).
			if (gm.isProtected(pos)) {
				return false;
			}

			return true; // normal block, breakable
		});

		// Death is real - nothing cancelled, nothing dropped (keep_inventory is on,
		// set below), player sees the normal death screen. We start a short,
		// live-adjustable countdown (/recored respawndelay) the moment they actually
		// die; once it elapses we force their respawn ourselves (no click needed -
		// see GameManager#forceRespawn), which lands them at the vanilla world/bed
		// spawn for an instant before AFTER_RESPAWN redirects them straight to
		// their team spawn with a fresh kit. Net effect: die, death screen for a
		// beat, appear at team spawn - no detour through server spawn visible.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof ServerPlayer player)) {
				return;
			}
			GameManager gm = GameManager.INSTANCE;
			Team team = gm.teamOf(player.getUUID());
			if (gm.phase == Phase.RUNNING && team != null) {
				gm.beginRespawnDelay(player);
			}
		});

		// Default respawn destination follows the active map while a round is
		// live, and the lobby at any other time (e.g. dying in the lobby itself).
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			GameManager gm = GameManager.INSTANCE;
			Team team = gm.teamOf(newPlayer.getUUID());
			if (gm.phase == Phase.RUNNING && team != null) {
				gm.finishRespawn(newPlayer, team);
			} else {
				gm.sendToLobby(newPlayer);
			}
		});

		// No right-click interaction with core beacons at all - not the beam/effect
		// GUI, nothing. Applies regardless of round phase. Also: right-clicking a
		// sign with a command attached (see /recored sign set|remove) runs that
		// command as the clicking player - the sign's own text is whatever the
		// admin wrote with normal sign editing, untouched by us.
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			BlockPos pos = hitResult.getBlockPos();
			if (GameManager.INSTANCE.isCore(pos)) {
				return InteractionResult.FAIL;
			}
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
				&& level.getBlockEntity(pos) instanceof SignBlockEntity) {
				String command = SignCommands.INSTANCE.get(level, pos);
				if (command != null) {
					serverPlayer.level().getServer().getCommands()
						.performPrefixedCommand(serverPlayer.createCommandSourceStack(), command);
					return InteractionResult.SUCCESS;
				}
			}
			return InteractionResult.PASS;
		});

		// Right-clicking the ready/not-ready item (given on /recored join - see
		// GameManager#giveReadyItem) toggles it, which drives the equal-teams +
		// all-ready auto-start countdown - see GameManager#toggleReady.
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			ItemStack stack = player.getItemInHand(hand);
			if (!GameManager.INSTANCE.isReadyMarkerStack(stack)) {
				return InteractionResult.PASS;
			}
			GameManager.INSTANCE.toggleReady(serverPlayer);
			return InteractionResult.SUCCESS;
		});

		// PVP is scoped entirely to this event, independent of the vanilla PVP
		// gamerule: player-vs-player damage is only ever allowed while a round is
		// RUNNING, and only between two rostered team members - never against/
		// from a bystander in the lobby, and never outside RUNNING at all. This
		// is both the "enable" and the automatic "disable again" - there's no
		// separate on/off toggle to remember to flip back.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (!(entity instanceof ServerPlayer victim)) {
				return true;
			}
			if (!(source.getEntity() instanceof ServerPlayer attacker) || attacker == victim) {
				return true; // not player-inflicted damage - not ours to gate
			}
			GameManager gm = GameManager.INSTANCE;
			if (gm.phase != Phase.RUNNING) {
				return false;
			}
			return gm.teamOf(victim.getUUID()) != null && gm.teamOf(attacker.getUUID()) != null;
		});

		// New joins (and anyone reconnecting outside a live round) land in the
		// lobby; a player rejoining mid-round with a team goes straight back to
		// their team spawn.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			GameManager gm = GameManager.INSTANCE;
			ServerPlayer player = handler.player;
			Team team = gm.teamOf(player.getUUID());
			if (gm.phase == Phase.RUNNING && team != null) {
				gm.teleportToSpawn(player, team);
			} else {
				gm.sendToLobby(player);
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			GameManager.INSTANCE.handleDisconnect(server, handler.player));

		// Real, visible death screen (not skipped) - we drive the respawn timing
		// ourselves now - and no item drops on death (a fresh kit gets reissued on
		// respawn regardless, dropped items would just be clutter/duplicates).
		// Also (re)register the per-team sidebar HUD - a fresh ServerScoreboard
		// exists each time the server (re)starts.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			GameRules rules = server.getGameRules();
			rules.set(GameRules.IMMEDIATE_RESPAWN, false, server);
			rules.set(GameRules.KEEP_INVENTORY, true, server);
			GameManager.INSTANCE.setupHud(server);
			// Registered maps + the lobby are otherwise pure in-memory state -
			// load whatever was saved (see MapPersistence) so they survive a
			// restart instead of starting empty every time.
			MapPersistence.load(server);
		});

		LOGGER.info("Recored gamemode initialized (id: {})", Identifier.fromNamespaceAndPath(MOD_ID, "root"));
	}
}
