package com.example.recored;

import com.example.recored.game.GameManager;
import com.example.recored.game.MapRegistry;
import com.example.recored.game.Phase;
import com.example.recored.game.Team;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI placeholders for Recored's live round state - lets any PAPI-aware plugin (a sign
 * plugin like SignManager, a scoreboard plugin, chat formatting, ...) display current stats
 * without needing to know anything about Recored itself. Registers only if PlaceholderAPI is
 * actually installed (a soft dependency - see {@code RecoredPlugin#onEnable}).
 *
 * <pre>
 * %recored_phase%            WAITING / STARTING / RUNNING / ENDED
 * %recored_time%              "M:SS" elapsed since the round went RUNNING, or "-" otherwise
 * %recored_active_map%       the map id currently being played, or "-" outside a round
 * %recored_red_players%      players on the red team right now
 * %recored_blue_players%     players on the blue team right now
 * %recored_ready%            how many rostered players are ready, e.g. "3/4" (WAITING/STARTING only)
 * %recored_countdown%        seconds left in the ready countdown, or "-" if not STARTING
 * %recored_red_cores%        red team's cores still standing (only meaningful while RUNNING)
 * %recored_blue_cores%       blue team's cores still standing (only meaningful while RUNNING)
 * %recored_respawn_delay%    current respawn delay, in seconds
 * %recored_coretime%         current core mining time, in seconds
 * </pre>
 */
public class RecoredPlaceholders extends PlaceholderExpansion {

	private final RecoredPlugin plugin;

	public RecoredPlaceholders(RecoredPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public @NotNull String getIdentifier() {
		return "recored";
	}

	@Override
	public @NotNull String getAuthor() {
		return "InstanceLabs";
	}

	@Override
	public @NotNull String getVersion() {
		return plugin.getDescription().getVersion();
	}

	/** Keeps this expansion registered across a PlaceholderAPI /papi reload instead of needing it re-registered. */
	@Override
	public boolean persist() {
		return true;
	}

	@Override
	public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
		GameManager gm = GameManager.INSTANCE;
		return switch (params.toLowerCase()) {
			case "phase" -> gm.phase.name();
			case "time" -> gm.phase == Phase.RUNNING ? gm.elapsedTimeFormatted() : "-";
			case "active_map" -> {
				var active = MapRegistry.INSTANCE.activeMap();
				yield active != null ? active.id : "-";
			}
			case "red_players" -> String.valueOf(countTeam(gm, Team.RED));
			case "blue_players" -> String.valueOf(countTeam(gm, Team.BLUE));
			case "ready" -> gm.phase == Phase.WAITING || gm.phase == Phase.STARTING
					? gm.readyCount() + "/" + gm.players.size()
					: "-";
			case "countdown" -> {
				int seconds = gm.countdownSecondsRemaining();
				yield seconds < 0 ? "-" : String.valueOf(seconds);
			}
			case "red_cores" -> gm.phase == Phase.RUNNING ? String.valueOf(gm.cores.get(Team.RED).size()) : "-";
			case "blue_cores" -> gm.phase == Phase.RUNNING ? String.valueOf(gm.cores.get(Team.BLUE).size()) : "-";
			case "respawn_delay" -> String.valueOf(gm.respawnDelayTicks / 20.0);
			case "coretime" -> String.valueOf(gm.coreMiningSeconds);
			default -> null;
		};
	}

	private int countTeam(GameManager gm, Team team) {
		int n = 0;
		for (Team t : gm.players.values()) {
			if (t == team) {
				n++;
			}
		}
		return n;
	}
}
