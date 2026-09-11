package com.example.cores.net;

import java.util.ArrayList;
import java.util.List;

import com.example.cores.CoresMod;
import com.example.cores.game.GameManager;
import com.example.cores.game.Phase;
import com.example.cores.game.Team;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client snapshot of the bits of {@link GameManager} the client needs
 * for block-break prediction to match the server: the current {@link Phase} and
 * every registered core position. Broadcast on every state change (see
 * {@link GameManager#syncCores}).
 *
 * <p>Team attribution is intentionally dropped - the client only asks "is this
 * block a core?" when deciding mining speed via {@code BeaconHardnessMixin}.
 */
public record CoreSyncPayload(int phaseOrdinal, List<BlockPos> cores) implements CustomPacketPayload {

	public static final Type<CoreSyncPayload> TYPE =
		new Type<>(Identifier.fromNamespaceAndPath(CoresMod.MOD_ID, "core_sync"));

	public static final StreamCodec<FriendlyByteBuf, CoreSyncPayload> CODEC =
		CustomPacketPayload.codec(CoreSyncPayload::write, CoreSyncPayload::new);

	private CoreSyncPayload(FriendlyByteBuf buf) {
		this(buf.readVarInt(), readPositions(buf));
	}

	private static List<BlockPos> readPositions(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		List<BlockPos> list = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			list.add(BlockPos.of(buf.readLong()));
		}
		return list;
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(phaseOrdinal);
		buf.writeVarInt(cores.size());
		for (BlockPos pos : cores) {
			buf.writeLong(pos.asLong());
		}
	}

	@Override
	public Type<CoreSyncPayload> type() {
		return TYPE;
	}

	/** Snapshot the current server-side state into a payload. */
	public static CoreSyncPayload snapshot() {
		GameManager gm = GameManager.INSTANCE;
		List<BlockPos> all = new ArrayList<>();
		for (Team team : Team.values()) {
			all.addAll(gm.cores.get(team));
		}
		return new CoreSyncPayload(gm.phase.ordinal(), all);
	}

	public Phase phase() {
		Phase[] values = Phase.values();
		return phaseOrdinal >= 0 && phaseOrdinal < values.length ? values[phaseOrdinal] : Phase.WAITING;
	}
}
