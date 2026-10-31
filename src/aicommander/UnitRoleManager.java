package aicommander;

import arc.util.Log;
import mindustry.gen.*;

import java.util.List;

/**
 * UnitRoleManager dispatches incoming orders to the appropriate role handler.
 *
 * Roles:
 *   build   → BuilderRole
 *   defend  → DefenderRole
 *   attack  → ScoutRole
 *   repair  → (future, ignored for now)
 *   mine    → (future, ignored for now)
 *   produce → (future, ignored for now)
 */
public class UnitRoleManager{
    private final BuilderRole builder = new BuilderRole();
    private final DefenderRole defender = new DefenderRole();
    private final ScoutRole scout = new ScoutRole();

    /** Called on the main thread with the parsed order list from each LLM response. */
    public void applyOrders(List<Order> orders){
        for(Order order : orders){
            switch(order.type){
                case build   -> builder.addOrder(order);
                case defend  -> defender.addOrder(order);
                case attack  -> scout.addOrder(order);
                case repair, mine, produce -> Log.info(
                    "[AI Commander/Roles] order type '@' queued (not yet implemented)", order.type);
                case unknown -> Log.warn(
                    "[AI Commander/Roles] unknown order type in: @", order.raw);
            }
        }
    }

    /** Called every game tick from the main update loop. */
    public void update(){
        builder.update();
        defender.update();
        scout.update();
    }

    /** Called when the match ends or controller is stopped. */
    public void clear(){
        builder.clear();
        defender.clear();
        scout.clear();
    }

    // ── Accessors for HUD / logging ──────────────────────────────────────────

    public BuilderRole builder(){ return builder; }
    public DefenderRole defender(){ return defender; }
    public ScoutRole scout(){ return scout; }
}
