package aicommander;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.graphics.*;

import static mindustry.Vars.*;

/**
 * Renders in-world tactical overlays (Trigger.draw):
 * - Target waypoints for squads (Air Interceptors, Ground Assault, Support)
 * - Defensive rally choke point & alert ring
 * - Queued conveyor/schematic construction vectors
 */
public class TacticalOverlay{
    private final CommanderController ctrl;

    public TacticalOverlay(CommanderController ctrl){
        this.ctrl = ctrl;
    }

    public void draw(){
        if(ctrl == null || !ctrl.isActive() || !state.isGame()) return;
        if(!Settings.overlayDebug()) return;

        UnitRoleManager roles = ctrl.roles();
        if(roles == null) return;

        Draw.z(Layer.overlayUI);

        // 1. Draw Squad Waypoints & Intercept Vectors
        SquadManager squads = roles.defender().squadManager();
        if(squads != null){
            for(SquadManager.Squad squad : squads.allSquads()){
                if(squad.members.isEmpty() || squad.targetPos == null) continue;
                Vec2 wp = squad.targetPos;
                Color col = squad.type == SquadManager.SquadType.air ? Pal.accent :
                            squad.type == SquadManager.SquadType.ground ? Pal.bulletYellowBack : Pal.heal;

                // Draw pulsing squad beacon
                Lines.stroke(1.5f, col);
                Lines.circle(wp.x, wp.y, 14f + Mathf.absin(Time.time, 5f, 3f));
                Drawf.target(wp.x, wp.y, 8f, col);
            }
        }

        // 2. Draw Defensive Choke Point Waypoints & Alert Zone
        DefenderRole def = roles.defender();
        for(Vec2 defWp : def.waypoints()){
            Color defCol = def.isUrgentThreat() ? Pal.scarlet : Pal.command;
            Lines.stroke(1.8f, defCol);
            Lines.circle(defWp.x, defWp.y, 20f + (def.isUrgentThreat() ? Mathf.absin(Time.time, 4f, 5f) : 0f));
            Drawf.target(defWp.x, defWp.y, 10f, defCol);
        }

        // 3. Draw Construction queue previews
        BuilderRole bld = roles.builder();
        if(bld != null && bld.queueSize() > 0){
            Lines.stroke(1f, Pal.accent.cpy().a(0.6f));
            for(BuilderRole.PlannedBlock pb : bld.peekQueue(12)){
                float px = pb.tx() * 8f;
                float py = pb.ty() * 8f;
                float size = pb.block().size * 8f;
                Lines.rect(px - size / 2f + 4f, py - size / 2f + 4f, size, size);
            }
        }

        Draw.reset();
    }
}
