package net.arcana.townybridge;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownyPermission;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps J's Arcana's town tags and scores up to date. The datapack never calls this plugin; it only reads what this
 * plugin writes:
 *
 *   jsarcana.notown (tag)   on players, Spell Foci and spell carriers standing where they may not change blocks
 *   jsarcana.edge   (tag)   on the same (and on Spell Keystones), when land they may not change is within reach
 *   jsarcana.town   (score) on players (their own town) and on the markers of things set down in the world (the town
 *                      whose land they're on); 0 means none / the wild
 *
 * and reads what the datapack writes on spell carriers: jsarcana.sp.dg (1 still, 2 in flight), jsarcana.sp.pid
 * (the caster's jsarcana.pid), and for spells cast by a Spell Focus or a rune the tag jsarcana.sp.focus with
 * jsarcana.sp.ox / .oy / .oz (the block they came from).
 *
 * Spells from a ring in the off hand (datapack build 49) are the wearer's, exactly like a wand's: the datapack gives
 * their carriers the wearer's jsarcana.pid in jsarcana.sp.pid, so nothing here tells them apart.
 *
 * For JCreates' Builder's Wand pack (namespace jc_bwand; since 1.4.0, renamed in 1.5.0), on players, it reads
 * jc_bwand.rev (the selection's revision, absent or 0 for none) and the box jc_bwand.x1 / .z1 / .x2 / .z2, and
 * writes jc_bwand.ok (the rev it last checked) and the tag jc_bwand.deny (some of the box is land that player may
 * not build in). Without the pack none of it exists and nothing happens.
 */
public final class ArcanaTownyBridge extends JavaPlugin {

    private static final String NOTOWN = "jsarcana.notown";
    private static final String EDGE = "jsarcana.edge";
    /** markers of things set down in the world that people click on (the datapack only lets a clicker of the same
     *  town use one that has a town number; Essence Batteries and Wind Anchors since 1.3.0). Spell Keystones and Field
     *  Rods are left out: the datapack already lets only whoever set them change them (jsarcana.ks.own), and a town
     *  number would lock out an owner who isn't a resident of that town. */
    private static final Set<String> OBJECTS = Set.of("jsarcana.pot", "jsarcana.tb", "jsarcana.jar", "jsarcana.rack", "jsarcana.ball", "jsarcana.hb", "jsarcana.carp", "jsarcana.sp.rune", "jsarcana.nx", "jsarcana.er", "jsarcana.bag",
            "jsarcana.bat", "jsarcana.wa");
    private static final String WAND_DENY = "jc_bwand.deny";
    /** a Builder's Wand box wider than this (in blocks, along x or z) is a bad score, not a selection: denied unchecked */
    private static final int WAND_MAX_SPAN = 64;

    private int playerEdge, focusEdge, keystoneEdge, stillEdge, flightEdge;
    private int slowEvery, objectEvery, wandEvery;
    private long tick;
    private boolean wandLogged;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        playerEdge = getConfig().getInt("edge.player", 16);
        focusEdge = getConfig().getInt("edge.focus", 16);
        keystoneEdge = getConfig().getInt("edge.keystone", 16);
        stillEdge = getConfig().getInt("edge.still-spell", 8);
        flightEdge = getConfig().getInt("edge.flying-spell", 12);
        slowEvery = Math.max(1, getConfig().getInt("every.players-and-still-spells", 5));
        objectEvery = Math.max(1, getConfig().getInt("every.objects", 40));
        wandEvery = Math.max(1, getConfig().getInt("every.builders-wand", 10));
        Bukkit.getScheduler().runTaskTimer(this, this::run, 1L, 1L);
    }

    // ---------------------------------------------------------------- the loop
    private void run() {
        tick++;
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        builderWand(sb);                              // the Builder's Wand pack's, so before the J's Arcana check below
        Objective dg = sb.getObjective("jsarcana.sp.dg");
        if (dg == null) return;                       // the datapack hasn't loaded yet
        Objective town = sb.getObjective("jsarcana.town");
        Objective pid = sb.getObjective("jsarcana.pid");
        Objective spid = sb.getObjective("jsarcana.sp.pid");
        boolean slow = tick % slowEvery == 0;
        boolean objects = town != null && tick % objectEvery == 0;

        Map<Integer, Player> byPid = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (pid != null) {
                Score s = pid.getScoreFor(p);
                if (s.isScoreSet()) byPid.put(s.getScore(), p);
            }
            if (slow) tagPlayer(p);
            if (objects) town.getScoreFor(p).setScore(residentTown(p));
        }

        for (World w : Bukkit.getWorlds()) {
            for (Marker m : w.getEntitiesByClass(Marker.class)) {
                Set<String> tags = m.getScoreboardTags();
                Score d = dg.getScoreFor(m);
                if (d.isScoreSet()) {
                    // a spell carrier: one in flight every tick, a still one less often
                    if (d.getScore() >= 2) tagSpell(m, sb, byPid, spid, flightEdge);
                    else if (slow) tagSpell(m, sb, byPid, spid, stillEdge);
                } else if (tags.contains("jsarcana.focus")) {
                    if (slow) tagFocus(m, focusEdge);
                } else if (tags.contains("jsarcana.ks")) {
                    // a Spell Keystone pulses its spell over its field (its rods stand within 16 blocks of it) as
                    // placed magic, like a Spell Focus: the datapack reads its tags through jsarcana:town/me. The tag is
                    // all or nothing: a tagged keystone's pulses change no blocks anywhere in its field
                    if (slow) tagFocus(m, keystoneEdge);
                }
                if (objects && isObject(tags)) town.getScoreFor(m).setScore(landTown(m.getLocation()));
            }
        }
    }

    private static boolean isObject(Set<String> tags) {
        for (String t : tags) if (OBJECTS.contains(t)) return true;
        return false;
    }

    // ---------------------------------------------------------------- who may change blocks where
    /** a rule saying whether blocks at a location may be changed */
    private interface Rule { boolean allows(Location at); }

    private static Rule playerRule(Player p) {
        return at -> PlayerCacheUtil.getCachePermission(p, at, Material.STONE, TownyPermission.ActionType.BUILD);
    }

    /** a Spell Focus's spells only touch its own town's land (or only the wild, if it stands in the wild) */
    private static Rule focusRule(Location focus) {
        Town home = TownyAPI.getInstance().getTown(focus);
        return at -> {
            Town there = TownyAPI.getInstance().getTown(at);
            return home == null ? there == null : home.equals(there);
        };
    }

    /** nobody to answer for it: only the wild */
    private static final Rule WILD_ONLY = at -> TownyAPI.getInstance().isWilderness(at);

    private void tagPlayer(Player p) {
        Rule r = playerRule(p);
        apply(p, !r.allows(p.getLocation()), near(p.getLocation(), playerEdge, r));
    }

    /** a Spell Focus or a Spell Keystone */
    private void tagFocus(Marker m, int margin) {
        Rule r = focusRule(m.getLocation());
        apply(m, false, near(m.getLocation(), margin, r));
    }

    private void tagSpell(Marker m, Scoreboard sb, Map<Integer, Player> byPid, Objective spid, int margin) {
        Rule r;
        if (m.getScoreboardTags().contains("jsarcana.sp.focus")) {
            r = focusRule(new Location(m.getWorld(), score(sb, "jsarcana.sp.ox", m), score(sb, "jsarcana.sp.oy", m), score(sb, "jsarcana.sp.oz", m)));
        } else {
            Player owner = spid == null ? null : byPid.get(spid.getScoreFor(m).getScore());
            r = owner == null ? WILD_ONLY : playerRule(owner);
        }
        apply(m, !r.allows(m.getLocation()), near(m.getLocation(), margin, r));
    }

    private static int score(Scoreboard sb, String objective, Entity e) {
        Objective o = sb.getObjective(objective);
        return o == null ? 0 : o.getScoreFor(e).getScore();
    }

    /** true if somewhere within the margin is land the rule forbids: the spot itself, then rings at half and full reach */
    private static boolean near(Location at, int margin, Rule r) {
        if (!r.allows(at)) return true;
        for (int ring = 1; ring <= 2; ring++) {
            double d = margin * ring / 2.0;
            int steps = ring * 8;
            for (int i = 0; i < steps; i++) {
                double a = Math.PI * 2 * i / steps;
                Location p = at.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d);
                if (!r.allows(p)) return true;
            }
        }
        return false;
    }

    private static void apply(Entity e, boolean notown, boolean edge) {
        set(e, NOTOWN, notown);
        set(e, EDGE, edge && !notown);
    }

    private static void set(Entity e, String tag, boolean on) {
        if (on) e.addScoreboardTag(tag);
        else e.removeScoreboardTag(tag);
    }

    // ---------------------------------------------------------------- the Builder's Wand pack (jc_bwand)
    /** checks a selection as soon as its rev is new (the datapack waits for ok == rev), and every held one again each
     *  every.builders-wand ticks, since towns change. Players without a selection cost one score lookup */
    private void builderWand(Scoreboard sb) {
        Objective rev = sb.getObjective("jc_bwand.rev");
        if (rev == null) return;                      // no Builder's Wand pack (or it hasn't loaded yet)
        Objective ok = sb.getObjective("jc_bwand.ok");
        Objective x1 = sb.getObjective("jc_bwand.x1"), z1 = sb.getObjective("jc_bwand.z1");
        Objective x2 = sb.getObjective("jc_bwand.x2"), z2 = sb.getObjective("jc_bwand.z2");
        if (ok == null || x1 == null || z1 == null || x2 == null || z2 == null) return;
        boolean again = tick % wandEvery == 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            Score r = rev.getScoreFor(p);
            int v = r.isScoreSet() ? r.getScore() : 0;
            if (v <= 0) {                             // no selection
                p.removeScoreboardTag(WAND_DENY);
                continue;
            }
            Score done = ok.getScoreFor(p);
            boolean fresh = !done.isScoreSet() || done.getScore() != v;
            if (!fresh && !again) continue;
            set(p, WAND_DENY, !wandAllows(p, x1.getScoreFor(p).getScore(), z1.getScoreFor(p).getScore(),
                    x2.getScoreFor(p).getScore(), z2.getScoreFor(p).getScore()));
            if (fresh) done.setScore(v);              // after the tag: ok == rev means the tag is right
        }
    }

    /** true if the player may build in all of the box, in their own world: one spot in each 16x16 chunk it touches
     *  (Towny rights go by town block, whatever the height) */
    private boolean wandAllows(Player p, int x1, int z1, int x2, int z2) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        if ((long) bx - ax >= WAND_MAX_SPAN || (long) bz - az >= WAND_MAX_SPAN) {
            if (!wandLogged) {
                wandLogged = true;
                getLogger().fine("Builder's Wand box of " + p.getName() + " is over " + WAND_MAX_SPAN + " blocks wide (x "
                        + x1 + ".." + x2 + ", z " + z1 + ".." + z2 + "): denied without checking. Not logged again.");
            }
            return false;
        }
        Rule rule = playerRule(p);
        World w = p.getWorld();
        double y = p.getLocation().getY();
        for (int cx = ax >> 4; cx <= bx >> 4; cx++) {
            for (int cz = az >> 4; cz <= bz >> 4; cz++) {
                if (!rule.allows(new Location(w, Math.max(ax, cx << 4), y, Math.max(az, cz << 4)))) return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- town numbers
    private static int residentTown(Player p) {
        Resident r = TownyAPI.getInstance().getResident(p);
        Town t = r == null ? null : r.getTownOrNull();
        return t == null ? 0 : id(t.getUUID());
    }

    private static int landTown(Location at) {
        Town t = TownyAPI.getInstance().getTown(at);
        return t == null ? 0 : id(t.getUUID());
    }

    /** a stable positive number for a town (0 is kept for "no town") */
    private static int id(UUID u) {
        return (int) (Math.floorMod(u.getMostSignificantBits() ^ u.getLeastSignificantBits(), 2_000_000_000L)) + 1;
    }
}
