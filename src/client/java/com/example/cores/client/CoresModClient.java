package com.example.cores.client;

import com.example.cores.game.GameManager;
import com.example.cores.net.CoreSyncPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;

/**
 * Client half of the mod. The gameplay logic lives in shared code; the client
 * only needs to keep its local {@link GameManager} mirror up to date so
 * block-break prediction and {@code BeaconHardnessMixin} behave like the server.
 */
public class CoresModClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(CoreSyncPayload.TYPE, (payload, context) -> {
			GameManager.INSTANCE.applyClientSync(payload.phase(), payload.cores());
		});

		// Drop stale state when leaving a server / world.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			GameManager.INSTANCE.applyClientSync(com.example.cores.game.Phase.WAITING, java.util.List.<BlockPos>of()));
	}
}
