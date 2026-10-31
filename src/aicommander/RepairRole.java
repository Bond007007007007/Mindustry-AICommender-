package aicommander;

import arc.math.Mathf;
import arc.util.Log;
import arc.util.Time;
import mindustry.entities.Units;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;

import static mindustry.Vars.*;

/**
 * RepairRole manages automated repairs of damaged structures and player team defenses.
 *
 * Responsibilities:
 * 1. Tracks explicit "repair" orders from the Commander.
 * 2. Continuously searches for damaged player buildings within reach.
 * 3. Dispatches the player unit (when not busy constructing) to repair damaged walls/turrets.
 * 4. Ensures defensive lines stay functional during and after enemy waves.
 */
public class RepairRole{
    private static final float SCAN_INTERVAL = 30f; // every 0.5s
    private float lastScan = 0f;
    private Building currentDamaged = null;

    public void update(boolean buildingBusy, boolean combatBusy){
        if(!state.isGame()) return;
        if(buildingBusy || combatBusy) return;

        Unit playerUnit = player.unit();
        if(playerUnit == null || !playerUnit.isValid() || !playerUnit.canBuild()) return;

        if(Time.time - lastScan > SCAN_INTERVAL){
            lastScan = Time.time;
            currentDamaged = Units.findDamagedTile(player.team(), playerUnit.x, playerUnit.y);
            if(currentDamaged instanceof ConstructBuild) currentDamaged = null;
        }

        if(currentDamaged == null || !currentDamaged.isValid() || currentDamaged.health >= currentDamaged.maxHealth - 1f){
            currentDamaged = null;
            return;
        }

        // Move player unit within repair range and repair
        float buildRange = playerUnit.type.buildRange - 16f;
        if(!playerUnit.within(currentDamaged, buildRange)){
            arc.math.geom.Vec2 dir = new arc.math.geom.Vec2(currentDamaged.x - playerUnit.x, currentDamaged.y - playerUnit.y).limit(playerUnit.speed());
            playerUnit.movePref(dir);
            playerUnit.lookAt(currentDamaged);
        }else{
            // Within repair range: queue repair build plan if not already active
            if(playerUnit.buildPlan() == null || playerUnit.buildPlan().x != currentDamaged.tileX() || playerUnit.buildPlan().y != currentDamaged.tileY()){
                playerUnit.clearBuilding();
                playerUnit.addBuild(new BuildPlan(currentDamaged.tileX(), currentDamaged.tileY(), currentDamaged.rotation, currentDamaged.block));
            }
            playerUnit.lookAt(currentDamaged);
        }
    }

    public boolean hasRepairTarget(){
        return currentDamaged != null && currentDamaged.isValid() && currentDamaged.health < currentDamaged.maxHealth - 1f;
    }

    public void clear(){
        currentDamaged = null;
    }
}
