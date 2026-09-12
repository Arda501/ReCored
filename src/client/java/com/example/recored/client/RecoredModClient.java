package com.example.recored.client;

import com.example.recored.game.GameManager;
import com.example.recored.net.CoreSyncPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;

/**
 * Client half of the mod - entirely optional, since the server never requires
 * clients to have it installed (see {@code GameManager#startDigging}'s
 * mining-slowdown mechanism, which works against a plain vanilla client).
 * The gameplay logic lives in shared code; a client that does install this
 * only needs to keep its local {@link GameManager} mirror up to date so
 * block-break prediction and {@code CoreProtectionMixin} behave like the
 * server.
 */
public class RecoredModClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(CoreSyncPayload.TYPE, (payload, context) -> {
			GameManager.INSTANCE.applyClientSync(payload.phase(), payload.cores(), payload.ownCores());
		});

		// Drop stale state when leaving a server / world.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			GameManager.INSTANCE.applyClientSync(com.example.recored.game.Phase.WAITING, java.util.List.<BlockPos>of(), java.util.List.<BlockPos>of()));
	}
}
