package com.example.recored;

import com.example.recored.command.RecoredCommand;
import com.example.recored.game.GameManager;
import com.example.recored.game.MapPersistence;
import com.example.recored.listener.RecoredListener;

import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for the "Recored" team-core-destruction gamemode: a standalone Paper port of the
 * user's fabric-example-mod-26.2 mod of the same name. See {@link GameManager} for the full
 * design notes and what was deliberately dropped in the port (the client-side core-highlight
 * networking sync, which needed a companion client mod).
 *
 * <p>Entirely independent of the BowBash plugin - different command ({@code /recored} vs
 * {@code /bb}), different permission root ({@code recored.*} vs {@code bowbash.*}), different
 * package, different data folder. The two can be installed on the same server without any
 * conflict.
 */
public class RecoredPlugin extends JavaPlugin {

	/** How often (in ticks) {@link GameManager#tick()} runs. */
	private static final int TICK_INTERVAL = 1;

	@Override
	public void onEnable() {
		for (World world : Bukkit.getWorlds()) {
			world.setGameRule(GameRules.KEEP_INVENTORY, true);
			world.setGameRule(GameRules.IMMEDIATE_RESPAWN, false);
		}

		GameManager.INSTANCE.init(this);
		GameManager.INSTANCE.setupHud();
		MapPersistence.load(this);

		Bukkit.getPluginManager().registerEvents(new RecoredListener(this), this);

		RecoredCommand command = new RecoredCommand(this);
		getCommand("recored").setExecutor(command);
		getCommand("recored").setTabCompleter(command);

		Bukkit.getScheduler().runTaskTimer(this, GameManager.INSTANCE::tick, TICK_INTERVAL, TICK_INTERVAL);

		getLogger().info("Recored enabled.");
	}

	@Override
	public void onDisable() {
		if (GameManager.INSTANCE.phase != com.example.recored.game.Phase.WAITING) {
			GameManager.INSTANCE.reset();
		}
		MapPersistence.save(this);
	}
}
