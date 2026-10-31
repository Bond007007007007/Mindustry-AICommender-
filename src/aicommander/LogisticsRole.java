package aicommander;

import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.Time;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;

import static mindustry.Vars.*;

/**
 * LogisticsRole handles autonomous resource gathering and core supply:
 * 1. Supports "mine" orders from the Commander for target resources.
 * 2. When idle (no construction needed and no enemies attacking), autonomous mining:
 *    - Finds closest copper/lead deposit.
 *    - Drives player unit (Alpha/Beta/Gamma) or miner units to mine.
 *    - When cargo is full, returns to Core and transfers cargo into storage via Call.transferItemTo.
 */
public class LogisticsRole{
    private static final float TICK_INTERVAL = 30f; // ~0.5s
    private float lastTick;
    private Item preferredItem = Items.copper;

    public void addOrder(Order order){
        if(order.type != Order.Type.mine) return;
        // Check if schematic or note specifies an item
        if(order.schematic != null && order.schematic.contains("lead")){
            preferredItem = Items.lead;
        }else if(order.schematic != null && order.schematic.contains("coal")){
            preferredItem = Items.coal;
        }else{
            preferredItem = Items.copper;
        }
    }

    /** Called from main loop. Only operates if player unit is not building or fighting. */
    public void update(boolean isBuildingBusy, boolean isCombatBusy){
        if(!state.isGame() || isBuildingBusy || isCombatBusy) return;
        if(Time.time - lastTick < TICK_INTERVAL) return;
        lastTick = Time.time;

        Unit u = player.unit();
        if(u == null || !u.isValid() || !u.canMine()) return;

        Building core = u.closestCore();
        if(core == null) return;

        // If cargo is full (or nearly full), go drop it off at Core
        if(u.stack.amount >= u.itemCapacity() && u.stack.amount > 0){
            u.mineTile = null;
            if(!u.within(core.x, core.y, 30f)){
                Vec2 dir = new Vec2(core.x - u.x, core.y - u.y).limit(u.speed());
                u.movePref(dir);
            }else{
                // Deposit items to core
                Call.transferItemTo(u, u.stack.item, u.stack.amount, u.x, u.y, core);
            }
            return;
        }

        // Otherwise, mine resource
        // If core is low on copper, mine copper; if low on lead, mine lead
        Item targetItem = preferredItem;
        if(core.items.get(Items.copper) < 300){
            targetItem = Items.copper;
        }else if(core.items.get(Items.lead) < 200){
            targetItem = Items.lead;
        }

        if(u.mineTile == null || u.mineTile.drop() != targetItem){
            Tile ore = indexer.findClosestOre(core.x, core.y, targetItem);
            if(ore != null){
                u.mineTile = ore;
            }
        }

        if(u.mineTile != null){
            if(!u.within(u.mineTile.worldx(), u.mineTile.worldy(), u.type.mineRange - 15f)){
                Vec2 dir = new Vec2(u.mineTile.worldx() - u.x, u.mineTile.worldy() - u.y).limit(u.speed());
                u.movePref(dir);
            }
        }
    }

    public void clear(){
        preferredItem = Items.copper;
    }
}
