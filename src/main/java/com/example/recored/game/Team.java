package com.example.recored.game;

import net.minecraft.ChatFormatting;
import net.minecraft.world.scores.TeamColor;

/**
 * The two teams in a Recored round. Kept deliberately tiny - just an id plus a
 * bit of presentation so command feedback and broadcasts can be colour-coded.
 */
public enum Team {
	RED(ChatFormatting.RED, TeamColor.RED),
	BLUE(ChatFormatting.BLUE, TeamColor.BLUE);

	private final ChatFormatting colour;
	private final TeamColor teamColor;

	Team(ChatFormatting colour, TeamColor teamColor) {
		this.colour = colour;
		this.teamColor = teamColor;
	}

	public ChatFormatting colour() {
		return colour;
	}

	/** Colour used for the vanilla scoreboard team backing this team (tab list, nametag). */
	public TeamColor teamColor() {
		return teamColor;
	}

	public Team opposite() {
		return this == RED ? BLUE : RED;
	}

	/** Lower-case name, used as the literal command argument (`/recored join red`). */
	public String lowerName() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
