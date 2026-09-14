package com.example.recored;

import com.example.recored.command.RecoredCommand;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point. Minimal so far - just enough scaffolding to register {@code /recored} and let
 * admins define + save a map's reset baseline (see {@link RecoredCommand}). The full round
 * engine (GameManager, gameplay listeners, HUD, etc.) isn't wired in yet.
 */
public class RecoredPlugin extends JavaPlugin {

	@Override
	public void onEnable() {
		RecoredCommand command = new RecoredCommand(this);
		getCommand("recored").setExecutor(command);
		getCommand("recored").setTabCompleter(command);
		getLogger().info("Recored enabled.");
	}
}
