# ArcanaTownyBridge

A small Paper plugin that lets datapacks respect Towny. A datapack has no way to ask Towny whether a player may
build somewhere, or which town a spot belongs to. This plugin answers those questions ahead of time: it keeps a few
scoreboard tags and scores up to date on players and marker entities, and the datapack checks them when something
happens. The datapack never calls the plugin; the plugin never runs datapack functions. They only share the main
scoreboard.

It was written for two datapacks by JCreates: J's Arcana (namespace `jsarcana`, build 49) and JPack's Builder's Wand
(objectives `jcts.bw.*`). Part of what it does is generic and can be used by any datapack; part of it only makes sense
for J's Arcana's own entities. The [datapack interface](#the-datapack-interface) section says which is which.

Current version: 1.4.0.

## Requirements

- **Paper 26.3** (Minecraft Java 26.3). `plugin.yml` declares `api-version: '26.3'` and the plugin is compiled
  against the Paper 26.3 API. Other server software is not supported; Folia is not supported (the plugin reads
  entities in every world from one global task).
- **Java 25**, because Paper 26.1 and later require it.
- **Towny 0.103.2.7 or newer**, the first Towny build that supports Minecraft 26.3 (a pre-release at the time of
  writing). `plugin.yml` has `depend: [Towny]`, so the plugin will not load without Towny.
- A datapack that reads what the plugin writes: J's Arcana, JPack, or your own (see below). Without one the plugin
  does nothing useful, but it does no harm either.

## Install

1. Download `ArcanaTownyBridge-<version>.jar` from this repository's GitHub Releases page. If there is no release
   yet, open the Actions tab, pick a successful "Build" run, and download the `ArcanaTownyBridge` artifact (a zip
   that contains the jar; GitHub requires you to be signed in to download artifacts).
2. Put the jar in the server's `plugins/` folder, next to Towny.
3. Restart the server (do not use `/reload`).

On first start the plugin writes `plugins/ArcanaTownyBridge/config.yml` with the defaults listed below.

## Build from source

You need:

- JDK 25 installed on the build machine. The build asks for a Java 25 toolchain but does not set up automatic
  toolchain downloads, so Gradle will not fetch a JDK for you.
- Gradle 9.1 or newer (the first Gradle that supports Java 25).

Then, in the repository root:

    gradle build

The jar ends up at `build/libs/ArcanaTownyBridge-1.4.0.jar`.

No Gradle wrapper is committed. If you want one, run `gradle wrapper --gradle-version 9.1.0` once and commit the
generated files.

The plugin version is set in one place, `version = "1.4.0"` in `build.gradle.kts`; it is copied into `plugin.yml`
at build time.

`gradle.properties` says which Paper and Towny builds to compile against:

- `paperVersion=26.3.build.242-stable`. Paper 26.x versions look like `<mc>.build.<n>-<status>`. Set it to the
  -stable build your server runs. (The older pattern `26.3.0.build.+` matches nothing, since 26.3 has no `.0`.)
- `townyVersion=0.103.2.7`, resolved from Towny's own Maven repository `https://repo.glaremasters.me/repository/towny/`
  (the address the Towny wiki gives). 0.103.2.7 is a pre-release; if it does not resolve from that repository, use
  the newest version that does (0.103.2.0 is the newest full release the wiki lists). The Towny API calls used here
  are the same in both.

The GitHub Actions workflow in `.github/workflows/build.yml` builds every push and pull request with Temurin 25 and
Gradle 9.1.0 and uploads the jar as an artifact. Pushing a tag that starts with `v` (for example `v1.4.0`) also
creates a GitHub Release with the jar attached. Keep the tag and the version in `build.gradle.kts` the same.

## Configuration

`plugins/ArcanaTownyBridge/config.yml`. It is read once at startup; there is no reload command, so restart the
server after changing it. Values for the `every.*` keys below 1 are treated as 1.

| Key | Default | Meaning |
| --- | --- | --- |
| `edge.player` | `16` | Radius in blocks for the `jsarcana.edge` tag on players. |
| `edge.focus` | `16` | Radius for `jsarcana.edge` on J's Arcana Spell Foci. |
| `edge.keystone` | `16` | Radius for `jsarcana.edge` on J's Arcana Spell Keystones. A keystone's Field Rods stand within 16 blocks of it, so its field reaches 16 at most. Lower it for keystones in small towns (see below). |
| `edge.still-spell` | `8` | Radius for `jsarcana.edge` on J's Arcana spells that stay in place (runes, walls, delays, lingering areas). |
| `edge.flying-spell` | `12` | Radius for `jsarcana.edge` on J's Arcana spells in flight (bolts). |
| `every.players-and-still-spells` | `5` | Ticks between checks of players, still spells, Spell Foci and Spell Keystones. Spells in flight are always checked every tick. |
| `every.objects` | `40` | Ticks between updates of the `jsarcana.town` score, on players and on J's Arcana object markers. |
| `every.builders-wand` | `10` | Ticks between re-checks of a Builder's Wand selection that has not changed (towns change too). A new or changed selection is always checked on the next tick. |

## The datapack interface

This is everything the plugin reads and writes. Names are exact.

### General rules

- **The plugin never creates objectives.** Your datapack's load function must create every objective listed here
  (`scoreboard objectives add <name> dummy`). If an objective is missing, the part of the plugin that needs it is
  skipped silently.
- Everything is on the server's **main scoreboard**.
- The plugin only looks at **players** and **`minecraft:marker` entities**. Other entity types are never tagged or
  scored.
- "May not build" always means: Towny's BUILD permission for that player at that location, checked with
  `PlayerCacheUtil.getCachePermission(player, location, STONE, BUILD)`. That is the same answer Towny gives when the
  player places a block there, so it follows town, plot, resident, nation and wilderness settings. DESTROY, SWITCH
  and ITEM_USE are not checked separately.
- The plugin runs once per server tick from Paper's scheduler. Do not assume it runs before or after your tick
  function in the same tick; treat every value as possibly one tick old (or one check interval old, see
  [Configuration](#configuration)).
- **The J's Arcana part, including the generic player tags, only runs while the objective `jsarcana.sp.dg` exists.**
  The plugin uses it to tell that J's Arcana has loaded. The Builder's Wand part does not depend on it.

### Generic: player tags and town number

These work for any datapack.

| Name | Kind | On | Updated | Meaning |
| --- | --- | --- | --- | --- |
| `jsarcana.notown` | tag | every online player | every `every.players-and-still-spells` ticks | The player may not build at the block they stand on. Removed when they may. |
| `jsarcana.edge` | tag | every online player | same | The player may build where they stand, but somewhere within `edge.player` blocks they may not. Never present together with `jsarcana.notown`. |
| `jsarcana.town` | score | every online player | every `every.objects` ticks | The player's own town (as a resident), as a number. `0` means no town. Needs the objective `jsarcana.town`. |

Town numbers are positive integers from 1 to 2,000,000,000, derived from the town's UUID. They stay the same across
restarts and town renames. `0` always means "no town" (or wilderness, for land). Two towns getting the same number
is possible in theory but very unlikely.

How `jsarcana.edge` is worked out: the plugin tests the spot itself, then 8 points on a circle at half the radius
and 16 points on a circle at the full radius, all at the entity's height. It stops at the first forbidden point. It
is a sample, not a full scan: a very small piece of forbidden land between sample points can be missed. Use
`jsarcana.edge` for "be careful here" (for example, an area effect only does its harmless part), and
`jsarcana.notown` for "refuse".

To use the player tags from a datapack that is not J's Arcana, create the two objectives in your load function.
The names are in J's Arcana's namespace for historical reasons; creating them when J's Arcana is also installed is
harmless (the second `add` just fails).

```mcfunction
# load
scoreboard objectives add jsarcana.sp.dg dummy
scoreboard objectives add jsarcana.town dummy
```

Refusing an action while the player stands where they may not build:

```mcfunction
# run as the player, before changing blocks
execute if entity @s[tag=jsarcana.notown] run return run title @s actionbar {text:"You can't do that here",color:"red"}
function mypack:do_the_thing
```

Doing only the harmless part of an area effect near land the player may not touch:

```mcfunction
# run as the player
execute if entity @s[tag=jsarcana.notown] run return fail
execute if entity @s[tag=jsarcana.edge] run return run function mypack:blast/particles_only
function mypack:blast/full
```

Checking that the player belongs to a town at all:

```mcfunction
execute unless score @s jsarcana.town matches 1.. run return run tellraw @s {text:"Join a town first",color:"red"}
```

### Generic: Builder's Wand box check

This lets a datapack ask "may this player build in every column of this box?". It is written for JPack's Builder's
Wand but works for any datapack that writes these scores. It does not need J's Arcana.

All six objectives must exist, or this part does nothing: `jcts.bw.rev`, `jcts.bw.ok`, `jcts.bw.x1`, `jcts.bw.z1`,
`jcts.bw.x2`, `jcts.bw.z2`.

What the plugin reads, on each online player:

| Name | Kind | Meaning |
| --- | --- | --- |
| `jcts.bw.rev` | score | The selection's revision. Absent or `0` (or below) means "no selection". Set it to a new positive value every time the box changes; the plugin only re-evaluates at once when `jcts.bw.rev` differs from `jcts.bw.ok`, so reusing the last value means the change is not noticed until the next periodic re-check. A global counter that only goes up is the simplest way. |
| `jcts.bw.x1`, `jcts.bw.z1`, `jcts.bw.x2`, `jcts.bw.z2` | score | The box's block x/z bounds, inclusive. The plugin takes the min and max itself, but JPack keeps `x1 <= x2` and `z1 <= z2`. The box is in the player's current world; y is ignored. |

What the plugin writes, on each online player:

| Name | Kind | Meaning |
| --- | --- | --- |
| `jcts.bw.ok` | score | The `jcts.bw.rev` value the plugin last evaluated. It is written after the tag, so when `jcts.bw.ok` equals `jcts.bw.rev`, `jcts.bw.deny` is correct for that box. Left alone when there is no selection. |
| `jcts.bw.deny` | tag | Present if some part of the box is land where the player may not build. Removed when the whole box is allowed, and removed from players with no selection. |

Timing: a new or changed selection (`rev != ok`) is checked on the plugin's next tick. A selection that has already
been checked is checked again every `every.builders-wand` ticks; that can add or remove `jcts.bw.deny` without
changing `jcts.bw.ok`. So read the tag at the moment you act, not only when `ok` catches up.

How the box is checked: one location per 16x16 chunk that the box overlaps (a point of the box inside that chunk),
at the player's current height, with the BUILD check described above. A box whose x or z bounds differ by 64 or more
(more than 64 blocks wide) is treated as a bad score, not a real selection: it is denied without checking, and a
message is logged once per server run at debug (FINE) level.

**Town block size assumption.** One check per chunk is exact only when every chunk lies inside a single Towny town
block. That is true with Towny's default `town_block_size` of 16 (and with sizes that are multiples of 16). With any
other size, the check can miss land.

The handshake, from the datapack's side:

```mcfunction
# load
scoreboard objectives add jcts.bw.rev dummy
scoreboard objectives add jcts.bw.ok dummy
scoreboard objectives add jcts.bw.x1 dummy
scoreboard objectives add jcts.bw.z1 dummy
scoreboard objectives add jcts.bw.x2 dummy
scoreboard objectives add jcts.bw.z2 dummy
scoreboard objectives add mypack.tmp dummy
```

```mcfunction
# as the player, whenever the box changes: write the bounds first, then a fresh revision
# (jcts.bw.x1 / z1 / x2 / z2 already set here)
scoreboard players add #rev mypack.tmp 1
scoreboard players operation @s jcts.bw.rev = #rev mypack.tmp
```

```mcfunction
# as the player, when the selection is cleared
scoreboard players reset @s jcts.bw.rev
```

```mcfunction
# as the player, on confirm
execute unless score @s jcts.bw.ok = @s jcts.bw.rev run return run title @s actionbar {text:"Checking...",color:"gray"}
execute if entity @s[tag=jcts.bw.deny] run return run title @s actionbar {text:"You can't build here",color:"red"}
function mypack:fill_box
```

A datapack that waits for `jcts.bw.ok` will wait forever on a server without this plugin. JPack has its own switch
for that, `#bw_towny jcts.bw.cfg` (1 by default, which waits for the plugin; set it to 0 only where the plugin is not
installed). If you write your own datapack against this interface, give it a similar switch.

### J's Arcana only: spells, foci, keystones and placed objects

This part keys on J's Arcana's entity tags and scores. It is documented so the behaviour is clear, not because other
datapacks are expected to use it. All of it needs the objective `jsarcana.sp.dg`, and only `minecraft:marker`
entities are considered. Each marker is handled by the first rule that matches, in this order:

1. **Spell carriers**: markers with any `jsarcana.sp.dg` score.
2. **Spell Foci**: markers tagged `jsarcana.focus`.
3. **Spell Keystones**: markers tagged `jsarcana.ks`.

Independently of that, **placed objects** (markers with one of the object tags below) get a town number.

What the plugin reads:

| Name | Kind | On | Meaning |
| --- | --- | --- | --- |
| `jsarcana.sp.dg` | score | spell carrier markers | Marks a marker as a spell to watch. `2` or more: in flight, checked every tick with `edge.flying-spell`. Any other value: still, checked every `every.players-and-still-spells` ticks with `edge.still-spell`. |
| `jsarcana.pid` | score | players | The player's id number. |
| `jsarcana.sp.pid` | score | spell carrier markers | The `jsarcana.pid` of the player who cast it. |
| `jsarcana.sp.focus` | tag | spell carrier markers | The spell came from a Spell Focus, a Spell Keystone or a rune, not directly from a player. |
| `jsarcana.sp.ox`, `jsarcana.sp.oy`, `jsarcana.sp.oz` | score | spell carrier markers with `jsarcana.sp.focus` | The block the spell came from, in the marker's world. A missing score reads as 0. |
| `jsarcana.focus` | tag | markers | A Spell Focus. |
| `jsarcana.ks` | tag | markers | A Spell Keystone. |

What the plugin writes:

| Name | Kind | On | Meaning |
| --- | --- | --- | --- |
| `jsarcana.notown` | tag | spell carriers | The spell stands where its caster may not build. |
| `jsarcana.edge` | tag | spell carriers, Spell Foci, Spell Keystones | Forbidden land within the matching `edge.*` radius, while the spot itself is allowed. Never together with `jsarcana.notown`. |
| `jsarcana.town` | score | object markers | The town whose land the marker stands on; `0` for wilderness. Every `every.objects` ticks. |

Whose rights a spell carrier is checked against:

- With `jsarcana.sp.focus`: the rule for placed magic. If the origin block (`jsarcana.sp.ox/oy/oz`) is in a town,
  the spell may only touch that town's land; if the origin is in the wilderness, it may only touch wilderness.
- Otherwise: the online player whose `jsarcana.pid` equals the carrier's `jsarcana.sp.pid`, with that player's
  Towny build rights. If no such player is online (or `jsarcana.sp.pid` does not exist), the spell may only touch
  wilderness. Keep player ids positive, since a missing `jsarcana.sp.pid` reads as 0.

Spell Foci and Spell Keystones use the placed-magic rule from where they stand. They only ever get `jsarcana.edge`,
never `jsarcana.notown` (the spot they stand on is their own by definition). For a keystone the tag is all or
nothing: a keystone within `edge.keystone` blocks of land that isn't its own town's (wilderness included; for a
keystone in the wilderness, any town's land) changes no blocks anywhere in its field, even inside its own town.

Object tags that get a `jsarcana.town` score:

| Tag | J's Arcana object |
| --- | --- |
| `jsarcana.pot` | brewing pot |
| `jsarcana.tb` | spellcrafting table |
| `jsarcana.jar` | tincture jar |
| `jsarcana.rack` | drying rack |
| `jsarcana.ball` | crystal ball |
| `jsarcana.hb` | hat bench |
| `jsarcana.carp` | carpentry table |
| `jsarcana.sp.rune` | rune |
| `jsarcana.nx` | nexus |
| `jsarcana.er` | Empowerment Rod |
| `jsarcana.bag` | open Portal Bag |
| `jsarcana.bat` | Essence Battery |
| `jsarcana.wa` | Wind Anchor |

Spell Keystones and Field Rods get no town number on purpose: J's Arcana already lets only whoever set them change
them, and a town number would lock out an owner who is not a resident of that town.

How J's Arcana uses all this, for reference:

- Block-changing spell effects check the spell carrier's tags: nothing happens on `jsarcana.notown`, only the
  harmless part happens on `jsarcana.edge`.
- Something set down from an item (drying rack, hat bench, spellcrafting table, crystal ball, tincture jar, Essence
  Battery, Wind Anchor, Spell Keystone, Field Rod, Empowerment Rod) pops back out as an item if the nearest player
  has `jsarcana.notown`. Striking a placed thing to take it down at once (which Towny cannot see) is refused while
  the striker has the tag.
- Right-clicking a placed object that has a town number is refused unless the player's `jsarcana.town` is the same.
- Spells cast by a ring in the off hand are the wearer's, like a wand's: the datapack gives them the wearer's
  `jsarcana.pid`, so the plugin checks them against the wearer's rights.
- Brooms are not checked (flying is not griefing), and items dropped into cauldrons (automation) are not checked.
- J's Arcana's own switch `#grief jsarcana.cfg` (0 turns all block-changing magic off) works on its own; the plugin
  does not read it.

A town-number comparison in the same style, run as the player against a placed object's marker:

```mcfunction
# @s = the player; the object's marker was tagged mypack.target beforehand
execute if score @e[type=marker,tag=mypack.target,limit=1] jsarcana.town matches 1.. unless score @s jsarcana.town = @e[type=marker,tag=mypack.target,limit=1] jsarcana.town run return run title @s actionbar {text:"That belongs to another town",color:"red"}
```

### Towny's permission cache

Every "may this player build here" answer comes from Towny's per-player permission cache
(`PlayerCacheUtil.getCachePermission`), the same cache Towny uses for its own block checks. The plugin does not
clear or refresh that cache; Towny is responsible for keeping it current. So the tags agree with what Towny itself
would decide at that moment, including any delay in Towny noticing a change. The town lookups for placed magic and
for `jsarcana.town` (`TownyAPI.getTown`) do not go through that cache.

### Performance

What runs, and how often:

- **Every tick**
  - Builder's Wand (only if the `jcts.bw.*` objectives exist): one score lookup per online player; a new or changed
    box costs one Towny check per chunk it overlaps (at most 25, since a box is at most 64 blocks wide).
  - J's Arcana (only if `jsarcana.sp.dg` exists): one pass over the online players to map `jsarcana.pid` to
    players, then one pass over every marker entity in every loaded world, with one score lookup each. Markers from
    other datapacks are visited too. Each spell in flight costs up to 25 Towny checks (the spot, 8 points at half
    radius, 16 at full radius; it stops at the first forbidden point).
- **Every `every.players-and-still-spells` ticks (5)**: up to 25 checks for each online player, each still spell,
  each Spell Focus and each Spell Keystone.
- **Every `every.builders-wand` ticks (10)**: each unchanged wand selection is checked again (up to 25 checks).
- **Every `every.objects` ticks (40)**: one town lookup for each online player and each object marker.

Raising the `every.*` values lowers the cost. Spells in flight are always checked every tick.

## Compatibility and limitations

- Built for Paper 26.3 with Towny 0.103.2.7 or newer. Folia is not supported; other server software is untested.
- Main scoreboard only; objectives must be created by the datapack.
- Only players and `minecraft:marker` entities are handled.
- Only Towny's BUILD permission is checked.
- `jsarcana.edge` is a sampled check (25 points at the entity's height), not a full scan.
- The wand check assumes Towny's default 16-block town blocks, works in the player's current world and at the
  player's height, and denies boxes more than 64 blocks wide without checking.
- Spells whose caster is offline are limited to wilderness.
- No commands, no permissions, no reload; the config is read at startup.
- The tags live on the entities. If you remove the plugin, players keep whatever tags they had last; clear them
  with `tag @a remove jsarcana.notown`, `tag @a remove jsarcana.edge` and `tag @a remove jcts.bw.deny` (`@a` only
  reaches players who are online), and set JPack's `#bw_towny jcts.bw.cfg` to 0.

## Changelog

1.4.0
- JPack's Builder's Wand: checks each selection box against Towny and writes `jcts.bw.ok` and the tag
  `jcts.bw.deny` (see "Builder's Wand box check"). New `every.builders-wand` in `config.yml` (10 ticks). Without
  JPack it does nothing; it works without the J's Arcana datapack too. Nothing else changed.

1.3.0
- Built for Paper 26.3: new Paper version string, Java 25, Towny's repository address fixed.
- Essence Batteries and Wind Anchors now get a town number, so people from other towns can't use them (before,
  anyone could right-click them in your town).
- Spell Keystones get `jsarcana.edge` like a Spell Focus (new `edge.keystone` in `config.yml`, 16 blocks: a field
  reaches 16 at most), so a keystone's field can't change blocks in land its town doesn't own. It is all or nothing:
  a keystone within that many blocks of land that isn't its own town's (wilderness included) changes no blocks at
  all, even inside its own town. Lower `edge.keystone` for keystones in small towns.

Earlier versions were not recorded.

## Credits

Written for JCreates' datapacks J's Arcana and JPack.

## License

No license has been chosen yet. Until one is added, no permission to copy, change or redistribute the code is
granted; ask the owner if you need it.
