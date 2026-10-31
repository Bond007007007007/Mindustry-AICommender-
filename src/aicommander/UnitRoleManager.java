package aicommander;

import arc.util.Log;
import mindustry.gen.*;

import java.util.List;

import static mindustry.Vars.*;

/**
 * UnitRoleManager coordinates orders across roles:
 * - BuilderRole: handles construction sites & drives builder units/player unit
 * - DefenderRole: manages defensive rally points & emergency combat response
 * - ScoutRole: sends exploratory/assault groups
 * - LogisticsRole: handles auto-mining & item logistics when builders are idle
 */
public class UnitRoleManager{
    private final BuilderRole builder = new BuilderRole();
    private final DefenderRole defender = new DefenderRole();
    private final ScoutRole scout = new ScoutRole();
    private final LogisticsRole logistics = new LogisticsRole();

    public void applyOrders(List<Order> orders){
        for(Order order : orders){
            switch(order.type){
                case build   -> builder.addOrder(order);
                case defend  -> defender.addOrder(order);
                case attack  -> scout.addOrder(order);
                case mine    -> logistics.addOrder(order);
                case repair, produce -> Log.info(
                    "[AI Commander/Roles] order type '@' received", order.type);
                case unknown -> Log.warn(
                    "[AI Commander/Roles] unknown order type in: @", order.raw);
            }
        }
    }

    public void update(){
        builder.update();
        defender.update();
        scout.update();

        // Check if player unit is busy building or fighting
        boolean buildingBusy = builder.queueSize() > 0 || (player.unit() != null && player.unit().buildPlan() != null);
        boolean combatBusy = defender.isUrgentThreat();

        // If not busy building or fighting, perform autonomous logistics / mining
        logistics.update(buildingBusy, combatBusy);
    }

    public void clear(){
        builder.clear();
        defender.clear();
        scout.clear();
        logistics.clear();
    }

    public BuilderRole builder(){ return builder; }
    public DefenderRole defender(){ return defender; }
    public ScoutRole scout(){ return scout; }
    public LogisticsRole logistics(){ return logistics; }
}
