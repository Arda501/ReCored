# Cores

A two-team objective gamemode for Minecraft **26.2** (Fabric Loader + Fabric API, Java 25).

Each team (RED / BLUE) defends one or more **core beacons**. Cores are hardened to
obsidian mining time (50.0F vs the vanilla beacon's 3.0F). When a team's last core
is broken, the other team wins.

## Setup

```bash
./gradlew genSources   # readable Minecraft source (Yarn mappings)
./gradlew build        # -> build/libs/cores-1.0.0.jar
./gradlew runServer    # dev server
./gradlew runClient    # dev client
```

The mod is `environment: "*"` and **must be installed on clients too** – the beacon
hardness mixin is shared code, and clients need core positions (synced via a custom
payload) for block-break prediction to match the server.

## Architecture

| Piece | File |
|-------|------|
| Mutable round state (singleton) | `game/GameManager.java` |
| Team / phase enums | `game/Team.java`, `game/Phase.java` |
| Spawn-protection box | `game/SpawnRegion.java` |
| `/cores` commands | `command/CoresCommand.java` |
| Block-break rules + wiring | `CoresMod.java` |
| Beacon hardness mixin | `mixin/BeaconHardnessMixin.java` |
| Client-sync payload | `net/CoreSyncPayload.java`, `client/CoresModClient.java` |

Round lifecycle: `WAITING → STARTING (5s server-tick countdown) → RUNNING → ENDED`.

## Commands

Open to everyone:

| Command | Effect |
|---------|--------|
| `/cores join <red\|blue>` | Join a team (WAITING only) |
| `/cores leave` | Leave your team (WAITING only) |
| `/cores status` | Print phase, team sizes, spawns, cores, kits |

Game masters (`Commands.LEVEL_GAMEMASTERS`, i.e. `/op`-level) – configuration & control:

| Command | Effect |
|---------|--------|
| `/cores setspawn <red\|blue>` | Record your position/rotation as that team's spawn |
| `/cores addcore <red\|blue>` | Register the block you're looking at as a core |
| `/cores removecore <red\|blue>` | Unregister the core you're looking at |
| `/cores pos1` / `/cores pos2` | Select a spawn-protection region (WorldEdit-style) |
| `/cores setregion <red\|blue>` | Commit the current pos1/pos2 selection to a team |
| `/cores setkit <red\|blue>` | Capture your current inventory as that team's kit |
| `/cores start` | Begin the round (needs ≥1 spawn, ≥1 core, ≥1 player per team) |
| `/cores reset` | Wipe all state back to WAITING |

## Rules (while RUNNING)

* A registered core is always breakable, even inside a spawn region.
* Breaking a team's **last** core ends the game for the other team.
* Any other block inside a spawn region cannot be broken.
* Outside `RUNNING`, no break restrictions apply.

## License

CC0-1.0.
