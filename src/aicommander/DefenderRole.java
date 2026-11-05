package aicommander;

import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.Time;
import mindustry.ai.types.CommandAI;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.Turret;

import java.util.*;
import arc.func.Boolf;

import static mindustry.Vars.*;

/**
 * DefenderRole assigns units and player unit to defend key locations and respond to enemy incursions.
 *
 * Polished Mechanics:
 * 1. Tracks defend orders and core positions.
 * 2. If enemies are spotted within 300px of core or defenses, marks combat as urgent.
 * 3. Commands combat units (CommandAI) to intercept threats.
 * 4. If enemies approach the player unit, player unit can defensively kite and shoot back.
 */
public class DefenderRole{
    private static final int MAX_WAYPOINTS = 8;
    private static final int DEFENDERS_PER_POINT = 5;
    private static final float TICK_INTERVAL = 30f; // ~0.5s
    private static final float CORE_THREAT_RANGE = 650f;
    private static final float ANCHOR_THREAT_RANGE = 480f;
    private static final float ANCHOR_REFRESH = 300f; // ~5s
    private static final int MAX_ANCHORS = 8;
    private static final float COMMAND_EPSILON = 24f;

    private final List<Vec2> waypoints = new ArrayList<>();
    private final List<Vec2> anchors = new ArrayList<>();
    private final Map<Integer, Vec2> lastCommands = new HashMap<>();
    private final SquadManager squadManager = new SquadManager();
    private float lastTick;
    private float lastAnchorScan;
    private boolean urgentThreat = false;
    private boolean playerControlActive = false;
    private Unit priorityTarget;
    private Doctrine doctrine = Doctrine.defaultDoctrine();

    public void applyDoctrine(Doctrine doc){
        if(doc == null) return;
        this.doctrine = doc;
        squadManager.setDefenseFocus(doc.defenseFocus);

        // Apply squad stance from doctrine
        try{
            SquadManager.Stance stance = SquadManager.Stance.valueOf(doc.squadStance.toLowerCase(Locale.ROOT));
            squadManager.setStance(stance, null);
        }catch(Exception ignored){}
    }

    public void addOrder(Order order){
        if(order.type != Order.Type.defend) return;
        Tile tile = world.tileWorld(order.x, order.y);
        if(tile == null || tile.solid()){
            Log.info("[AI Commander/Defender] Ignored defend waypoint outside walkable map at @,@.",
                (int)order.x, (int)order.y);
            return;
        }
        Vec2 pos = new Vec2(tile.worldx(), tile.worldy());
        for(Vec2 wp : waypoints){
            if(wp.dst(pos) < 16f) return;
        }
        if(waypoints.size() >= MAX_WAYPOINTS){
            waypoints.remove(0);
        }
        waypoints.add(pos);
        lastAnchorScan = 0f;
    }

    public void update(){
        if(!state.isGame()) return;
        if(Time.time - lastTick < TICK_INTERVAL) return;
        lastTick = Time.time;

        Team team = player.team();
        Building core = player.closestCore();
        Unit playerUnit = player.unit();

        refreshAnchors(core);

        urgentThreat = false;
        Unit nearestEnemy = null;
        float bestDst = Float.MAX_VALUE;

        // Anchored threat scan: core, outlying turrets, and defend waypoints.
        for(Vec2 anchor : anchors){
            Unit threat = findThreat(team, anchor.x, anchor.y, ANCHOR_THREAT_RANGE);
            if(threat == null) continue;
            float dst = threat.dst(anchor);
            if(dst < bestDst){
                bestDst = dst;
                nearestEnemy = threat;
            }
            // Any threat at a defended outpost is urgent enough to rally.
            urgentThreat = true;
        }
        if(core != null){
            Unit coreThreat = findThreat(team, core.x, core.y, CORE_THREAT_RANGE);
            if(coreThreat != null){
                urgentThreat = true;
                float dst = coreThreat.dst(core);
                if(dst < bestDst){
                    bestDst = dst;
                    nearestEnemy = coreThreat;
                }
            }
        }

        // Check player unit combat assistance if under attack
        if(playerUnit != null && playerUnit.isValid()){
            Unit threatToPlayer = findThreat(team, playerUnit.x, playerUnit.y, 300f);
            if(threatToPlayer != null){
                urgentThreat = true;
                if(nearestEnemy == null || threatToPlayer.dst(playerUnit) < bestDst){
                    nearestEnemy = threatToPlayer;
                }
                // Defensive kiting: keep optimal range while firing
                if(playerUnit.hasWeapons()){
                    playerControlActive = true;
                    playerUnit.lookAt(threatToPlayer);
                    playerUnit.aim(threatToPlayer);
                    playerUnit.controlWeapons(true, true);

                    float dist = playerUnit.dst(threatToPlayer);
                    float idealDist = Math.max(80f, playerUnit.range() * 0.75f);
                    if(dist < idealDist){
                        // Back away from aggressive enemy
                        Vec2 dir = new Vec2(playerUnit.x - threatToPlayer.x, playerUnit.y - threatToPlayer.y).setLength(playerUnit.speed());
                        playerUnit.movePref(dir);
                    }
                }
            }else if(playerControlActive){
                // Restore normal player-unit behavior once the threat is gone.
                playerUnit.controlWeapons(false, false);
                playerControlActive = false;
            }
        }

        priorityTarget = nearestEnemy;

        // Update tactical combat squads with priority threat
        Unit squadTarget = nearestEnemy != null ? nearestEnemy : (playerUnit != null ? findThreat(team, playerUnit.x, playerUnit.y, 350f) : null);
        squadManager.update(urgentThreat, squadTarget);

        // Retreat overrides offensive interception: pull everything back to core.
        if(squadManager.getStance() == SquadManager.Stance.retreat){
            Vec2 fallback = core != null
                ? new Vec2(core.x, core.y)
                : (playerUnit != null ? new Vec2(playerUnit.x, playerUnit.y)
                    : new Vec2(world.width() * tilesize / 2f, world.height() * tilesize / 2f));
            for(Unit unit : team.data().units){
                if(unit == null || !unit.isValid() || unit == playerUnit) continue;
                if(unit.controller() instanceof CommandAI ai){
                    command(unit, ai, fallback);
                }
            }
            cleanupCommands();
            return;
        }

        // If urgent threat at core or player, rally defenders to intercept
        if(urgentThreat && squadTarget != null){
            for(Unit unit : team.data().units){
                if(unit == null || !unit.isValid() || unit == playerUnit) continue;
                if(unit.controller() instanceof CommandAI ai){
                    command(unit, ai, new Vec2(squadTarget.x, squadTarget.y));
                }
            }
            cleanupCommands();
            return;
        }

        if(waypoints.isEmpty()){
            cleanupCommands();
            return;
        }

        // Normal waypoint distribution. Units whose target no longer matches a
        // current waypoint (removed/expired waypoint) are treated as stale and
        // re-tasked instead of being ignored forever.
        int[] assigned = new int[waypoints.size()];
        Map<Integer, Integer> matched = new HashMap<>();
        for(Unit unit : team.data().units){
            if(unit == null || !unit.isValid() || unit == playerUnit) continue;
            if(!(unit.controller() instanceof CommandAI ai)) continue;
            if(ai.targetPos == null) continue;
            for(int i = 0; i < waypoints.size(); i++){
                Vec2 wp = waypoints.get(i);
                if(ai.targetPos.dst(wp.x, wp.y) < COMMAND_EPSILON){
                    matched.put(unit.id(), i);
                    assigned[i]++;
                    break;
                }
            }
        }

        for(Unit unit : team.data().units){
            if(unit == null || !unit.isValid() || unit == playerUnit) continue;
            if(!(unit.controller() instanceof CommandAI ai)) continue;

            Integer current = matched.get(unit.id());
            if(current != null && assigned[current] <= DEFENDERS_PER_POINT){
                continue;
            }
            if(current != null){
                // Overstaffed waypoint: release this unit and rebalance it.
                assigned[current]--;
            }

            int bestIndex = -1;
            int minAssigned = Integer.MAX_VALUE;
            for(int i = 0; i < waypoints.size(); i++){
                if(assigned[i] < DEFENDERS_PER_POINT && assigned[i] < minAssigned){
                    minAssigned = assigned[i];
                    bestIndex = i;
                }
            }
            if(bestIndex < 0) break;

            Vec2 wp = waypoints.get(bestIndex);
            // Force the command so a stale destination cannot suppress it.
            command(unit, ai, wp, true);
            matched.put(unit.id(), bestIndex);
            assigned[bestIndex]++;
        }
        cleanupCommands();
    }

    /** Rebuilds the list of defensive anchor points (core, waypoints, closest turrets). */
    private void refreshAnchors(Building core){
        if(Time.time - lastAnchorScan < ANCHOR_REFRESH && !anchors.isEmpty()) return;
        lastAnchorScan = Time.time;
        anchors.clear();

        if(core != null){
            anchors.add(new Vec2(core.x, core.y));
        }
        for(Vec2 wp : waypoints){
            anchors.add(wp.cpy());
        }

        List<Building> turrets = new ArrayList<>();
        for(Building b : player.team().data().buildings){
            if(b != null && b.isValid() && b.block instanceof Turret){
                turrets.add(b);
            }
        }
        final float cx = core != null ? core.x : 0f;
        final float cy = core != null ? core.y : 0f;
        turrets.sort(Comparator.comparingDouble(b -> b.dst2(cx, cy)));
        for(int i = 0; i < turrets.size() && anchors.size() < MAX_ANCHORS; i++){
            Building b = turrets.get(i);
            anchors.add(new Vec2(b.x, b.y));
        }
    }

    /** Commands a unit only when the destination has meaningfully changed. */
    private void command(Unit unit, CommandAI ai, Vec2 pos){
        command(unit, ai, pos, false);
    }

    private void command(Unit unit, CommandAI ai, Vec2 pos, boolean force){
        Vec2 last = lastCommands.get(unit.id());
        if(!force && last != null && last.dst(pos) < COMMAND_EPSILON) return;
        ai.commandPosition(pos.cpy());
        lastCommands.put(unit.id(), pos.cpy());
    }

    private void cleanupCommands(){
        if(lastCommands.size() > 256) lastCommands.clear();
    }

    /**
     * Selects the highest-priority threat near a position according to doctrine.
     * Focus-specific targets are preferred, then the closest remaining enemy.
     */
    private Unit findThreat(Team team, float x, float y, float range){
        Boolf<Unit> preferred = focusPredicate();
        if(preferred != null){
            Unit focused = Units.closestEnemy(team, x, y, range, preferred);
            if(focused != null) return focused;
        }
        return Units.closestEnemy(team, x, y, range, u -> true);
    }

    private Boolf<Unit> focusPredicate(){
        if(doctrine == null) return null;
        return switch(doctrine.defenseFocus){
            case ANTI_AIR -> Unit::isFlying;
            case GROUND_SWARM -> u -> !u.isFlying();
            case HEAVY_ARMOR -> u -> u.maxHealth() >= 700f;
            default -> null;
        };
    }

    public boolean isUrgentThreat(){
        return urgentThreat;
    }

    public void clear(){
        waypoints.clear();
        anchors.clear();
        lastCommands.clear();
        lastAnchorScan = 0f;
        squadManager.clear();
        urgentThreat = false;
        priorityTarget = null;
        playerControlActive = false;
    }

    public SquadManager squadManager(){
        return squadManager;
    }

    public int waypointCount(){
        return waypoints.size();
    }

    public Unit priorityTarget(){
        return priorityTarget;
    }

    public int anchorCount(){
        return anchors.size();
    }

    public List<Vec2> waypoints(){
        return waypoints;
    }
}
