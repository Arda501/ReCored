package com.example.recored.net;

import java.util.ArrayList;
import java.util.List;

import com.example.recored.RecoredMod;
import com.example.recored.game.GameManager;
import com.example.recored.game.Phase;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client snapshot of the bits of {@link GameManager} the client needs
 * for block-break prediction to match the server: the current {@link Phase},
 * every registered core position, and - specifically for the receiving player -
 * which of those belong to their own team. Sent per-player (not identically
 * broadcast) since "own team" differs per receiver; see
 * {@link GameManager#syncCores}.
 */
public record CoreSyncPayload(int phaseOrdinal, List<BlockPos> cores, List<BlockPos> ownCores) implements CustomPacketPayload {

	public static final Type<CoreSyncPayload> TYPE =
		new Type<>(Identifier.fromNamespaceAndPath(RecoredMod.MOD_ID, "core_sync"));

	public static final StreamCodec<FriendlyByteBuf, CoreSyncPayload> CODEC =
		CustomPacketPayload.codec(CoreSyncPayload::write, CoreSyncPayload::new);

	private CoreSyncPayload(FriendlyByteBuf buf) {
		this(buf.readVarInt(), readPositions(buf), readPositions(buf));
	}

	private static List<BlockPos> readPositions(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		List<BlockPos> list = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			list.add(BlockPos.of(buf.readLong()));
		}
		return list;
	}

	private static void writePositions(FriendlyByteBuf buf, List<BlockPos> positions) {
		buf.writeVarInt(positions.size());
		for (BlockPos pos : positions) {
			buf.writeLong(pos.asLong());
		}
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(phaseOrdinal);
		writePositions(buf, cores);
		writePositions(buf, ownCores);
	}

	@Override
	public Type<CoreSyncPayload> type() {
		return TYPE;
	}

	public Phase phase() {
		Phase[] values = Phase.values();
		return phaseOrdinal >= 0 && phaseOrdinal < values.length ? values[phaseOrdinal] : Phase.WAITING;
	}
}
