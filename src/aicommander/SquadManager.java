package aicommander;

import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.Time;
import mindustry.ai.types.CommandAI;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.gen.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * SquadManager organizes non-builder combat units into distinct tactical combat squads:
 * - Air Squad: fast interceptors (Flare, Horizon, Zenith)
 * - Ground Assault: heavy pushers (Dagger, Mace, Fortress)
 * - Support Escort: protective screen (Nova, Pulsar, etc.)
 *
 * Provides coordinated focus-fire, choke point positioning, and defensive rallying.
 */
public class SquadManager{
    public enum SquadType{
        air("Air Interceptors"),
        ground("Ground Assault"),
        support("Support Escort");

        public final String title;
        SquadType(String title){ this.title = title; }
    }

    public static class Squad{
        public final SquadType type;
        public final List<Unit> members = new ArrayList<>();
        public Vec2 targetPos = null;
        public Unit attackTarget = null;

        public Squad(SquadType type){
            this.type = type;
        }

        public void clear(){
            members.clear();
            attackTarget = null;
        }

        public int size(){
            return members.size();
        }
    }

    private final Map<SquadType, Squad> squads = new EnumMap<>(SquadType.class);
    private float lastSquadUpdate;

    public SquadManager(){
        for(SquadType t : SquadType.values()){
            squads.put(t, new Squad(t));
        }
    }

    public void update(boolean urgentThreat, Unit priorityThreat){
        if(!state.isGame()) return;
        if(Time.time - lastSquadUpdate < 30f) return; // ~0.5s
        lastSquadUpdate = Time.time;

        // Clean up and classify existing team units
        for(Squad s : squads.values()){
            s.members.clear();
        }

        Team team = player.team();
        Unit playerUnit = player.unit();

        for(Unit unit : team.data().units){
            if(unit == null || !unit.isValid() || unit == playerUnit) continue;
            // Exclude dedicated builder/miner units (mono, poly, mega)
            String name = unit.type.name;
            if(name.equals("mono") || name.equals("poly") || name.equals("mega")) continue;

            if(unit.isFlying()){
                squads.get(SquadType.air).members.add(unit);
            }else if(name.equals("nova") || name.equals("pulsar") || name.equals("quasar")){
                squads.get(SquadType.support).members.add(unit);
            }else{
                squads.get(SquadType.ground).members.add(unit);
            }
        }

        // Direct squads
        Building core = player.closestCore();
        for(Squad squad : squads.values()){
            if(squad.members.isEmpty()) continue;

            // Priority 1: Urgent threat
            if(urgentThreat && priorityThreat != null){
                squad.targetPos = new Vec2(priorityThreat.x, priorityThreat.y);
                squad.attackTarget = priorityThreat;
            }else if(squad.targetPos == null && core != null){
                // Default to defensive orbit around core
                squad.targetPos = new Vec2(core.x, core.y);
            }

            if(squad.targetPos != null){
                dispatchSquad(squad);
            }
        }
    }

    public void setSquadOrder(SquadType type, Vec2 target){
        Squad s = squads.get(type);
        if(s != null){
            s.targetPos = target;
        }
    }

    private void dispatchSquad(Squad squad){
        Vec2 center = squad.targetPos;
        if(center == null) return;

        int i = 0;
        int total = squad.members.size();
        float radius = Math.min(80f, 16f + total * 4f);

        for(Unit unit : squad.members){
            if(unit.controller() instanceof CommandAI ai){
                // Form a loose formation around target
                float angle = (360f / Math.max(1, total)) * i;
                float tx = center.x + arc.math.Angles.trnsx(angle, radius);
                float ty = center.y + arc.math.Angles.trnsy(angle, radius);
                ai.commandPosition(new Vec2(tx, ty));
            }
            i++;
        }
    }

    public int totalCombatUnits(){
        int count = 0;
        for(Squad s : squads.values()){
            count += s.members.size();
        }
        return count;
    }

    public Squad getSquad(SquadType type){
        return squads.get(type);
    }

    public void clear(){
        for(Squad s : squads.values()){
            s.clear();
        }
    }
}
