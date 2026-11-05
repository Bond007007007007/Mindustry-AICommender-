package aicommander;

import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.Time;
import mindustry.ai.types.CommandAI;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.gen.*;

import java.util.*;
import arc.func.Boolf;

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
        private Vec2 lastTargetPos = null;
        private Unit lastAttackTarget = null;
        private int lastMemberCount = -1;

        public Squad(SquadType type){
            this.type = type;
        }

        public void clear(){
            members.clear();
            attackTarget = null;
            targetPos = null;
            lastTargetPos = null;
            lastAttackTarget = null;
            lastMemberCount = -1;
        }

        public int size(){
            return members.size();
        }
    }

    private final Map<SquadType, Squad> squads = new EnumMap<>(SquadType.class);
    private float lastSquadUpdate;
    private Doctrine.DefenseFocus defenseFocus = Doctrine.DefenseFocus.BALANCED;

    public void setDefenseFocus(Doctrine.DefenseFocus focus){
        this.defenseFocus = focus == null ? Doctrine.DefenseFocus.BALANCED : focus;
    }

    public Doctrine.DefenseFocus getDefenseFocus(){
        return defenseFocus;
    }

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

            // Priority 1: retreat overrides offensive orders and falls back to core.
            if(stance == Stance.retreat){
                squad.attackTarget = null;
                if(core != null){
                    squad.targetPos = new Vec2(core.x, core.y);
                }else if(playerUnit != null && playerUnit.isValid()){
                    squad.targetPos = new Vec2(playerUnit.x, playerUnit.y);
                }else{
                    squad.targetPos = new Vec2(world.width() * tilesize / 2f, world.height() * tilesize / 2f);
                }
            }else if(urgentThreat && priorityThreat != null){
                squad.targetPos = new Vec2(priorityThreat.x, priorityThreat.y);
                squad.attackTarget = priorityThreat;
            }else if(stance == Stance.assault){
                // Hunt down the closest focused enemy; avoid pulling the army across the map.
                float cx = core != null ? core.x : 0f;
                float cy = core != null ? core.y : 0f;
                Unit closestEnemy = selectTarget(team, cx, cy, 1400f);
                if(closestEnemy != null){
                    squad.targetPos = new Vec2(closestEnemy.x, closestEnemy.y);
                    squad.attackTarget = closestEnemy;
                }else if(rallyPoint != null){
                    squad.targetPos = rallyPoint;
                }
            }else if(stance == Stance.rally && rallyPoint != null){
                squad.targetPos = rallyPoint;
            }else if(core != null){
                // Default to defensive orbit around core
                squad.targetPos = new Vec2(core.x, core.y);
            }

            if(squad.targetPos != null){
                dispatchSquad(squad);
            }
        }
    }

    public enum Stance{
        defend("Defend Core"),
        rally("Rally Waypoint"),
        assault("Aggressive Assault"),
        retreat("Emergency Fallback");

        public final String title;
        Stance(String title){ this.title = title; }
    }

    private Stance stance = Stance.defend;
    private Vec2 rallyPoint = null;

    public void setStance(Stance newStance, Vec2 point){
        this.stance = newStance;
        if(point != null){
            this.rallyPoint = point.cpy();
        }
    }

    public Stance getStance(){
        return stance;
    }

    public Vec2 getRallyPoint(){
        return rallyPoint;
    }

    private Unit selectTarget(Team team, float x, float y, float range){
        Boolf<Unit> preferred = focusPredicate();
        if(preferred != null){
            Unit focused = Units.closestEnemy(team, x, y, range, preferred);
            if(focused != null) return focused;
        }
        return Units.closestEnemy(team, x, y, range, u -> true);
    }

    private Boolf<Unit> focusPredicate(){
        if(defenseFocus == null) return null;
        return switch(defenseFocus){
            case ANTI_AIR -> Unit::isFlying;
            case GROUND_SWARM -> u -> !u.isFlying();
            case HEAVY_ARMOR -> u -> u.maxHealth() >= 700f;
            default -> null;
        };
    }

    private void dispatchSquad(Squad squad){
        Vec2 center = squad.targetPos;
        if(center == null) return;

        int total = squad.members.size();
        boolean changed = squad.lastTargetPos == null
            || squad.lastTargetPos.dst(center) > 24f
            || squad.lastAttackTarget != squad.attackTarget
            || squad.lastMemberCount != total;
        if(!changed) return;

        squad.lastTargetPos = center.cpy();
        squad.lastAttackTarget = squad.attackTarget;
        squad.lastMemberCount = total;

        int i = 0;
        float radius = formationRadius(squad.type, total);
        float offset = formationAngleOffset(squad.type);

        for(Unit unit : squad.members){
            if(unit.controller() instanceof CommandAI ai){
                // Form a loose formation around target, with per-squad spacing.
                float angle = offset + (360f / Math.max(1, total)) * i;
                float tx = center.x + arc.math.Angles.trnsx(angle, radius);
                float ty = center.y + arc.math.Angles.trnsy(angle, radius);
                ai.commandPosition(new Vec2(tx, ty));
            }
            i++;
        }
    }

    /** Air squads spread wide, ground squads stay tight, support sits behind. */
    private float formationRadius(SquadType type, int total){
        return switch(type){
            case air -> Math.min(110f, 40f + total * 8f);
            case ground -> Math.min(84f, 24f + total * 5f);
            case support -> Math.min(96f, 32f + total * 6f);
        };
    }

    private float formationAngleOffset(SquadType type){
        return switch(type){
            case air -> 0f;
            case ground -> 45f;
            case support -> 90f;
        };
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

    public Collection<Squad> allSquads(){
        return squads.values();
    }

    public void clear(){
        for(Squad s : squads.values()){
            s.clear();
        }
    }
}
