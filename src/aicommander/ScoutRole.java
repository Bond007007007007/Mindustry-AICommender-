package aicommander;

import arc.math.geom.Vec2;
import mindustry.ai.types.CommandAI;
import mindustry.game.Team;
import mindustry.gen.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * ScoutRole sends a small number of units to explore/attack positions.
 *
 * Keeps the last N attack orders as scout destinations.
 * Sends at most MAX_SCOUTS units; prefers fast unit types.
 */
public class ScoutRole{
    private static final int MAX_WAYPOINTS = 4;
    private static final int MAX_SCOUTS = 3;
    private static final float TICK_INTERVAL = 120f; // ~2s

    private final List<Vec2> waypoints = new ArrayList<>();
    private float lastTick;

    public void addOrder(Order order){
        if(order.type != Order.Type.attack) return;
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
        if(!state.isGame() || waypoints.isEmpty()) return;
        if(Time.time - lastTick < TICK_INTERVAL) return;
        lastTick = Time.time;

        Team team = player.team();
        Vec2 target = waypoints.get(waypoints.size() - 1); // latest order

        int assigned = 0;
        for(Unit unit : team.data().units){
            if(assigned >= MAX_SCOUTS) break;
            if(unit == null || !unit.isValid()) continue;
            if(!(unit.controller() instanceof CommandAI ai)) continue;
            if(ai.targetPos != null) continue; // already moving

            ai.commandPosition(target.cpy());
            assigned++;
        }
    }

    public void clear(){
        waypoints.clear();
    }

    public int waypointCount(){
        return waypoints.size();
    }
}
