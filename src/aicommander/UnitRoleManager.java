package aicommander;

import arc.util.Log;
import mindustry.gen.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * UnitRoleManager coordinates orders across roles:
 * - BuilderRole: handles construction sites & drives builder units/player unit
 * - DefenderRole: manages defensive rally points & emergency combat response
 * - ScoutRole: sends exploratory/assault groups
 * - LogisticsRole: handles auto-mining & item logistics when builders are idle
 * - RepairRole: actively seeks and repairs damaged player buildings
 */
public class UnitRoleManager{
    private final BuilderRole builder = new BuilderRole();
    private final DefenderRole defender = new DefenderRole();
    private final ScoutRole scout = new ScoutRole();
    private final LogisticsRole logistics = new LogisticsRole();
    private final RepairRole repair = new RepairRole();
    private Doctrine doctrine = Doctrine.defaultDoctrine();

    public void applyDoctrine(Doctrine doc){
        if(doc == null) return;
        this.doctrine = doc;
        logistics.applyDoctrine(doc);
        defender.applyDoctrine(doc);
        builder.applyDoctrine(doc);
        Log.info("[AI Commander/Roles] Applied doctrine: posture=@, defense=@, squadStance=@",
            doc.posture, doc.defenseFocus, doc.squadStance);
    }

    public void applyOrders(List<Order> orders){
        for(Order order : orders){
            switch(order.type){
                case build   -> builder.addOrder(order);
                case defend  -> defender.addOrder(order);
                case attack  -> scout.addOrder(order);
                case mine    -> logistics.addOrder(order);
                case produce -> handleProduce(order);
                case stance  -> handleStance(order);
                case repair  -> Log.info(
                    "[AI Commander/Roles] order type '@' received", order.type);
                case unknown -> Log.warn(
                    "[AI Commander/Roles] unknown order type in: @", order.raw);
            }
        }
    }

    private void handleProduce(Order order){
        if(order.unitType == null || order.unitType.isEmpty()) return;
        // Search player team buildings for UnitFactory
        for(Building b : player.team().data().buildings){
            if(b instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild factory){
                mindustry.world.blocks.units.UnitFactory block = (mindustry.world.blocks.units.UnitFactory)factory.block;
                for(int i = 0; i < block.plans.size; i++){
                    if(block.plans.get(i).unit.name.equalsIgnoreCase(order.unitType)){
                        factory.currentPlan = i;
                        Log.info("[AI Commander/Roles] Set factory at @,@ to produce @", factory.tileX(), factory.tileY(), order.unitType);
                        return;
                    }
                }
            }
        }
    }

    private void handleStance(Order order){
        if(order.stance == null) return;
        try{
            SquadManager.Stance stance = SquadManager.Stance.valueOf(order.stance.toLowerCase(Locale.ROOT));
            arc.math.geom.Vec2 target = (order.x != 0f || order.y != 0f) ? new arc.math.geom.Vec2(order.x, order.y) : null;
            defender.squadManager().setStance(stance, target);
            Log.info("[AI Commander/Roles] Set squad tactical stance: @ @", stance, target != null ? target : "");
        }catch(Exception e){
            Log.warn("[AI Commander/Roles] Unknown stance: @", order.stance);
        }
    }

    public void update(){
        builder.update();
        defender.update();
        boolean combatBusy = defender.isUrgentThreat();
        scout.update(combatBusy);

        // Check if player unit is busy building or fighting
        boolean buildingBusy = builder.queueSize() > 0 || (player.unit() != null && player.unit().buildPlan() != null);

        // If not busy building or fighting, prioritize repairing damaged defenses
        repair.update(buildingBusy, combatBusy);
        boolean repairBusy = repair.hasRepairTarget();

        // If not busy building, fighting, or repairing, perform autonomous logistics / mining
        logistics.update(buildingBusy || repairBusy, combatBusy);
    }

    public void clear(){
        builder.clear();
        defender.clear();
        scout.clear();
        logistics.clear();
        repair.clear();
    }

    public BuilderRole builder(){ return builder; }
    public DefenderRole defender(){ return defender; }
    public ScoutRole scout(){ return scout; }
    public LogisticsRole logistics(){ return logistics; }
    public RepairRole repair(){ return repair; }
    public Doctrine doctrine(){ return doctrine; }
}
