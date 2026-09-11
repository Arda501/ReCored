package com.example.recored.command;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

import com.example.recored.game.Kit;
import com.example.recored.game.MapConfig;
import com.example.recored.game.MapPersistence;
import com.example.recored.game.MapRegistry;
import com.example.recored.game.Side;
import com.example.recored.game.Spawn;
import com.example.recored.game.SpawnRegion;
import com.example.recored.game.Team;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /recored map ...} - registers and configures {@link MapConfig}s.
 * Every subcommand takes an explicit {@code <id>} (no "map currently being
 * edited" state to lose track of). Reuses {@link RecoredCommand}'s raycast
 * helper, admin gate, pos1/pos2 selection and exception types - same package,
 * package-private access.
 */
final class MapCommand {

	private static final DynamicCommandExceptionType NO_SUCH_MAP =
		new DynamicCommandExceptionType(id -> Component.literal("No such map: " + id));

	private MapCommand() {
	}

	static LiteralArgumentBuilder<CommandSourceStack> build() {
		LiteralArgumentBuilder<CommandSourceStack> map = Commands.literal("map").requires(RecoredCommand.admin());

		map.then(idArg("create", MapCommand::create));
		map.then(idArg("corner1", ctx -> corner(ctx, 0)));
		map.then(idArg("corner2", ctx -> corner(ctx, 1)));
		map.then(idTeamArg("setspawn", MapCommand::setSpawn));
		map.then(idTeamSideArg("addcore", MapCommand::addCore));
		map.then(idTeamSideArg("removecore", MapCommand::removeCore));
		map.then(idTeamArg("setregion", MapCommand::setRegion));
		map.then(idTeamArg("setkit", MapCommand::setKit));
		map.then(idArg("save", MapCommand::save));
		map.then(idArg("delete", MapCommand::delete));
		map.then(Commands.literal("list").executes(MapCommand::list));

		return map;
	}

	/** {@code /recored map <name> <id>}. */
	private static LiteralArgumentBuilder<CommandSourceStack> idArg(String name, Command<CommandSourceStack> executor) {
		return Commands.literal(name).then(Commands.argument("id", StringArgumentType.word()).executes(executor));
	}

	/** {@code /recored map <name> <id> <red|blue>}, one executor shared by both team literals. */
	private static LiteralArgumentBuilder<CommandSourceStack> idTeamArg(
		String name, Function<Team, Command<CommandSourceStack>> executor) {
		RequiredArgumentBuilder<CommandSourceStack, String> idArg = Commands.argument("id", StringArgumentType.word());
		for (Team team : Team.values()) {
			idArg.then(Commands.literal(team.lowerName()).executes(executor.apply(team)));
		}
		return Commands.literal(name).then(idArg);
	}

	/**
	 * {@code /recored map <name> <id> <red|blue> <l|r>} - each team tracks its
	 * own left AND right core independently (up to four cores total on a
	 * map: RED left, RED right, BLUE left, BLUE right), so both which team
	 * and which side have to be specified. Used by {@code addcore}/{@code
	 * removecore} specifically.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> idTeamSideArg(
		String name, BiFunction<Team, Side, Command<CommandSourceStack>> executor) {
		RequiredArgumentBuilder<CommandSourceStack, String> idArg = Commands.argument("id", StringArgumentType.word());
		for (Team team : Team.values()) {
			LiteralArgumentBuilder<CommandSourceStack> teamNode = Commands.literal(team.lowerName());
			for (Side side : Side.values()) {
				teamNode.then(Commands.literal(side.argName()).executes(executor.apply(team, side)));
			}
			idArg.then(teamNode);
		}
		return Commands.literal(name).then(idArg);
	}

	private static String id(CommandContext<CommandSourceStack> ctx) {
		return StringArgumentType.getString(ctx, "id");
	}

	private static MapConfig requireMap(String id) throws CommandSyntaxException {
		MapConfig map = MapRegistry.INSTANCE.get(id);
		if (map == null) {
			throw NO_SUCH_MAP.create(id);
		}
		return map;
	}

	// --- subcommand handlers -------------------------------------------------

	private static int create(CommandContext<CommandSourceStack> ctx) {
		String id = id(ctx);
		if (MapRegistry.INSTANCE.exists(id)) {
			ctx.getSource().sendFailure(Component.literal("Map '" + id + "' already exists"));
			return 0;
		}
		MapRegistry.INSTANCE.create(id);
		MapPersistence.save(ctx.getSource().getServer());
		ctx.getSource().sendSuccess(() -> Component.literal("Created map '" + id
			+ "' - now set corner1/corner2, setspawn, addcore and setregion for each team, then map save"), true);
		return Command.SINGLE_SUCCESS;
	}

	private static int corner(CommandContext<CommandSourceStack> ctx, int index) throws CommandSyntaxException {
		String id = id(ctx);
		MapConfig map = requireMap(id);
		BlockPos pos = RecoredCommand.lookedAtBlock(ctx.getSource()).immutable();
		if (index == 0) {
			map.corner1 = pos;
		} else {
			map.corner2 = pos;
		}
		MapPersistence.save(ctx.getSource().getServer());
		ctx.getSource().sendSuccess(() -> Component.literal(id + " corner" + (index + 1) + " = " + pos.toShortString()), false);
		return Command.SINGLE_SUCCESS;
	}

	private static Command<CommandSourceStack> setSpawn(Team team) {
		return ctx -> {
			String id = id(ctx);
			MapConfig map = requireMap(id);
			CommandSourceStack source = ctx.getSource();
			ServerPlayer player = source.getPlayerOrException();
			Vec3 pos = source.getPosition();
			Vec2 rot = source.getRotation();
			map.teamSpawns.put(team, new Spawn(player.level().dimension(), pos.x, pos.y, pos.z, rot.y, rot.x));
			MapPersistence.save(source.getServer());
			source.sendSuccess(() -> Component.literal(id + " " + team.name() + " spawn set to " + RecoredCommand.fmt(pos)), false);
			return Command.SINGLE_SUCCESS;
		};
	}

	private static Command<CommandSourceStack> addCore(Team team, Side side) {
		return ctx -> {
			String id = id(ctx);
			MapConfig map = requireMap(id);
			BlockPos pos = RecoredCommand.lookedAtBlock(ctx.getSource()).immutable();
			boolean alreadyCoreElsewhere = map.cores.values().stream().anyMatch(sides -> sides.containsValue(pos));
			if (alreadyCoreElsewhere) {
				ctx.getSource().sendFailure(Component.literal("That block is already a core on this map"));
				return 0;
			}
			map.cores.get(team).put(side, pos);
			MapPersistence.save(ctx.getSource().getServer());
			ctx.getSource().sendSuccess(() -> Component.literal("Set " + id + " " + team.name() + " " + side.label()
				+ " core at " + pos.toShortString()), false);
			return Command.SINGLE_SUCCESS;
		};
	}

	private static Command<CommandSourceStack> removeCore(Team team, Side side) {
		return ctx -> {
			String id = id(ctx);
			MapConfig map = requireMap(id);
			BlockPos removed = map.cores.get(team).remove(side);
			if (removed != null) {
				MapPersistence.save(ctx.getSource().getServer());
				ctx.getSource().sendSuccess(() -> Component.literal("Removed " + id + " " + team.name() + " " + side.label()
					+ " core (was at " + removed.toShortString() + ")"), false);
				return Command.SINGLE_SUCCESS;
			}
			ctx.getSource().sendFailure(Component.literal("No " + team.name() + " " + side.label() + " core set on " + id));
			return 0;
		};
	}

	private static Command<CommandSourceStack> setRegion(Team team) {
		return ctx -> {
			String id = id(ctx);
			MapConfig map = requireMap(id);
			UUID uuid = ctx.getSource().getPlayerOrException().getUUID();
			BlockPos[] sel = RecoredCommand.SELECTIONS.get(uuid);
			if (sel == null || sel[0] == null || sel[1] == null) {
				throw RecoredCommand.NO_SELECTION.create();
			}
			SpawnRegion region = SpawnRegion.of(sel[0], sel[1]);
			map.spawnRegions.put(team, region);
			MapPersistence.save(ctx.getSource().getServer());
			ctx.getSource().sendSuccess(() -> Component.literal(id + " " + team.name() + " spawn region set: "
				+ region.min().toShortString() + " -> " + region.max().toShortString()), false);
			return Command.SINGLE_SUCCESS;
		};
	}

	private static Command<CommandSourceStack> setKit(Team team) {
		return ctx -> {
			String id = id(ctx);
			MapConfig map = requireMap(id);
			ServerPlayer player = ctx.getSource().getPlayerOrException();
			Inventory inv = player.getInventory();
			Map<Integer, ItemStack> slots = new HashMap<>();
			for (int slot = 0; slot < inv.getContainerSize(); slot++) {
				ItemStack stack = inv.getItem(slot);
				if (!stack.isEmpty()) {
					slots.put(slot, stack.copy());
				}
			}
			map.kits.put(team, new Kit(slots, inv.getSelectedSlot()));
			MapPersistence.save(ctx.getSource().getServer());
			ctx.getSource().sendSuccess(() -> Component.literal("Captured " + slots.size()
				+ " item stack(s) as the " + id + " " + team.name() + " kit"), false);
			return Command.SINGLE_SUCCESS;
		};
	}

	private static int save(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String id = id(ctx);
		MapConfig map = requireMap(id);
		if (!map.hasBoundingBox()) {
			ctx.getSource().sendFailure(Component.literal("Set corner1 and corner2 first"));
			return 0;
		}
		boolean ok = map.saveStructure(ctx.getSource().getServer());
		if (ok) {
			SpawnRegion box = map.bounds();
			ctx.getSource().sendSuccess(() -> Component.literal("Saved " + id + " reset baseline: "
				+ box.min().toShortString() + " -> " + box.max().toShortString()), true);
			return Command.SINGLE_SUCCESS;
		}
		ctx.getSource().sendFailure(Component.literal("Failed to save " + id + " - see server log"));
		return 0;
	}

	private static int delete(CommandContext<CommandSourceStack> ctx) {
		String id = id(ctx);
		if (MapRegistry.INSTANCE.remove(id)) {
			MapPersistence.save(ctx.getSource().getServer());
			ctx.getSource().sendSuccess(() -> Component.literal("Deleted map '" + id + "'"), true);
			return Command.SINGLE_SUCCESS;
		}
		ctx.getSource().sendFailure(Component.literal("No such map (or it's the active one - finish/reset the round first): " + id));
		return 0;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		Collection<MapConfig> maps = MapRegistry.INSTANCE.all();
		MapConfig active = MapRegistry.INSTANCE.activeMap();
		source.sendSuccess(() -> Component.literal("Maps (" + maps.size() + ")").withStyle(ChatFormatting.BOLD), false);
		if (maps.isEmpty()) {
			source.sendSuccess(() -> Component.literal("  none - /recored map create <id>").withStyle(ChatFormatting.GRAY), false);
		}
		for (MapConfig map : maps) {
			boolean isActive = map == active;
			String line = "  " + map.id + (isActive ? " [ACTIVE]" : "") + " - "
				+ (map.hasBoundingBox() ? "box set" : "NO BOX") + ", "
				+ describeTeam(map, Team.RED) + ", " + describeTeam(map, Team.BLUE)
				+ " - " + (map.isReadyToPlay() ? "READY" : "NOT READY");
			ChatFormatting colour = map.isReadyToPlay() ? ChatFormatting.GREEN : ChatFormatting.RED;
			source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static String describeTeam(MapConfig map, Team team) {
		Map<Side, BlockPos> sides = map.cores.get(team);
		String coreSummary = "L" + (sides.containsKey(Side.LEFT) ? "✓" : "✗") + " R" + (sides.containsKey(Side.RIGHT) ? "✓" : "✗");
		return team.name() + ": " + (map.teamSpawns.containsKey(team) ? "spawn✓" : "NO SPAWN")
			+ " " + coreSummary + ", "
			+ (map.spawnRegions.containsKey(team) ? "region✓" : "no region") + ", "
			+ (map.kits.get(team).slots().isEmpty() ? "no kit" : map.kits.get(team).slots().size() + "-item kit");
	}
}
