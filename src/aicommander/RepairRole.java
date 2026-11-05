package aicommander;

import arc.math.Mathf;
import arc.util.Log;
import arc.util.Time;
import mindustry.entities.Units;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.storage.CoreBlock;

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
    private static final float SCAN_RANGE = 800f;
    private float lastScan = 0f;
    private Building currentDamaged = null;

    public void update(boolean buildingBusy, boolean combatBusy){
        if(!state.isGame()) return;
        if(buildingBusy || combatBusy) return;

        Unit playerUnit = player.unit();
        if(playerUnit == null || !playerUnit.isValid() || !playerUnit.canBuild()) return;

        if(Time.time - lastScan > SCAN_INTERVAL){
            lastScan = Time.time;
            currentDamaged = findRepairTarget(playerUnit, player.closestCore());
        }

        if(currentDamaged == null || !currentDamaged.isValid() || currentDamaged.health >= currentDamaged.maxHealth - 1f){
            currentDamaged = null;
            return;
        }

        // Move player unit within repair range and repair
        float buildRange = Math.max(8f, playerUnit.type.buildRange - 16f);
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

    /**
     * Picks the most valuable damaged building near the player. Turrets, the
     * core, and blocks close to the core are repaired before distant walls.
     */
    private Building findRepairTarget(Unit playerUnit, Building core){
        Building best = null;
        float bestScore = Float.NEGATIVE_INFINITY;

        for(Building b : player.team().data().buildings){
            if(b == null || !b.isValid() || b instanceof ConstructBuild) continue;
            if(b.health >= b.maxHealth - 1f) continue;
            float dist = playerUnit.dst(b);
            if(dist > SCAN_RANGE) continue;

            float damage = 1f - b.health / b.maxHealth;
            float score = damage * 40f;
            if(b.block instanceof Turret) score += 50f;
            if(b.block instanceof CoreBlock) score += 60f;
            if(b.block instanceof Wall) score += 25f;
            if(core != null){
                float coreDst = b.dst(core);
                if(coreDst < 320f) score += 25f * (1f - coreDst / 320f);
            }
            score -= dist / 50f;

            if(score > bestScore){
                bestScore = score;
                best = b;
            }
        }
        return best;
    }

    public void clear(){
        currentDamaged = null;
    }
}
