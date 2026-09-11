package com.example.recored.command;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import com.example.recored.game.GameManager;
import com.example.recored.game.MapPersistence;
import com.example.recored.game.MapRegistry;
import com.example.recored.game.Phase;
import com.example.recored.game.SignCommands;
import com.example.recored.game.Spawn;
import com.example.recored.game.SpawnRegion;
import com.example.recored.game.Team;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * All {@code /recored ...} subcommands. Registered from
 * {@link com.example.recored.RecoredMod} through Fabric's
 * {@code CommandRegistrationCallback}. Map configuration lives under
 * {@code /recored map ...}, built by {@link MapCommand} (shares this class's
 * raycast helper, admin gate and pos1/pos2 selection).
 */
public final class RecoredCommand {

	private static final double RAYCAST_REACH = 24.0;

	static final SimpleCommandExceptionType NOT_LOOKING_AT_BLOCK =
		new SimpleCommandExceptionType(Component.literal("You must be looking at a block"));
	static final SimpleCommandExceptionType NO_SELECTION =
		new SimpleCommandExceptionType(Component.literal("Set both corners first with /recored pos1 and /recored pos2"));
	private static final SimpleCommandExceptionType WRONG_PHASE =
		new SimpleCommandExceptionType(Component.literal("That is only allowed while the game is WAITING"));
	private static final SimpleCommandExceptionType NOT_A_SIGN =
		new SimpleCommandExceptionType(Component.literal("You must be looking at a sign"));

	/**
	 * Per-player WorldEdit-style scratch selection ({@code pos1}/{@code pos2}).
	 * Shared with {@link MapCommand} (map spawn regions) and {@link #setLobby}
	 * (the lobby region) - both commit whatever is currently selected here.
	 */
	static final Map<UUID, BlockPos[]> SELECTIONS = new HashMap<>();

	private RecoredCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("recored");

		// --- open to everyone ---
		root.then(teamArg("join", RecoredCommand::join));
		root.then(Commands.literal("leave").executes(RecoredCommand::leave));
		root.then(Commands.literal("status").executes(RecoredCommand::status));

		// --- configuration / control (game masters only) ---
		root.then(Commands.literal("pos1").requires(admin()).executes(c -> setPos(c, 0)));
		root.then(Commands.literal("pos2").requires(admin()).executes(c -> setPos(c, 1)));
		root.then(Commands.literal("setlobby").requires(admin()).executes(RecoredCommand::setLobby));
		root.then(MapCommand.build());
		root.then(Commands.literal("start").requires(admin()).executes(RecoredCommand::start));
		root.then(Commands.literal("reset").requires(admin()).executes(RecoredCommand::reset));
		root.then(Commands.literal("respawndelay").requires(admin())
			.then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0.0))
				.executes(RecoredCommand::setRespawnDelay)));
		root.then(Commands.literal("sign").requires(admin())
			.then(Commands.literal("set")
				.then(Commands.argument("command", StringArgumentType.greedyString())
					.executes(RecoredCommand::signSet)))
			.then(Commands.literal("remove").executes(RecoredCommand::signRemove)));

		dispatcher.register(root);
	}

	static Predicate<CommandSourceStack> admin() {
		return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
	}

	/** Builds {@code <name> <red|blue>} with one executor shared by both literals. */
	static LiteralArgumentBuilder<CommandSourceStack> teamArg(
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
			gm.joinScoreboardTeam(ctx.getSource().getServer(), player, team);
			gm.syncCores(ctx.getSource().getServer()); // refresh their "own core" client sync now that they're on a team
			// Clears their inventory and gives the "Not ready" clay ball - see
			// GameManager#giveReadyItem. Right-clicking it toggles ready, which
			// (once both teams are equal size and everyone's ready) auto-starts
			// a 5s countdown - see GameManager#toggleReady/maybeStartCountdown.
			gm.giveReadyItem(player);
			ctx.getSource().sendSuccess(
				() -> Component.literal("Joined ").append(Component.literal(team.name()).withStyle(team.colour()))
					.append(Component.literal(" team - right-click your clay ball when you're ready")),
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
		gm.leaveScoreboardTeam(ctx.getSource().getServer(), player);
		gm.forgetReady(ctx.getSource().getServer(), player);
		ctx.getSource().sendSuccess(() -> Component.literal("Left your team"), false);
		return Command.SINGLE_SUCCESS;
	}

	/** Attaches a command (run as the clicking player) to the sign the executor is looking at. */
	private static int signSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();
		BlockPos pos = lookedAtBlock(source);
		if (!(player.level().getBlockEntity(pos) instanceof SignBlockEntity)) {
			throw NOT_A_SIGN.create();
		}
		String command = StringArgumentType.getString(ctx, "command");
		SignCommands.INSTANCE.set(player.level(), pos, command);
		MapPersistence.save(source.getServer());
		source.sendSuccess(() -> Component.literal("Sign at " + pos.toShortString() + " now runs: /" + command), true);
		return Command.SINGLE_SUCCESS;
	}

	/** Detaches whatever command is attached to the sign the executor is looking at, if any. */
	private static int signRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();
		BlockPos pos = lookedAtBlock(source);
		if (!SignCommands.INSTANCE.remove(player.level(), pos)) {
			source.sendFailure(Component.literal("That sign has no command attached"));
			return 0;
		}
		MapPersistence.save(source.getServer());
		source.sendSuccess(() -> Component.literal("Removed the command from the sign at " + pos.toShortString()), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int setPos(CommandContext<CommandSourceStack> ctx, int index) throws CommandSyntaxException {
		BlockPos pos = lookedAtBlock(ctx.getSource());
		UUID id = ctx.getSource().getPlayerOrException().getUUID();
		SELECTIONS.computeIfAbsent(id, k -> new BlockPos[2])[index] = pos.immutable();
		ctx.getSource().sendSuccess(() -> Component.literal("pos" + (index + 1) + " = " + pos.toShortString()), false);
		return Command.SINGLE_SUCCESS;
	}

	/** Records the executor's position/facing as the lobby spawn AND commits the current pos1/pos2 selection as the lobby region, in one call. */
	private static int setLobby(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();
		BlockPos[] sel = SELECTIONS.get(player.getUUID());
		if (sel == null || sel[0] == null || sel[1] == null) {
			throw NO_SELECTION.create();
		}
		Vec3 pos = source.getPosition();
		Vec2 rot = source.getRotation();
		GameManager gm = GameManager.INSTANCE;
		gm.lobbySpawn = new Spawn(player.level().dimension(), pos.x, pos.y, pos.z, rot.y, rot.x);
		SpawnRegion region = SpawnRegion.of(sel[0], sel[1]);
		gm.lobbyRegion = region;
		MapPersistence.save(source.getServer());
		source.sendSuccess(() -> Component.literal("Lobby spawn set to " + fmt(pos) + ", region "
			+ region.min().toShortString() + " -> " + region.max().toShortString()), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int start(CommandContext<CommandSourceStack> ctx) {
		GameManager gm = GameManager.INSTANCE;
		if (gm.phase != Phase.WAITING) {
			ctx.getSource().sendFailure(Component.literal("Game is not in WAITING (" + gm.phase + ")"));
			return 0;
		}
		if (!gm.readyToStart()) {
			ctx.getSource().sendFailure(Component.literal(
				"Not ready: need a player on each team and at least one fully-configured map "
					+ "(bounding box, both team spawns, both teams' cores - see /recored map list)"));
			return 0;
		}
		gm.beginStart(ctx.getSource().getServer());
		return Command.SINGLE_SUCCESS;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) {
		GameManager.INSTANCE.reset(ctx.getSource().getServer());
		ctx.getSource().sendSuccess(() -> Component.literal("Recored reset to WAITING"), true);
		return Command.SINGLE_SUCCESS;
	}

	/** Live-adjustable pause between a team member's death and their respawn. Default 1s. */
	private static int setRespawnDelay(CommandContext<CommandSourceStack> ctx) {
		double seconds = DoubleArgumentType.getDouble(ctx, "seconds");
		int ticks = (int) Math.round(seconds * 20.0);
		GameManager.INSTANCE.respawnDelayTicks = ticks;
		ctx.getSource().sendSuccess(
			() -> Component.literal("Respawn delay set to " + ticks + " ticks (" + (ticks / 20.0) + "s)"), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		GameManager gm = GameManager.INSTANCE;
		CommandSourceStack s = ctx.getSource();
		s.sendSuccess(() -> Component.literal("Recored status").withStyle(ChatFormatting.BOLD), false);
		s.sendSuccess(() -> Component.literal("  phase: " + gm.phase), false);
		s.sendSuccess(() -> Component.literal("  respawn delay: " + (gm.respawnDelayTicks / 20.0) + "s"), false);
		s.sendSuccess(() -> Component.literal("  lobby: " + (gm.lobbySpawn != null ? "spawn set" : "NO SPAWN") + ", "
			+ (gm.lobbyRegion != null ? "region set" : "no region")), false);
		String activeMapId = MapRegistry.INSTANCE.activeMap() != null ? MapRegistry.INSTANCE.activeMap().id : "none";
		s.sendSuccess(() -> Component.literal("  maps: " + MapRegistry.INSTANCE.all().size()
			+ " registered, active: " + activeMapId), false);
		for (Team team : Team.values()) {
			long members = gm.players.values().stream().filter(t -> t == team).count();
			// Cores are only populated once RUNNING actually begins - during
			// STARTING (the pre-round ready countdown) nobody's been teleported/
			// kitted yet, so there's nothing meaningful to report there either.
			String line = "  " + team.name() + ": " + members + " player(s)"
				+ (gm.phase == Phase.RUNNING
					? ", " + gm.cores.get(team).size() + " core(s) remaining this round"
					: "");
			s.sendSuccess(() -> Component.literal(line).withStyle(team.colour()), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	// --- helpers ----------------------------------------------------------

	static BlockPos lookedAtBlock(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		HitResult hit = player.pick(RAYCAST_REACH, 1.0F, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			throw NOT_LOOKING_AT_BLOCK.create();
		}
		return ((BlockHitResult) hit).getBlockPos();
	}

	static String fmt(Vec3 v) {
		return String.format("%.1f, %.1f, %.1f", v.x, v.y, v.z);
	}
}
