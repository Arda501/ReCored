package com.example.cores.game;

/**
 * Lifecycle of a single Cores round.
 *
 * <pre>
 * WAITING  -> players may /cores join, admins configure spawns/cores
 * STARTING -> teams teleported + kitted, countdown running (server-tick scheduled)
 * RUNNING  -> block-break rules active, cores destructible
 * ENDED    -> a team has lost its last core; waiting for /cores reset
 * </pre>
 */
public enum Phase {
	WAITING,
	STARTING,
	RUNNING,
	ENDED
}
