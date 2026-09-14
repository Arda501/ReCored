package com.example.recored.game;

import java.util.Locale;

import org.bukkit.ChatColor;
import org.bukkit.scoreboard.DisplaySlot;

/**
 * The two teams in a Recored round. Kept deliberately tiny - just an id plus a bit of
 * presentation so command feedback, broadcasts and the per-team sidebar can be colour-coded.
 */
public enum Team {
	RED(ChatColor.RED),
	BLUE(ChatColor.BLUE);

	private final ChatColor colour;

	Team(ChatColor colour) {
		this.colour = colour;
	}

	public ChatColor colour() {
		return colour;
	}

	public Team opposite() {
		return this == RED ? BLUE : RED;
	}

	/** Lower-case name, used as the literal command argument (`/recored join red`). */
	public String lowerName() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** The vanilla sidebar slot that only this team's own scoreboard-team members ever see. */
	public DisplaySlot sidebarSlot() {
		return this == RED ? DisplaySlot.SIDEBAR_TEAM_RED : DisplaySlot.SIDEBAR_TEAM_BLUE;
	}
}
