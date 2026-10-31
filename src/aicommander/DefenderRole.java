package aicommander;

import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.Time;
import mindustry.ai.types.CommandAI;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.blocks.storage.CoreBlock;

import java.util.*;

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

    private final List<Vec2> waypoints = new ArrayList<>();
    private final SquadManager squadManager = new SquadManager();
    private float lastTick;
    private boolean urgentThreat = false;

    public void addOrder(Order order){
        if(order.type != Order.Type.defend) return;
        Vec2 pos = new Vec2(order.x, order.y);
        for(Vec2 wp : waypoints){
            if(wp.dst(pos) < 16f) return;
        }
        if(waypoints.size() >= MAX_WAYPOINTS){
            waypoints.remove(0);
        }
        waypoints.add(pos);
    }

    public void update(){
        if(!state.isGame()) return;
        if(Time.time - lastTick < TICK_INTERVAL) return;
        lastTick = Time.time;

        Team team = player.team();
        Building core = player.closestCore();

        // Check for nearby enemy threats
        urgentThreat = false;
        Unit nearestEnemy = null;
        if(core != null){
            // Find closest enemy to core or defenses
            nearestEnemy = Units.closestEnemy(team, core.x, core.y, 650f, u -> true);
            if(nearestEnemy != null){
                urgentThreat = true;
            }
        }

        // Check player unit combat assistance if under attack
        Unit playerUnit = player.unit();
        if(playerUnit != null && playerUnit.isValid()){
            Unit threatToPlayer = Units.closestEnemy(team, playerUnit.x, playerUnit.y, 300f, u -> true);
            if(threatToPlayer != null){
                urgentThreat = true;
                // Defensive kiting: keep optimal range while firing
                if(playerUnit.hasWeapons()){
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
            }
        }

        // Update tactical combat squads with priority threat
        Unit squadTarget = nearestEnemy != null ? nearestEnemy : (playerUnit != null ? Units.closestEnemy(team, playerUnit.x, playerUnit.y, 350f, u -> true) : null);
        squadManager.update(urgentThreat, squadTarget);

        // If urgent threat at core or player, rally defenders to intercept
        if(urgentThreat){
            Unit target = squadTarget;
            if(target != null){
                for(Unit unit : team.data().units){
                    if(unit == null || !unit.isValid() || unit == playerUnit) continue;
                    if(unit.controller() instanceof CommandAI ai){
                        ai.commandPosition(new Vec2(target.x, target.y));
                    }
                }
                return;
            }
        }

        if(waypoints.isEmpty()) return;

        // Normal waypoint distribution
        int[] assigned = new int[waypoints.size()];
        for(Unit unit : team.data().units){
            if(unit == null || !unit.isValid() || unit == playerUnit) continue;
            if(!(unit.controller() instanceof CommandAI ai)) continue;
            if(ai.targetPos == null) continue;
            for(int i = 0; i < waypoints.size(); i++){
                Vec2 wp = waypoints.get(i);
                if(ai.targetPos.dst(wp.x, wp.y) < 24f){
                    assigned[i]++;
                    break;
                }
            }
        }

        for(Unit unit : team.data().units){
            if(unit == null || !unit.isValid() || unit == playerUnit) continue;
            if(!(unit.controller() instanceof CommandAI ai)) continue;
            if(ai.targetPos != null) continue;

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
            ai.commandPosition(wp.cpy());
            assigned[bestIndex]++;
        }
    }

    public boolean isUrgentThreat(){
        return urgentThreat;
    }

    public void clear(){
        waypoints.clear();
        squadManager.clear();
        urgentThreat = false;
    }

    public SquadManager squadManager(){
        return squadManager;
    }

    public int waypointCount(){
        return waypoints.size();
    }
}
