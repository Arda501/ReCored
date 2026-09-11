package com.example.recored.game;

/**
 * Lifecycle of a single Recored round.
 *
 * <pre>
 * WAITING  -> players may /recored join, ready up, admins configure maps/lobby
 * STARTING -> the 5s ready countdown is running (nobody's teleported yet - see
 *             GameManager#tickCountdown); aborts back to WAITING immediately if
 *             team sizes stop matching or anyone un-readies
 * RUNNING  -> teams teleported + kitted, block-break rules active, cores destructible
 * ENDED    -> a team has lost its last core; brief cleanup, then back to WAITING automatically
 * </pre>
 */
public enum Phase {
	WAITING,
	STARTING,
	RUNNING,
	ENDED
}
