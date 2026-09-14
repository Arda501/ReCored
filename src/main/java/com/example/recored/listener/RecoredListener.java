package com.example.recored.listener;

import com.example.recored.RecoredPlugin;
import com.example.recored.game.GameManager;
import com.example.recored.game.Phase;
import com.example.recored.game.ReadyItem;
import com.example.recored.game.SignCommands;
import com.example.recored.game.Team;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Everything that was a Fabric callback registration or a Mixin in the original mod, as plain
 * Paper events instead:
 *
 * <ul>
 *   <li>{@code CoreMiningMixin} -&gt; {@link #onDamage}/{@link #onAbortDamage} ({@link
 *       BlockDamageEvent}/{@link BlockDamageAbortEvent} give the same start/stop pair the mixin
 *       intercepted {@code handleBlockBreakAction} for) + {@link #onBreak} as the creative-mode
 *       safety net</li>
 *   <li>{@code CoreProtectionMixin} - not needed: that mixin only stopped a vanilla client's own
 *       *local* mining prediction from starting on a core - purely cosmetic, and moot here since
 *       {@link #onDamage} already cancels the server-authoritative attempt outright</li>
 *   <li>{@code PreventReadyItemDropMixin}/{@code PreventReadyItemDropKeyMixin} -&gt; {@link
 *       #onDropItem}</li>
 *   <li>{@code RecoredMod}'s {@code PlayerBlockBreakEvents.BEFORE} -&gt; {@link #onBreak}</li>
 *   <li>{@code RecoredMod}'s {@code UseBlockCallback} -&gt; {@link #onInteract} (core no-interact +
 *       command signs) and {@link #onPlace} (build protection)</li>
 *   <li>{@code RecoredMod}'s {@code UseItemCallback} -&gt; {@link #onInteract} (ready item toggle)</li>
 *   <li>{@code RecoredMod}'s {@code ServerLivingEntityEvents.ALLOW_DAMAGE} -&gt; {@link
 *       #onPvp}</li>
 *   <li>{@code RecoredMod}'s {@code ServerLivingEntityEvents.AFTER_DEATH}/{@code
 *       ServerPlayerEvents.AFTER_RESPAWN} -&gt; {@link #onDeath}/{@link #onRespawn}</li>
 *   <li>{@code RecoredMod}'s {@code ServerPlayConnectionEvents.JOIN}/{@code DISCONNECT} -&gt;
 *       {@link #onJoin}/{@link #onQuit}</li>
 * </ul>
 */
public class RecoredListener implements Listener {

	private final RecoredPlugin plugin;

	public RecoredListener(RecoredPlugin plugin) {
		this.plugin = plugin;
	}

	private GameManager gm() {
		return GameManager.INSTANCE;
	}

	@EventHandler
	public void onDamage(BlockDamageEvent event) {
		GameManager gm = gm();
		Location pos = event.getBlock().getLocation();
		if (gm.phase != Phase.RUNNING || !gm.isCore(pos)) {
			if (gm.isProtected(pos)) {
				event.setCancelled(true);
			}
			return;
		}

		// Never let vanilla accumulate/finish a core break on its own, in either direction - the
		// server decides when a core actually breaks (see GameManager#applyMiningTick).
		event.setCancelled(true);

		Team owner = gm.coreOwnerAt(pos);
		Team minerTeam = gm.teamOf(event.getPlayer().getUniqueId());
		if (owner == minerTeam) {
			event.getPlayer().sendMessage(ChatColor.RED + "You can't mine your own team's core!");
			return;
		}
		if (minerTeam == null) {
			return; // not a rostered player - shouldn't normally reach a core anyway
		}
		gm.startDigging(event.getPlayer(), pos);
	}

	@EventHandler
	public void onAbortDamage(BlockDamageAbortEvent event) {
		GameManager gm = gm();
		Location pos = event.getBlock().getLocation();
		if (gm.phase == Phase.RUNNING && gm.isCore(pos)) {
			gm.stopDigging(event.getPlayer(), pos);
		}
	}

	@EventHandler
	public void onBreak(BlockBreakEvent event) {
		GameManager gm = gm();
		Location pos = event.getBlock().getLocation();

		// Safety net: survival mining of a core never reaches here (onDamage/onAbortDamage handle
		// it), but creative insta-mine can skip straight past those - handle removal ourselves
		// (no item drop) and cancel vanilla's, exactly like RecoredMod's own break-event listener did.
		if (gm.phase == Phase.RUNNING && gm.isCore(pos)) {
			event.setCancelled(true);
			Team owner = gm.coreOwnerAt(pos);
			Team breakerTeam = gm.teamOf(event.getPlayer().getUniqueId());
			if (owner != breakerTeam) {
				gm.breakCore(pos, event.getPlayer());
			}
			return;
		}

		if (event.getPlayer().getGameMode() != GameMode.CREATIVE && gm.isProtected(pos)) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onPlace(BlockPlaceEvent event) {
		if (event.getPlayer().getGameMode() != GameMode.CREATIVE && gm().isProtected(event.getBlock().getLocation())) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onInteract(PlayerInteractEvent event) {
		Player p = event.getPlayer();
		GameManager gm = gm();

		// Ready item: works everywhere (air or block), not just in the lobby specifically - toggling
		// only matters pre-round anyway since it's cleared/replaced by the kit once RUNNING starts.
		if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
				&& ReadyItem.isReadyItem(plugin, event.getItem())) {
			event.setCancelled(true);
			gm.toggleReady(p);
			return;
		}

		if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !event.hasBlock()) {
			return;
		}
		Block block = event.getClickedBlock();
		Location pos = block.getLocation();

		// No interaction with core beacons at all - not the beam/effect GUI, nothing - regardless
		// of round phase.
		if (gm.isCore(pos)) {
			event.setCancelled(true);
			return;
		}

		// A command sign runs its attached command as the clicking player; the sign's own text is
		// whatever an admin wrote with vanilla sign editing.
		if (block.getState() instanceof Sign) {
			String command = SignCommands.INSTANCE.get(pos);
			if (command != null) {
				event.setCancelled(true);
				Bukkit.dispatchCommand(p, command);
			}
		}
	}

	@EventHandler
	public void onPvp(EntityDamageByEntityEvent event) {
		if (!(event.getEntity() instanceof Player victim)) {
			return;
		}
		if (!(event.getDamager() instanceof Player attacker) || attacker.equals(victim)) {
			return; // not player-inflicted damage - not ours to gate
		}
		GameManager gm = gm();
		Team victimTeam = gm.teamOf(victim.getUniqueId());
		Team attackerTeam = gm.teamOf(attacker.getUniqueId());
		if (victimTeam == null && attackerTeam == null) {
			// neither player has anything to do with Recored (e.g. they're playing some other
			// minigame entirely) - not ours to touch, at all, regardless of Recored's own phase
			return;
		}
		if (gm.phase != Phase.RUNNING || victimTeam == null || attackerTeam == null) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		Player p = event.getEntity();
		GameManager gm = gm();
		Team team = gm.teamOf(p.getUniqueId());
		if (gm.phase == Phase.RUNNING && team != null) {
			gm.beginRespawnDelay(p);
		}
	}

	@EventHandler
	public void onRespawn(PlayerRespawnEvent event) {
		Player p = event.getPlayer();
		GameManager gm = gm();
		Team team = gm.teamOf(p.getUniqueId());
		if (team == null) {
			return; // not a Recored participant - their respawn location is none of Recored's business
		}
		if (gm.phase == Phase.RUNNING) {
			Location spawn = gm.spawns.get(team);
			if (spawn != null) {
				event.setRespawnLocation(spawn);
			}
			// Deferred a tick: the respawn itself isn't fully applied yet at this point in the event.
			Bukkit.getScheduler().runTask(plugin, () -> gm.giveKit(p, team));
		} else if (gm.lobbySpawn != null) {
			event.setRespawnLocation(gm.lobbySpawn);
		}
	}

	@EventHandler
	public void onDropItem(PlayerDropItemEvent event) {
		if (ReadyItem.isReadyItem(plugin, event.getItemDrop().getItemStack())) {
			event.setCancelled(true);
		}
	}

	/** Command names allowed for a rostered player while a round is RUNNING, besides admins (who are never restricted). */
	private static final java.util.Set<String> ALLOWED_COMMANDS_IN_ROUND = java.util.Set.of("help", "matrix");

	@EventHandler
	public void onCommand(PlayerCommandPreprocessEvent event) {
		Player p = event.getPlayer();
		if (p.hasPermission("recored.admin")) {
			return;
		}
		GameManager gm = gm();
		if (gm.phase != Phase.RUNNING || gm.teamOf(p.getUniqueId()) == null) {
			return;
		}
		String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase();
		int colon = label.indexOf(':'); // strip a "plugin:command" prefix, e.g. "recored:help"
		if (colon >= 0) {
			label = label.substring(colon + 1);
		}
		if (!ALLOWED_COMMANDS_IN_ROUND.contains(label)) {
			event.setCancelled(true);
			p.sendMessage(ChatColor.RED + "Commands are disabled during a round.");
		}
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		// Only a player who was already a rostered Recored participant before this connection
		// (i.e. reconnecting mid-round - WAITING/STARTING already drops their roster entry on
		// disconnect, see GameManager#handleDisconnect) gets auto-repositioned. Everyone else -
		// including a brand new join, or anyone playing something else entirely, like BowBash -
		// is left wherever the server naturally puts them.
		Player p = event.getPlayer();
		GameManager gm = gm();
		Team team = gm.teamOf(p.getUniqueId());
		if (team == null) {
			return;
		}
		if (gm.phase == Phase.RUNNING) {
			gm.teleportToSpawn(p, team);
		} else {
			gm.sendToLobby(p);
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		Player p = event.getPlayer();
		gm().clearDigging(p.getUniqueId());
		gm().handleDisconnect(p);
	}
}
