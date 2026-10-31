package aicommander;

import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.Time;
import mindustry.content.*;
import mindustry.entities.Units;
import mindustry.gen.*;
import mindustry.world.Block;
import mindustry.world.Tile;

import static mindustry.Vars.*;

/**
 * FallbackAdvisor provides instant heuristic rules if the remote LLM is offline,
 * timing out, or returning consecutive errors.
 * Ensures the colony never stalls and continues surviving automatically.
 */
public class FallbackAdvisor{
    private float lastCheck;

    public void update(CommanderController controller, UnitRoleManager roles){
        if(!Settings.autoFallback()) return;
        if(Time.time - lastCheck < 300f) return; // run check every ~5 seconds
        lastCheck = Time.time;

        Building core = player.closestCore();
        if(core == null) return;

        // Condition 1: If builder queue is completely empty and player has resources, ensure basic economy
        if(roles.builder().queueSize() == 0 && (player.unit() == null || player.unit().buildPlan() == null)){
            int copper = core.items.get(Items.copper);
            int lead = core.items.get(Items.lead);

            // Check if copper drill exists near core
            Tile copperOre = indexer.findClosestOre(core.x, core.y, Items.copper);
            if(copperOre != null && copper >= 25){
                if(copperOre.build == null && copperOre.block() == Blocks.air){
                    roles.builder().addOrder(Order.build("copper-drill", copperOre.worldx(), copperOre.worldy(), 0));
                    // Route conveyor back toward core
                    roles.builder().addOrder(Order.buildLine("conveyor", copperOre.worldx(), copperOre.worldy(), core.x, core.y));
                    Log.info("[AI Commander/Fallback] Queued basic copper drill & conveyor line.");
                    return;
                }
            }

            // Check if lead drill exists
            Tile leadOre = indexer.findClosestOre(core.x, core.y, Items.lead);
            if(leadOre != null && copper >= 25 && leadOre.build == null && leadOre.block() == Blocks.air){
                roles.builder().addOrder(Order.build("copper-drill", leadOre.worldx(), leadOre.worldy(), 0));
                roles.builder().addOrder(Order.buildLine("conveyor", leadOre.worldx(), leadOre.worldy(), core.x, core.y));
                Log.info("[AI Commander/Fallback] Queued basic lead drill & conveyor line.");
                return;
            }

            // Check if duo defense exists near core facing enemy spawn
            if(copper >= 35){
                int duoCount = 0;
                for(Building b : player.team().data().buildings){
                    if(b.block == Blocks.duo) duoCount++;
                }
                if(duoCount < 3){
                    // Place Duo between core and nearest spawn
                    Tile spawn = spawner.getSpawns().firstOpt();
                    float targetX = core.x;
                    float targetY = core.y;
                    if(spawn != null){
                        float dx = spawn.worldx() - core.x;
                        float dy = spawn.worldy() - core.y;
                        float len = (float)Math.sqrt(dx * dx + dy * dy);
                        if(len > 0){
                            targetX += (dx / len) * (48f + duoCount * 16f);
                            targetY += (dy / len) * (48f + duoCount * 16f);
                        }
                    }else{
                        targetX += 48f + duoCount * 16f;
                    }
                    roles.builder().addOrder(Order.build("duo", targetX, targetY, 0));
                    roles.builder().addOrder(Order.build("copper-wall", targetX + 8f, targetY, 0));
                    Log.info("[AI Commander/Fallback] Queued defensive Duo turret.");
                    return;
                }
            }
        }
    }
}
