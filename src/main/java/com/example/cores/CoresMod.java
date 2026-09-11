package com.example.cores;

import com.example.cores.command.CoresCommand;
import com.example.cores.game.GameManager;
import com.example.cores.game.Phase;
import com.example.cores.game.Team;
import com.example.cores.net.CoreSyncPayload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the "Cores" team-objective gamemode. Wires together the four
 * moving pieces:
 *
 * <ol>
 *   <li>{@link GameManager} - all mutable round state (singleton)</li>
 *   <li>{@link CoresCommand} - {@code /cores ...} to configure, join and control</li>
 *   <li>the {@code PlayerBlockBreakEvents.BEFORE} listener below - spawn
 *       protection and "last core broken = game over" logic</li>
 *   <li>{@code BeaconHardnessMixin} - makes core beacons mine at obsidian speed</li>
 * </ol>
 */
public class CoresMod implements ModInitializer {

	public static final String MOD_ID = "cores";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Payload used to mirror core positions + phase to clients so their
		// block-break prediction (and the hardness mixin) matches the server.
		PayloadTypeRegistry.clientboundPlay().register(CoreSyncPayload.TYPE, CoreSyncPayload.CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			CoresCommand.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(GameManager.INSTANCE::tick);

		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			GameManager gm = GameManager.INSTANCE;

			// No restrictions outside a live round. On the client this listener
			// only runs for break prediction - all authoritative mutation happens
			// on the server, so bail out client-side.
			if (gm.phase != Phase.RUNNING || level.isClientSide()) {
				return true;
			}

			// Core check first: an enemy core sitting inside a spawn region must
			// still be breakable, so this takes priority over spawn protection.
			Team owner = gm.coreOwnerAt(pos);
			if (owner != null) {
				gm.cores.get(owner).remove(pos);
				if (gm.cores.get(owner).isEmpty()) {
					// That team just lost their last core - the other team wins.
					gm.endGame(level.getServer(), owner.opposite());
				} else {
					gm.syncCores(level.getServer());
				}
				return true; // allow the break itself
			}

			// Spawn protection: cancel breaks inside any team's spawn region.
			if (gm.isProtected(pos)) {
				return false;
			}

			return true; // normal block, breakable
		});

		LOGGER.info("Cores gamemode initialized (id: {})", Identifier.fromNamespaceAndPath(MOD_ID, "root"));
	}
}
