package aicommander;

import arc.math.geom.Vec2;
import arc.util.Time;
import mindustry.ai.types.CommandAI;
import mindustry.game.Team;
import mindustry.gen.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * DefenderRole assigns units to hold defensive positions ordered by the Commander.
 *
 * Design:
 * - Maintains a list of active defend waypoints (world pixel coords).
 * - Each update, picks units with CommandAI controllers that are idle (no active target)
 *   and assigns them to the nearest waypoint that needs more defenders.
 * - Does not reassign units that are already heading to a waypoint.
 * - Uses standard CommandAI.commandMove() — no cheating.
 */
public class DefenderRole{
    private static final int MAX_WAYPOINTS = 8;
    private static final int DEFENDERS_PER_POINT = 5;
    private static final float TICK_INTERVAL = 90f; // ~1.5s

    private final List<Vec2> waypoints = new ArrayList<>();
    private float lastTick;

    /** Add or replace the defend waypoints from new orders. */
    public void addOrder(Order order){
        if(order.type != Order.Type.defend) return;
        Vec2 pos = new Vec2(order.x, order.y);
        // Deduplicate by proximity (within 2 tiles).
        for(Vec2 wp : waypoints){
            if(wp.dst(pos) < 16f) return;
        }
        if(waypoints.size() >= MAX_WAYPOINTS){
            waypoints.remove(0);
        }
        waypoints.add(pos);
    }

    public void update(){
        if(!state.isGame() || waypoints.isEmpty()) return;
        if(Time.time - lastTick < TICK_INTERVAL) return;
        lastTick = Time.time;

        Team team = player.team();

        // Count how many units are already assigned to each waypoint.
        int[] assigned = new int[waypoints.size()];
        for(Unit unit : team.data().units){
            if(unit == null || !unit.isValid()) continue;
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

        // Assign idle units to under-defended waypoints.
        for(Unit unit : team.data().units){
            if(unit == null || !unit.isValid()) continue;
            if(!(unit.controller() instanceof CommandAI ai)) continue;

            // Only reassign truly idle units (no target pos or already near core).
            if(ai.targetPos != null) continue;

            // Find the waypoint most in need.
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

    public void clear(){
        waypoints.clear();
    }

    public int waypointCount(){
        return waypoints.size();
    }
}
