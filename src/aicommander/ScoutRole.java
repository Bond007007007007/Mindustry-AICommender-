package aicommander;

import arc.math.geom.Vec2;
import arc.util.Time;
import mindustry.ai.types.CommandAI;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Tile;

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
    private static final float MAX_WAYPOINT_AGE = 60f * 90f; // ~90s
    private static final float MIN_CORE_DISTANCE = 200f;

    private static class ScoutWaypoint{
        final Vec2 pos;
        final float added;

        ScoutWaypoint(Vec2 pos, float added){
            this.pos = pos;
            this.added = added;
        }
    }

    private final List<ScoutWaypoint> waypoints = new ArrayList<>();
    private float lastTick;

    public void addOrder(Order order){
        if(order.type != Order.Type.attack) return;
        Tile tile = world.tileWorld(order.x, order.y);
        if(tile == null || tile.solid()){
            arc.util.Log.info("[AI Commander/Scout] Ignored unreachable scout target at @,@.",
                (int)order.x, (int)order.y);
            return;
        }
        Vec2 pos = new Vec2(tile.worldx(), tile.worldy());
        Building core = player.closestCore();
        if(core != null && pos.dst(core.x, core.y) < MIN_CORE_DISTANCE){
            return;
        }
        for(ScoutWaypoint wp : waypoints){
            if(wp.pos.dst(pos) < 16f) return;
        }
        if(waypoints.size() >= MAX_WAYPOINTS){
            waypoints.remove(0);
        }
        waypoints.add(new ScoutWaypoint(pos, Time.time));
    }

    public void update(){
        update(false);
    }

    public void update(boolean urgentThreat){
        if(!state.isGame()) return;
        pruneExpired();
        if(waypoints.isEmpty()) return;
        // Do not siphon units away from an active defense.
        if(urgentThreat) return;
        if(Time.time - lastTick < TICK_INTERVAL) return;
        lastTick = Time.time;

        Team team = player.team();
        Vec2 target = waypoints.get(waypoints.size() - 1).pos; // latest order
        Building core = player.closestCore();
        if(core != null && target.dst(core.x, core.y) < MIN_CORE_DISTANCE) return;
        Unit playerUnit = player.unit();

        int assigned = 0;
        for(Unit unit : team.data().units){
            if(assigned >= MAX_SCOUTS) break;
            if(unit == null || !unit.isValid() || unit == playerUnit) continue;
            String name = unit.type.name;
            if(name.equals("mono") || name.equals("poly") || name.equals("mega")) continue;
            if(!(unit.controller() instanceof CommandAI ai)) continue;
            if(ai.targetPos != null) continue; // already moving

            ai.commandPosition(target.cpy());
            assigned++;
        }
    }

    private void pruneExpired(){
        for(int i = waypoints.size() - 1; i >= 0; i--){
            ScoutWaypoint wp = waypoints.get(i);
            boolean expired = Time.time - wp.added > MAX_WAYPOINT_AGE;
            Tile tile = world.tileWorld(wp.pos.x, wp.pos.y);
            boolean invalid = tile == null || tile.solid();
            if(expired || invalid){
                waypoints.remove(i);
            }
        }
    }

    public void clear(){
        waypoints.clear();
        lastTick = 0f;
    }

    public int waypointCount(){
        return waypoints.size();
    }
}
