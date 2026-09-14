Recored
=======

Team core-destruction minigame: mine the enemy team's beacon cores while defending your own.
First team to lose all their cores loses. A standalone Paper plugin port of the user's
`fabric-example-mod-26.2` Fabric mod of the same name, for Minecraft **26.2** (Java 25+
required).

Same rules, same design as the original mod - just built entirely on plain Paper
events/scheduler instead of Fabric callbacks and Mixins (see the doc comment on
`GameManager` and `RecoredListener` for exactly which vanilla event replaces which
mixin/callback). **Dropped**: the original's optional client-side "highlight your own/enemy
cores" networking sync - it needed a companion client mod, and there's no equivalent for a
vanilla-client Paper server. Everything else - persistent shared core mining (a core survives
a miner letting go or logging off, and can be worked on by multiple players), spawn
protection, the ready-up auto-start, the per-team sidebar HUD with an enemy-proximity
warning, the respawn delay, multi-map support with round-robin rotation, command signs - is
fully intact.

Entirely independent of the [BowBash](https://github.com/Arda501/BowBash) plugin: different
command (`/recored` vs `/bb`), different permission root (`recored.*` vs `bowbash.*`),
different package, different data folder, and every listener that touches a player who
isn't a Recored participant (PVP-gating, join/respawn redirection) bails out immediately
rather than acting server-wide - both can be installed on the same server with no conflict.

Building
--------

```
mvn clean package
```

Produces `target/Recored-<version>.jar`. Drop it into your server's `plugins/` folder.

Setting up
----------

All admin commands require `recored.admin` (granted to ops by default).

1. **Lobby**: look at one corner of the lobby area, `/recored pos1`; the opposite corner,
   `/recored pos2`; stand where players should land, `/recored setlobby`.
2. **A map**: `/recored map create <id>`, then for that id:
   - `/recored map corner1`/`corner2` (look at a block) - the map's bounding box
   - `/recored map setspawn <id> <red|blue>` (stand there) - each team's spawn
   - `/recored map addcore <id> <red|blue> <l|r>` (look at a block, e.g. a beacon) - up to two
     cores per team
   - `/recored map setregion <id> <red|blue>` (uses your last `/recored pos1`/`pos2`) - that
     team's protected spawn area
   - `/recored map setkit <id> <red|blue>` - captures your current inventory as that team's kit
   - `/recored map save <id>` - captures every block in the bounding box as the reset baseline
     (do this last, once the map actually looks how you want it to reset to)
3. `/recored map list` shows what's still missing before a map counts as READY.

Multiple maps can be registered; a ready round picks the next one round-robin.

Playing
-------

`/recored join <red|blue>` hands you a "Not Ready" item - right-click it to ready up. Once
both teams are equal size (at least one player each) and everyone's ready, a countdown starts
automatically and the round begins on whichever map is next in rotation. `/recored leave`
before that; `/recored status` any time. Your sidebar shows team sizes, how many are ready,
and the countdown the whole time you're waiting - not just once a round is actually running.

Mining an enemy core takes a few seconds of continuous digging (`/recored coretime <seconds>`
to change how long, default 10s) and can be shared/resumed by any teammate; your own core
can't be mined at all, not even in creative. Losing all your cores ends the round for your
team. Admins: `/recored start` force-starts (bypassing ready-up, if a map is ready),
`/recored reset` aborts back to WAITING, `/recored respawndelay <seconds>` adjusts the pause
before a dead player's forced respawn (default 1s, automatically bumped to at least 3s once
any core has fallen).

`/recored sign set <command...>` (looking at a sign) attaches a command that runs as whoever
right-clicks it - `/recored sign remove` detaches it.

Links
-----

- Original mod: `fabric-example-mod-26.2` (Fabric, client+server)
