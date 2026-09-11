package com.example.recored.game;

/**
 * The left/right core slot <em>within a team's own base</em> - independent
 * of {@link Team}. Each team has its own left core and its own right core
 * (see {@code MapConfig#cores}: {@code Map<Team, Map<Side, BlockPos>>}), so
 * a map can have up to four cores total: RED left, RED right, BLUE left,
 * BLUE right. {@code /recored map addcore <id> <red|blue> <l|r>} sets one.
 */
public enum Side {
	LEFT,
	RIGHT;

	public String label() {
		return this == LEFT ? "Left" : "Right";
	}

	/** Lower-case command-argument literal (`l`/`r`). */
	public String argName() {
		return this == LEFT ? "l" : "r";
	}

	public static Side fromArg(String arg) {
		return "l".equalsIgnoreCase(arg) ? LEFT : RIGHT;
	}
}
