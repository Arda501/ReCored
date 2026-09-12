# Recored

A two-team objective gamemode for Minecraft **26.2** (Fabric Loader + Fabric API, Java 25).

Each team (RED / BLUE) defends one or more **core beacons**, mineable only by the
*other* team, at standard vanilla beacon speed. Mining progress shows above your
hotbar while you're digging, and a persistent sidebar HUD tracks every one of
your team's cores' health at all times - flashing and chiming when an enemy
gets close. When a team's last core is broken, the other team wins (with some
fireworks) - the map resets itself and everyone lands back in the lobby, ready
for a rematch (possibly on a different map).

Supports **multiple registered maps**, picked round-robin each round, each with
its own bounding box, spawns, cores, protection regions and kits, and its own
saved "reset baseline" that gets reloaded after every round - plus a **lobby**
(a fixed spawn + protected region, entirely separate from any map) where players
land on join and whenever no round is live.

Join discusson at https://matrix.to/#/#goodoldmc:tchncs.de
Play at IP: goodoldmc.com

## Setup

```bash
./gradlew genSources   # readable Minecraft source (Yarn mappings)
./gradlew build        # -> build/libs/recored-1.0.0.jar
./gradlew runServer    # dev server
./gradlew runClient    # dev client
```
Designed to run **server-only** - players connect with a plain vanilla
client, nothing to install. Core mining specifically is built to work
correctly against an unmodified client (see "Core mining" below for how);
installing the mod client-side too is entirely optional and only adds one
small cosmetic - your own team's core visually refuses to even start mining
(`CoreProtectionMixin`) instead of the crack overlay just doing nothing.

## Architecture

| Piece | File |
|-------|------|
| Mutable *round* state (singleton) - live working copy of whichever map is active, plus the lobby | `game/GameManager.java` |
| A registered map's blueprint: box, spawns, cores, regions, kits + save/reset | `game/MapConfig.java` |
| Holds every `MapConfig`, picks the next one round-robin | `game/MapRegistry.java` |
| Precise teleport target (pos + rotation + dimension) | `game/Spawn.java` |
| Captured inventory (exact slots + selected hotbar slot) | `game/Kit.java` |
| Team / phase enums | `game/Team.java`, `game/Phase.java` |
| Min/max bounding box (spawn regions, map corners, lobby region) | `game/SpawnRegion.java` |
| `/recored` commands (join/leave/status/pos1/pos2/setlobby/start/reset/respawndelay) | `command/RecoredCommand.java` |
| `/recored map ...` commands | `command/MapCommand.java` |
| Block-break rules + death/respawn/join wiring | `RecoredMod.java` |
| Persistent core-mining mixin | `mixin/CoreMiningMixin.java` |
| "Can't touch your own core" mixin (client + server) | `mixin/CoreProtectionMixin.java` |
| Client-sync payload | `net/CoreSyncPayload.java`, `client/RecoredModClient.java` |
| Saves/loads every registered map, the lobby, and command signs to disk | `game/MapPersistence.java` |
| Admin-attached "command sign" registry | `game/SignCommands.java` |
| Makes the ready-up item un-droppable (inventory-screen paths) | `mixin/PreventReadyItemDropMixin.java` |
| Makes the ready-up item un-droppable (the Q/Ctrl+Q drop key) | `mixin/PreventReadyItemDropKeyMixin.java` |

Round lifecycle: `WAITING → STARTING (5s ready countdown, nobody teleported yet) → RUNNING (teleported + kitted) → ENDED (cleanup) → WAITING`.

### Maps & lobby

A `MapConfig` is a reusable blueprint - `/recored map ...` commands only ever
configure it, never live round state. `GameManager.spawns`/`cores`/
`spawnRegions`/`kits` are a *working copy*, populated fresh from the map
`MapRegistry.activateNext()` picks (round-robin, skipping any map missing a
bounding box, either team's spawn, or either team's cores) each time
`/recored start` runs, and mutated during play (cores get removed as they're
broken). The blueprint itself is never touched, so the same map plays the same
way every time.

`/recored map save <id>` snapshots every block currently inside the map's
`corner1`/`corner2` box as a world-generated structure (via
`StructureTemplateManager`, saved under `<world>/generated/recored/structure/maps/<id>.nbt`)
- that's the baseline restored after each round. Resetting doesn't touch
anything outside the box.

The **lobby** (`/recored setlobby`) is a single spawn point + protected region,
entirely separate from any map. Players land there on join and are sent back
there (in Adventure mode) at the end of every round - see `GameManager.cleanupRound`,
shared by a natural win (`endGame`) and a manual `/recored reset`. Spawn
protection follows whichever place is actually relevant: the lobby region
while no round is RUNNING, the active map's team spawn regions while one is -
and covers both breaking (`PlayerBlockBreakEvents.BEFORE`) and building
(block/bucket placement, via the sign-click `UseBlockCallback` in
`RecoredMod`), both driven by the same `GameManager#isProtected`.

### Ready-up & auto-start

Joining a team (`/recored join`) clears your inventory and gives you a single
item: a clay ball named **"Not ready"** (red, bold). Right-clicking it swaps
it for a slime ball named **"Ready"** (green, bold) - and right-clicking
*that* swaps it back. This item can't be dropped - two mixins cover it, since
vanilla has two separate drop paths: `PreventReadyItemDropKeyMixin` cancels
the Q/Ctrl+Q drop key at its true source (`ServerPlayer#drop(boolean)`, which
removes the item from the inventory itself before anything else runs), and
`PreventReadyItemDropMixin` covers the other paths (closing an inventory
screen with it on your cursor, throwing it by clicking outside the window -
both go through `Player#drop(ItemStack, boolean)`). If it's ever moved out of
its slot some other way, it also snaps back within a fraction of a second
(`GameManager.enforceReadyItems`, running a few times a second while a round
isn't live) - it's identified by a hidden data-component marker, not just its
item type/name, so it can't be spoofed or confused with a real clay/slime ball.

Whenever a player readies up (or leaves/disconnects, which can also fix things),
`GameManager.maybeStartCountdown` checks, in order: are both teams non-empty
**and** exactly equal in size (if not, everyone gets a chat message saying
so); is *everyone* currently on a team ready (if not, waits quietly - no
message, since each player's own item already shows this); and is there a
fully-configured map registered (if not, everyone gets a chat message saying
so - **this is probably why nothing happens if you're both green and ready
and it still won't start**: check `/recored map list` - a map needs a
bounding box, both teams' spawns, and at least one core, either side, per
team). Once all three hold, a 5-second countdown starts - a number in chat
plus an audible tick every second, heard by every connected player regardless
of where they are. Nobody is teleported during the countdown; if at any point
during it someone un-readies, disconnects, or the team sizes stop matching,
it's cancelled immediately back to `WAITING` with a chat message. Only once
it completes with everything still holding does the round actually begin
(teleport, kits, survival mode, cores live).

This auto-start is the primary way a round begins - `/recored start` still
exists as a game-master-only **instant override** (still requires a player on
each team and a ready map, but skips the whole ready/countdown system
entirely, going live immediately - useful for solo testing). When a round
ends (win or `/recored reset`), everyone is fully removed from their team -
see "Round end" below - so a rematch means everyone runs `/recored join`
again.

### Persistence

Everything above - every `MapConfig` (id, corners, spawns, cores, regions,
kits) and the lobby (spawn + region) - is otherwise **pure in-memory Java
state**. The only thing that was ever written to disk on its own was a map's
*block contents*, via `/recored map save` (a world-generated structure under
`<world>/generated/recored/structure/maps/<id>.nbt`). So without extra work,
restarting the server drops every registered map and the lobby back to
nothing - the structure file would still be sitting there, just orphaned,
with no `MapConfig` left pointing at it.

`game/MapPersistence.java` fixes that: it serializes the lobby and every
registered map (including each kit's items, via `ItemStack.CODEC` +
`RegistryOps`) to a single compressed-NBT file at
`<world>/data/recored_maps.dat` - the same `data/` folder vanilla uses for
its own per-world persistent state. `MapPersistence.save` runs after
**every** mutating `/recored map ...` / `/recored setlobby` / `/recored sign
set|remove` command, not just on a clean shutdown, so a crash or `kill` can't
lose anything either. `MapPersistence.load` runs once, from `ServerLifecycle
Events.SERVER_STARTED`, re-registering whatever was saved before any player
can join. `SignCommands` (see "Command signs" below) is saved in the same
file, the same way.

Note this only covers maps/lobby *configured after this fix shipped* - a
world's existing `generated/cores/...` structure files (or any registered
maps) from before the mod was renamed from "Cores" to "Recored" won't
auto-migrate; re-register and `/recored map save` them again under the new
namespace.

### Core mining

Each team tracks its own **left** and **right** core independently - RED and
BLUE each have their own left slot and right slot (`MapConfig.cores`:
`Map<Team, Map<Side, BlockPos>>`), so a map can have up to four cores total:
RED left, RED right, BLUE left, BLUE right. `/recored map addcore <id>
<red|blue> <l|r>` registers the block you're looking at as that specific
team+side slot (`removecore` takes the same three arguments to clear one).
At least one slot (either side) is required per team for a map to be
playable - both aren't mandatory. This naming carries through the action bar
and sidebar (see "HUD" below).

Vanilla mining progress lives on the *player* (`ServerPlayerGameMode`) and resets
whenever they stop, switch target, or log off - not what you want for a shared
team objective. `CoreMiningMixin` cancels vanilla's handling for core blocks in
survival and hands off to `GameManager`, which:

- accumulates progress on the **block** (`GameManager.coreProgress`), using the
  same per-tick formula vanilla does (`BlockState.getDestroyProgress`) - see
  "Mining a hardened core with a vanilla client" below for how the actual
  *duration* (`GameManager.coreHardness`, currently `9.0F` - triple vanilla's
  own beacon default of `3.0F`; retune later if the round-length balance
  needs adjusting) is achieved without needing anything installed client-side;
- keeps that progress no matter who's swinging, or if everyone stops/logs off;
- shows it to the miner as an **action-bar message** ("Mining RED Left Core: 42%",
  the text that floats just above the hotbar) every tick they're digging - see
  `GameManager.applyMiningTick`;
- refuses to even start for a player mining their **own** team's core (checked
  before anything else in `CoreMiningMixin`, with a chat message explaining why),
  and for anyone via creative insta-mine in the `PlayerBlockBreakEvents.BEFORE`
  safety net.

That own-core refusal is enforced server-side regardless (mining one just
never progresses, ever), but if a client also has the mod installed (still
entirely optional), `CoreProtectionMixin` additionally makes it behave like
an adventure-mode-restricted block *for your team specifically* - overriding
`Player#blockActionRestricted` (the exact check both the server and a modded
client's own local `MultiPlayerGameMode` independently consult before even
starting to mine) so the mining animation/crack overlay never starts at all,
rather than just never finishing. Ownership is checked against the
authoritative `cores` map server-side, and against a small per-player synced
set - `GameManager.ownCores`, part of `CoreSyncPayload` - on a modded client,
since it doesn't otherwise know team assignments.

#### Mining a hardened core with a vanilla client

A core needs to take noticeably longer to mine than a real vanilla beacon
(`coreHardness`, above) - but changing a block's actual registered hardness is
data a vanilla client already has baked in and can't be told otherwise
without a resource/data pack it doesn't have. So `BeaconHardnessMixin` (an
earlier version of this mod) tried to fake the hardness server-side only,
which meant an unmodified client kept predicting completion at the real,
much shorter vanilla time - repeatedly finishing its own local mining
animation, optimistically hiding the block, getting corrected once the
server's ack disagreed, and popping the block back - and vanilla doesn't
auto-resume mining on the same held-down click once it's finished (even
speculatively), so the player had to release and click again every cycle.

The actual fix doesn't touch the block's hardness at all - `Blocks.BEACON`'s
real `3.0F` is left alone and used by both sides identically. Instead,
`GameManager.startDigging` applies a transient modifier to the miner's
**`Attributes.BLOCK_BREAK_SPEED`** attribute - a real, per-player vanilla
mechanic, synced to the client automatically like any other attribute, no
mod required to receive it - that slows their effective mining speed by
exactly `3.0F / coreHardness` for as long as they keep digging (removed the
moment they stop, in every path that can end it: release/abort, the core
breaking, disconnecting, or the tick loop noticing the block or target
changed). Since a vanilla client's own local mining prediction reads the
*same* real block hardness and the *same* synced attribute value as the
server's `applyMiningTick`, both sides now agree on the total mining time
throughout - not just by coincidence at the very end.

Applying that modifier only reactively, the moment digging actually starts,
still leaves a small gap though: the attribute change has to round-trip back
to the client before *that* client's own local prediction starts using it,
so for the first stretch of any dig the client is still predicting at the
old, faster speed - racing a little ahead of the server, which is exactly
what could still make a core feel "stuck" just under 100%, needing one more
manual re-click to finish. `GameManager.tickMiningSlowdownPriming` closes
this too: every tick, for every rostered player, it pre-arms the same
modifier the moment they're simply *looking* at an enemy core - before
they've clicked at all - so by the time they actually start digging, the
attribute has almost always already finished round-tripping (cheap - one
raycast per rostered player per tick - and side-effect-free, since it never
touches anyone not currently sighted on an enemy core). `applyMiningTick`
also doesn't require a mathematically exact `1.0` to finish (`MINING_COMPLETE
_THRESHOLD` = `0.98F`), a small safety-net tolerance for whatever residual
gap is left. Together, a core now mines start-to-finish in one continuous
hold, exactly like mining any ordinary block.

Beacons aren't in the pickaxe-mineable tag either way, so a tool never gives
a mining-speed bonus on one - a diamond pickaxe mines a core exactly as fast
as bare hands, and the flat `coreHardness * 30`-tick total (`9.0F` is 270
ticks, 13.5s) applies regardless of what's in your hand.

Creative-mode insta-mine bypasses the mixin and is caught as a safety net in
`PlayerBlockBreakEvents.BEFORE`, which (for **every** core removal path) breaks
the block with `Level.destroyBlock(pos, false, ...)` - sound and particles, no
item drop - plus a firework-particle burst + explosion sound
(`GameManager.playDestructionEffect`) and a bump of `respawnDelayTicks` up to at
least 3s (once any core falls, respawns get a little slower for the rest of the
fight). Right-clicking a core (to open the beacon beam/effect GUI) is blocked
outright via `UseBlockCallback`, for anyone, in any phase.

### HUD

A persistent per-team sidebar (`GameManager.setupHud`/`refreshHud`) shows,
at the top, an uncapped **"Time: M:SS"** line - how long the current round
has been running, identical on both sidebars, counting up with no limit
(`GameManager.roundElapsedTicks`, incremented every tick while `RUNNING`,
reset to `0` at the start of each round) - and below that, **both** teams'
cores and their health % - `<= 20%` red, `<= 50%` yellow, otherwise green -
your own team's listed first, the enemy team's below that, each marked with
a small colour-coded `■` and labelled "RED Left Core"/"RED Right Core"/"BLUE
Left Core"/"BLUE Right Core" as applicable (both the team and the side are
always named, since each team's Left/Right cores are tracked independently
of the other team's - see "Core mining" above). It's real vanilla scoreboard
sidebar, not custom rendering: two objectives are
registered, one displayed at `DisplaySlot.TEAM_RED`, one at `TEAM_BLUE` -
vanilla only shows a team-colour sidebar slot to viewers whose own scoreboard
team matches that colour, so red players automatically see the red-team
sidebar and vice versa - but each of those two sidebars is populated with
**both** teams' cores (own section + enemy section), so nobody's core health
is hidden from anybody. Each line's text comes from `ScoreAccess#display` (a
full `Component`, not just a number - the objective's number format is
`BlankFormat.INSTANCE` so no numeric score is shown at all) and updates every
`HUD_REFRESH_TICKS` (5 ticks).

When an enemy is within `ENEMY_WARNING_RANGE` (10 blocks) of a core, its line
flashes white on a slow blink (plus goes permanently bold while the enemy
stays in range) and a repeating noteblock-harp note (`SoundEvents.
NOTE_BLOCK_HARP`) plays at the core every `WARNING_SOUND_TICKS` (1s) - both
driven from the same periodic HUD refresh. This is the **one** thing that
stays team-specific: it only ever applies to a team's own cores, on that
team's own sidebar - the enemy section of a sidebar always shows plain
health-coloured text, never blinks, and never triggers the warning note
(computed once per core per refresh either way, so it can't double-trigger
just because the same core now appears on both sidebars).

Destroying a core does **not** remove its line - it stays, in red with
strikethrough text ("RED Left Core: DESTROYED"), instead of just disappearing
(`GameManager.destroyedCores` remembers it with its original owner team+side;
`breakCore` forces an extra `refreshHud` outside the normal cadence so this
happens the same tick, not up to 5 ticks later). The whole sidebar clears the
moment a round ends (see "Round end" below) - it doesn't keep showing the
last round's numbers into the next one.

**The sidebar is only ever shown while a round is actually `RUNNING`** -
`refreshHud` unassigns both `DisplaySlot.TEAM_RED`/`TEAM_BLUE` entirely
(`setDisplayObjective(slot, null)`) and clears every line whenever the phase
isn't RUNNING, so joining a team, sitting in the lobby, or readying up don't
show a HUD for a round that hasn't started yet - it only appears the instant
`beginStart` flips to RUNNING, and disappears again the instant the round
ends.

Sidebar visibility to a given *player* is separately gated by **vanilla
scoreboard team membership** (see the client's `Hud#extractScoreboardSidebar`:
it looks up the local player's `scoreboard.getPlayersTeam(...)`, and only
then resolves that team's colour to a `DisplaySlot` - no team, no sidebar,
regardless of whether anything is even assigned there). Vanilla scoreboard
team membership persists in `scoreboard.dat` across restarts; `GameManager`'s
own player/team map does not. So on a restart, a player who had joined a team
before could come back with a *stale* vanilla team assignment while
`GameManager` itself has forgotten them entirely - the sidebar (and colored
nametag) would then show even though they're no longer actually "in" a team
as far as the mod is concerned. `setupHud` fixes this by clearing every
player out of both scoreboard teams (`recored_red`/`recored_blue`) at every
`SERVER_STARTED`, before anyone can join - the sidebar/nametag only reappear
once a player runs `/recored join` again, and `leave`/team-loss removes them
the same way.

`setupHud` also purges anything left in `scoreboard.dat` from an earlier
version of this mod that would otherwise sit there forever, invisible to the
code but not to a viewer: the entire orphaned `cores_hud_red`/`cores_hud_blue`
objectives from before the "Cores" → "Recored" rename, and - inside the two
*current* objectives - any individual score entry whose name doesn't match
today's `"<team>_line_<n>"` scheme (e.g. an old `"blue_core_0"`/`"blue_core_1"`
entry from a since-changed naming convention), which would otherwise show up
as permanent extra lines (health-frozen at whatever they last were) alongside
the real, current ones.

### Round end

When a round ends - a natural win or a manual `/recored reset` - both are
handled identically by `GameManager.cleanupRound`: every participant is sent
back to the lobby in Adventure mode with their inventory fully cleared (no
leftover kit items carried into the lobby), **fully removed from their team**
(roster entry + backing scoreboard team - nobody stays teamed up, or holding
the ready-up clay ball, after a game), the map's blocks are restored from
their saved baseline, and this round's entire working state (remaining
cores, mining progress, in-progress digging, ready state) is wiped - which
is also what fixes the sidebar HUD no longer clearing itself (see "HUD"
above). A rematch means everyone runs `/recored join` again from scratch.

A natural win additionally plays a short victory celebration
(`GameManager.playVictoryCelebration`): a `ui.toast.challenge_complete`
sound centred on every connected player individually (so everyone hears it
clearly regardless of where they physically are, not just whoever's near the
lobby), plus a firework particle burst with launch/blast sounds specifically
at the lobby spawn (everyone's already standing there by the time this
plays).

### Death & respawn

Death is real - nothing is cancelled, nothing dropped (`keep_inventory` is
turned on at server start). The player sees the normal vanilla death screen
(`immediate_respawn` is explicitly left **off** for this) for a short,
live-adjustable beat (`GameManager.respawnDelayTicks`, default 20 ticks / 1s -
change with `/recored respawndelay <seconds>`), started the moment they actually
die (`ServerLivingEntityEvents.AFTER_DEATH`).

When that beat elapses, `GameManager.forceRespawn` triggers their respawn
itself - no button click needed - by feeding a synthetic `PERFORM_RESPAWN`
packet through the exact same server code a manual click would hit
(`ServerGamePacketListenerImpl#handleClientCommand`), so all of vanilla's own
bookkeeping (position reset, connection's player reference, load timer, ...)
happens correctly for free. That lands them at the vanilla world/bed spawn for
an instant, which immediately triggers `ServerPlayerEvents.AFTER_RESPAWN` ->
`GameManager.finishRespawn` (while RUNNING) or `GameManager.sendToLobby`
(any other time - e.g. dying in the lobby itself), redirecting them **with
their kit reissued** the same tick if it was a team-spawn respawn - no visible
detour through server spawn.

## Commands

Open to everyone:

| Command | Effect |
|---------|--------|
| `/recored join <red\|blue>` | Join a team (WAITING only) - clears your inventory and gives you the "Not ready" item |
| `/recored leave` | Leave your team (WAITING only) |
| `/recored status` | Print phase, respawn delay, lobby/map status, team sizes |

Right-clicking the ready-up item (see "Ready-up & auto-start" above) toggles
ready/not-ready and is how a round actually starts - not a command.

Game masters (`Commands.LEVEL_GAMEMASTERS`, i.e. `/op`-level) – lobby & round control:

| Command | Effect |
|---------|--------|
| `/recored pos1` / `/recored pos2` | Generic two-corner scratch selection (WorldEdit-style) - feeds `map setregion` and `setlobby` |
| `/recored setlobby` | Set the lobby spawn (your position/rotation) **and** commit the current pos1/pos2 selection as the lobby's protected region, in one call |
| `/recored start` | **Instant override**, bypassing the ready/countdown system: pick the next ready map and begin the round immediately (still needs a player on each team + a ready map) |
| `/recored reset` | Abort the round: reset the active map's structure, send everyone to the lobby, wipe all round/team state back to WAITING |
| `/recored respawndelay <seconds>` | Set the pause between a death and respawn (default 1s) |
| `/recored sign set <command>` | Attach a command to the sign you're looking at - right-clicking it runs the command as the clicking player |
| `/recored sign remove` | Detach whatever command is attached to the sign you're looking at |

Game masters - map configuration (every subcommand takes an explicit `<id>`, so there's no "currently being edited" map to lose track of):

| Command | Effect |
|---------|--------|
| `/recored map create <id>` | Register a new, empty map |
| `/recored map corner1 <id>` / `/recored map corner2 <id>` | Set that corner of the map's save/reset bounding box to the block you're looking at |
| `/recored map setspawn <id> <red\|blue>` | Record your position/rotation as that map's spawn for that team |
| `/recored map addcore <id> <red\|blue> <l\|r>` | Register the block you're looking at as that team's left (`l`) or right (`r`) core - each team has its own independent left/right slots |
| `/recored map removecore <id> <red\|blue> <l\|r>` | Unregister that team's left/right core |
| `/recored map setregion <id> <red\|blue>` | Commit the current pos1/pos2 selection as that map's spawn-protection region for that team |
| `/recored map setkit <id> <red\|blue>` | Capture your exact current inventory (every slot) as that map's kit for that team |
| `/recored map save <id>` | Snapshot the blocks in the bounding box as the reset baseline (requires both corners set) |
| `/recored map delete <id>` | Unregister a map (refuses if it's the currently active one) |
| `/recored map list` | List every registered map with its readiness status |

## Rules

While `RUNNING`:

* PVP is on, but **only** between two rostered team members (either team, friendly fire included) - a bystander can neither hit nor be hit, and this is enforced directly (`ServerLivingEntityEvents.ALLOW_DAMAGE`), not via the vanilla PVP gamerule, which this mod never touches.
* A core is only breakable by the *other* team - your own team's core is unmineable outright (no animation even starts), not even in creative.
* A registered core is always breakable (by the enemy), even inside its map's spawn region, and never drops an item - destroying one triggers a firework/explosion effect and bumps the respawn delay to at least 3s for the rest of the round. Its sidebar line stays afterwards, marked DESTROYED in red strikethrough, instead of disappearing.
* Right-clicking a core (beacon beam/effects GUI) does nothing, for anyone, at any time.
* Breaking a team's **last** core ends the round for the other team.
* Any other block inside the active map's spawn regions can neither be broken **nor built on** - no placing blocks, no emptying buckets, either.
* Death is real (death screen shown, no item drops); after a short `/recored respawndelay` pause they're auto-respawned straight to their team spawn, no click needed, with their inventory unconditionally reset to exactly their saved kit (nothing carried over, ever - not even an incomplete/empty kit).
* The sidebar HUD is shown (only now - see "HUD" above) with both teams' core health always visible to both teams (your own cores listed first, the enemy's below) - only the enemy-nearby blink/warning sound stays restricted to a team's own cores.

At any other time:

* PVP is off entirely, even between two rostered team members.
* Only the lobby region is break/build-protected (a map's own regions aren't relevant - nobody should be standing in one; see the "map territory" note below).
* Dying (however it happens) sends you back to the lobby spawn instead of vanilla's world spawn.
* A rostered player holds the ready-up item (can't be dropped or kept out of its slot) - right-clicking it toggles ready/not-ready, and a round auto-starts once both teams are equal size (≥1 each) and everyone's ready, after a 5s abortable countdown. See "Ready-up & auto-start" above.

Always:

* Player names are coloured red/blue (tab list + nametag) via a backing vanilla scoreboard team.
* When a round ends (win or `/recored reset`), every participant is switched to Adventure mode, has their **inventory fully cleared**, is **fully removed from their team**, and is returned to the lobby; the map's blocks are restored from its saved baseline and the sidebar HUD clears - ready for another round (same or next map) without any manual cleanup. A natural win also plays a victory sound for everyone plus fireworks at the lobby spawn.
* A sign with a command attached (`/recored sign set`) runs that command as whoever right-clicks it - the sign's own text is whatever was written on it with normal vanilla sign editing, entirely separate from the attached command.

**Scope note:** spawn protection outside `RUNNING` only covers the lobby region itself - a player who wanders physically outside the lobby into an unstarted map's territory isn't blocked from breaking or building there. In practice this shouldn't come up (players are teleported straight into the lobby on join and after every round), but it isn't actively fenced off either.

## License

[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) -
Attribution-NonCommercial-ShareAlike. You're free to use, modify, and share
this (including forks/derivatives), for non-commercial purposes, as long as
you give credit and share any derivative under the same license. See
`LICENSE` for the full legal text.
