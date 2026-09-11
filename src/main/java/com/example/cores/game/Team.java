package com.example.cores.game;

import net.minecraft.ChatFormatting;

/**
 * The two teams in a Cores round. Kept deliberately tiny - just an id plus a bit
 * of presentation so command feedback and broadcasts can be colour-coded.
 */
public enum Team {
	RED(ChatFormatting.RED),
	BLUE(ChatFormatting.BLUE);

	private final ChatFormatting colour;

	Team(ChatFormatting colour) {
		this.colour = colour;
	}

	public ChatFormatting colour() {
		return colour;
	}

	public Team opposite() {
		return this == RED ? BLUE : RED;
	}

	/** Lower-case name, used as the literal command argument (`/cores join red`). */
	public String lowerName() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
