package aicommander;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.content.Items;
import mindustry.gen.Unit;
import mindustry.graphics.*;
import mindustry.type.Item;

import static mindustry.Vars.*;

/**
 * Renders in-world tactical overlays (Trigger.draw):
 * - Target waypoints for squads (Air Interceptors, Ground Assault, Support)
 * - Defensive rally choke point & alert ring
 * - Queued conveyor/schematic construction vectors
 */
public class TacticalOverlay{
    private static final Item[] ORE_ITEMS = {
        Items.copper, Items.lead, Items.coal, Items.sand, Items.titanium
    };

    private final CommanderController ctrl;
    private final Vec2[] oreMarkers = new Vec2[ORE_ITEMS.length];
    private float lastOreScan = -300f;

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
            Color defCol = def.isUrgentThreat() ? Color.scarlet : Pal.command;
            Lines.stroke(1.8f, defCol);
            Lines.circle(defWp.x, defWp.y, 20f + (def.isUrgentThreat() ? Mathf.absin(Time.time, 4f, 5f) : 0f));
            Drawf.target(defWp.x, defWp.y, 10f, defCol);
        }

        // 3. Highlight the current priority threat and its approach vector.
        Unit threat = def.priorityTarget();
        if(threat != null && threat.isValid()){
            Lines.stroke(2f, Color.scarlet);
            Lines.circle(threat.x, threat.y, 12f + Mathf.absin(Time.time, 4f, 4f));
            Drawf.target(threat.x, threat.y, 6f, Color.scarlet);
            mindustry.gen.Building core = player.closestCore();
            if(core != null){
                Lines.stroke(1f, Color.scarlet.cpy().a(0.45f));
                Lines.line(core.x, core.y, threat.x, threat.y);
            }
        }

        // 4. Mark the closest reachable ore patch for each tracked resource.
        if(Time.time - lastOreScan > 120f){
            lastOreScan = Time.time;
            mindustry.gen.Building core = player.closestCore();
            for(int i = 0; i < ORE_ITEMS.length; i++){
                oreMarkers[i] = null;
                if(core == null) continue;
                mindustry.world.Tile ore = indexer.findClosestOre(core.x, core.y, ORE_ITEMS[i]);
                if(ore != null) oreMarkers[i] = new Vec2(ore.worldx(), ore.worldy());
            }
        }
        for(int i = 0; i < ORE_ITEMS.length; i++){
            Vec2 marker = oreMarkers[i];
            if(marker == null) continue;
            Lines.stroke(1.6f, ORE_ITEMS[i].color.cpy().a(0.8f));
            Lines.square(marker.x, marker.y, 5f);
        }

        // 5. Draw Construction queue previews
        BuilderRole bld = roles.builder();
        if(bld != null && bld.queueSize() > 0){
            for(BuilderRole.PlannedBlock pb : bld.peekQueue(16)){
                float px = pb.tx() * 8f;
                float py = pb.ty() * 8f;
                float size = pb.block().size * 8f;
                Lines.stroke(1.2f, Pal.accent.cpy().a(0.7f));
                Lines.rect(px - size / 2f + 4f, py - size / 2f + 4f, size, size);
                Drawf.target(px, py, 4f, Pal.accent.cpy().a(0.5f));
            }
        }

        Draw.reset();
    }
}
