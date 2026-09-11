package com.example.cores.command;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import com.example.cores.game.GameManager;
import com.example.cores.game.Phase;
import com.example.cores.game.SpawnRegion;
import com.example.cores.game.Team;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * All {@code /cores ...} subcommands. Registered from
 * {@link com.example.cores.CoresMod} through Fabric's
 * {@code CommandRegistrationCallback}.
 */
public final class CoresCommand {

	private static final double RAYCAST_REACH = 24.0;

	private static final SimpleCommandExceptionType NOT_LOOKING_AT_BLOCK =
		new SimpleCommandExceptionType(Component.literal("You must be looking at a block"));
	private static final SimpleCommandExceptionType NO_SELECTION =
		new SimpleCommandExceptionType(Component.literal("Set both corners first with /cores pos1 and /cores pos2"));
	private static final SimpleCommandExceptionType WRONG_PHASE =
		new SimpleCommandExceptionType(Component.literal("That is only allowed while the game is WAITING"));

	/** Per-player WorldEdit-style region selection ({@code pos1}/{@code pos2}). */
	private static final Map<UUID, BlockPos[]> SELECTIONS = new HashMap<>();

	private CoresCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cores");

		// --- open to everyone ---
		root.then(teamArg("join", CoresCommand::join));
		root.then(Commands.literal("leave").executes(CoresCommand::leave));
		root.then(Commands.literal("status").executes(CoresCommand::status));

		// --- configuration / control (game masters only) ---
		root.then(teamArg("setspawn", CoresCommand::setSpawn).requires(admin()));
		root.then(teamArg("addcore", CoresCommand::addCore).requires(admin()));
		root.then(teamArg("removecore", CoresCommand::removeCore).requires(admin()));
		root.then(teamArg("setregion", CoresCommand::setRegion).requires(admin()));
		root.then(teamArg("setkit", CoresCommand::setKit).requires(admin()));
		root.then(Commands.literal("pos1").requires(admin()).executes(c -> setPos(c, 0)));
		root.then(Commands.literal("pos2").requires(admin()).executes(c -> setPos(c, 1)));
		root.then(Commands.literal("start").requires(admin()).executes(CoresCommand::start));
		root.then(Commands.literal("reset").requires(admin()).executes(CoresCommand::reset));

		dispatcher.register(root);
	}

	private static java.util.function.Predicate<CommandSourceStack> admin() {
		return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
	}

	/** Builds {@code <name> <red|blue>} with one executor shared by both literals. */
	private static LiteralArgumentBuilder<CommandSourceStack> teamArg(
		String name, Function<Team, Command<CommandSourceStack>> executor) {
		LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(name);
		for (Team team : Team.values()) {
			node.then(Commands.literal(team.lowerName()).executes(executor.apply(team)));
		}
		return node;
	}

	// --- subcommand handlers -------------------------------------------------

	private static Command<CommandSourceStack> join(Team team) {
		return ctx -> {
			GameManager gm = GameManager.INSTANCE;
			if (gm.phase != Phase.WAITING) {
				throw WRONG_PHASE.create();
			}
			ServerPlayer player = ctx.getSource().getPlayerOrException();
			gm.players.put(player.getUUID(), team);
			ctx.getSource().sendSuccess(
				() -> Component.literal("Joined ").append(Component.literal(team.name()).withStyle(team.colour())).append(" team"),
				false);
			return Command.SINGLE_SUCCESS;
		};
	}

	private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		GameManager gm = GameManager.INSTANCE;
		if (gm.phase != Phase.WAITING) {
			throw WRONG_PHASE.create();
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (gm.players.remove(player.getUUID()) == null) {
			ctx.getSource().sendFailure(Component.literal("You are not on a team"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Left your team"), false);
		return Command.SINGLE_SUCCESS;
	}

	private static Command<CommandSourceStack> setSpawn(Team team) {
		return ctx -> {
			CommandSourceStack source = ctx.getSource();
			ServerPlayer player = source.getPlayerOrException();
			Vec3 pos = source.getPosition();
			Vec2 rot = source.getRotation();
			GameManager.INSTANCE.spawns.put(team, new GameManager.Spawn(
				player.level().dimension(), pos.x, pos.y, pos.z, rot.y, rot.x));
			source.sendSuccess(() -> Component.literal(team.name() + " spawn set to " + fmt(pos)), false);
			return Command.SINGLE_SUCCESS;
		};
	}

	private static Command<CommandSourceStack> addCore(Team team) {
		return ctx -> {
			BlockPos pos = lookedAtBlock(ctx.getSource());
			GameManager gm = GameManager.INSTANCE;
			if (gm.isCore(pos)) {
				ctx.getSource().sendFailure(Component.literal("That block is already a core"));
				return 0;
			}
			gm.cores.get(team).add(pos.immutable());
			gm.syncCores(ctx.getSource().getServer());
			ctx.getSource().sendSuccess(
				() -> Component.literal("Added " + team.name() + " core at " + pos.toShortString()
					+ " (" + gm.cores.get(team).size() + " total)"),
				false);
			return Command.SINGLE_SUCCESS;
		};
	}

	private static Command<CommandSourceStack> removeCore(Team team) {
		return ctx -> {
			BlockPos pos = lookedAtBlock(ctx.getSource());
			if (GameManager.INSTANCE.cores.get(team).remove(pos)) {
				GameManager.INSTANCE.syncCores(ctx.getSource().getServer());
				ctx.getSource().sendSuccess(() -> Component.literal("Removed " + team.name() + " core at " + pos.toShortString()), false);
				return Command.SINGLE_SUCCESS;
			}
			ctx.getSource().sendFailure(Component.literal("No " + team.name() + " core at that block"));
			return 0;
		};
	}

	private static int setPos(CommandContext<CommandSourceStack> ctx, int index) throws CommandSyntaxException {
		BlockPos pos = lookedAtBlock(ctx.getSource());
		UUID id = ctx.getSource().getPlayerOrException().getUUID();
		SELECTIONS.computeIfAbsent(id, k -> new BlockPos[2])[index] = pos.immutable();
		ctx.getSource().sendSuccess(() -> Component.literal("pos" + (index + 1) + " = " + pos.toShortString()), false);
		return Command.SINGLE_SUCCESS;
	}

	private static Command<CommandSourceStack> setRegion(Team team) {
		return ctx -> {
			UUID id = ctx.getSource().getPlayerOrException().getUUID();
			BlockPos[] sel = SELECTIONS.get(id);
			if (sel == null || sel[0] == null || sel[1] == null) {
				throw NO_SELECTION.create();
			}
			SpawnRegion region = SpawnRegion.of(sel[0], sel[1]);
			GameManager.INSTANCE.spawnRegions.put(team, region);
			ctx.getSource().sendSuccess(
				() -> Component.literal(team.name() + " spawn region set: "
					+ region.min().toShortString() + " -> " + region.max().toShortString()),
				false);
			return Command.SINGLE_SUCCESS;
		};
	}

	private static Command<CommandSourceStack> setKit(Team team) {
		return ctx -> {
			ServerPlayer player = ctx.getSource().getPlayerOrException();
			var kit = GameManager.INSTANCE.kits.get(team);
			kit.clear();
			var inv = player.getInventory();
			for (int slot = 0; slot < inv.getContainerSize(); slot++) {
				ItemStack stack = inv.getItem(slot);
				if (!stack.isEmpty()) {
					kit.add(stack.copy());
				}
			}
			ctx.getSource().sendSuccess(
				() -> Component.literal("Captured " + kit.size() + " item stack(s) as the " + team.name() + " kit"),
				false);
			return Command.SINGLE_SUCCESS;
		};
	}

	private static int start(CommandContext<CommandSourceStack> ctx) {
		GameManager gm = GameManager.INSTANCE;
		if (gm.phase != Phase.WAITING) {
			ctx.getSource().sendFailure(Component.literal("Game is not in WAITING (" + gm.phase + ")"));
			return 0;
		}
		if (!gm.readyToStart()) {
			ctx.getSource().sendFailure(Component.literal(
				"Not ready: need a spawn, at least one core and at least one player per team"));
			return 0;
		}
		gm.beginStart(ctx.getSource().getServer());
		return Command.SINGLE_SUCCESS;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) {
		GameManager.INSTANCE.reset(ctx.getSource().getServer());
		ctx.getSource().sendSuccess(() -> Component.literal("Cores game reset to WAITING"), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		GameManager gm = GameManager.INSTANCE;
		CommandSourceStack s = ctx.getSource();
		s.sendSuccess(() -> Component.literal("Cores status").withStyle(ChatFormatting.BOLD), false);
		s.sendSuccess(() -> Component.literal("  phase: " + gm.phase), false);
		for (Team team : Team.values()) {
			long members = gm.players.values().stream().filter(t -> t == team).count();
			String line = "  " + team.name() + ": " + members + " player(s), "
				+ gm.cores.get(team).size() + " core(s), "
				+ (gm.spawns.containsKey(team) ? "spawn set" : "NO SPAWN") + ", "
				+ (gm.spawnRegions.containsKey(team) ? "region set" : "no region") + ", "
				+ (gm.kits.get(team).isEmpty() ? "no kit" : gm.kits.get(team).size() + "-item kit");
			s.sendSuccess(() -> Component.literal(line).withStyle(team.colour()), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	// --- helpers ----------------------------------------------------------

	private static BlockPos lookedAtBlock(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		HitResult hit = player.pick(RAYCAST_REACH, 1.0F, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			throw NOT_LOOKING_AT_BLOCK.create();
		}
		return ((BlockHitResult) hit).getBlockPos();
	}

	private static String fmt(Vec3 v) {
		return String.format("%.1f, %.1f, %.1f", v.x, v.y, v.z);
	}
}
