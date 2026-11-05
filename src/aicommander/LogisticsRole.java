package aicommander;

import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.Time;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final Item[] MINABLE = {Items.copper, Items.lead, Items.coal, Items.titanium, Items.sand};

    private float lastTick;
    private Item preferredItem = Items.copper;
    private Doctrine doctrine = Doctrine.defaultDoctrine();
    private final Map<String, Tile> oreCache = new HashMap<>();
    private final Map<String, List<Tile>> oreListCache = new HashMap<>();
    private float lastOreScan;
    private Tile orderedTile;
    private Item orderedItem;

    public void applyDoctrine(Doctrine doc){
        if(doc == null) return;
        this.doctrine = doc;

        // Pick highest priority item based on doctrine weights
        Item bestItem = Items.copper;
        float bestWeight = -1f;

        for(Item item : MINABLE){
            float w = doc.getResourceWeight(item.name, 0.5f);
            if(w > bestWeight){
                bestWeight = w;
                bestItem = item;
            }
        }
        preferredItem = bestItem;
    }

    public void addOrder(Order order){
        if(order.type != Order.Type.mine) return;

        Item named = itemByName(order.schematic);
        if(named != null){
            preferredItem = named;
            orderedItem = named;
        }

        // An explicit coordinate wins over the item name: it may point at a
        // specific ore patch the Commander wants stripped first.
        if(order.x != 0f || order.y != 0f){
            Tile tile = world.tileWorld(order.x, order.y);
            if(tile != null && tile.drop() != null){
                orderedTile = tile;
                orderedItem = tile.drop();
                preferredItem = tile.drop();
                Log.info("[AI Commander/Logistics] Mine order targets @ at @,@.",
                    tile.drop().name, tile.x, tile.y);
                return;
            }
        }
        if(named != null){
            orderedTile = null;
            Log.info("[AI Commander/Logistics] Mine order targets @.", named.name);
        }
    }

    private static Item itemByName(String name){
        if(name == null || name.isBlank()) return null;
        String n = name.toLowerCase();
        for(Item item : MINABLE){
            if(n.equals(item.name) || n.contains(item.name)) return item;
        }
        return null;
    }

    private boolean orderedTileValid(){
        return orderedTile != null
            && orderedTile.drop() == orderedItem
            && orderedTile.block() == Blocks.air
            && orderedTile.build == null;
    }

    /** Called from main loop. Only operates if player unit is not building or fighting. */
    public void update(boolean isBuildingBusy, boolean isCombatBusy){
        if(!state.isGame() || isCombatBusy) return;
        if(Time.time - lastTick < TICK_INTERVAL) return;
        lastTick = Time.time;

        Building core = player.closestCore();
        if(core == null) return;

        Item targetItem = chooseTarget(core);

        // Direct AI miner units (Mono/Poly/Mega) toward the doctrine-selected ore.
        assignMiners(core, targetItem);

        if(isBuildingBusy) return;

        Unit u = player.unit();
        if(u == null || !u.isValid() || !u.canMine()) return;

        // If cargo is full (or nearly full), go drop it off at the nearest core
        // that can actually accept it. A full core should not trap the unit.
        if(u.stack.amount >= u.itemCapacity() && u.stack.amount > 0){
            u.mineTile = null;
            Building dropCore = depositCore(u);
            if(dropCore == null) return;
            if(!u.within(dropCore.x, dropCore.y, 34f)){
                Vec2 dir = new Vec2(dropCore.x - u.x, dropCore.y - u.y).limit(u.speed());
                u.movePref(dir);
            }else{
                // Deposit only what the core can accept. This avoids dropping
                // items on the ground when storage is full.
                int accepted = dropCore.acceptStack(u.stack.item, u.stack.amount, u);
                if(accepted > 0){
                    Call.transferItemTo(u, u.stack.item, accepted, u.x, u.y, dropCore);
                }
            }
            return;
        }

        if(u.mineTile != null && (u.mineTile.build != null || u.mineTile.block() != Blocks.air)){
            u.mineTile = null;
        }

        if(u.mineTile == null || u.mineTile.drop() != targetItem){
            Tile ore = targetItem == orderedItem && orderedTileValid()
                ? orderedTile : oreTile(core, targetItem);
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

    /** Nearest core with room for the unit's current cargo, else nearest core. */
    private Building depositCore(Unit unit){
        Building nearest = null;
        Building withRoom = null;
        float nearestDst = Float.MAX_VALUE;
        float roomDst = Float.MAX_VALUE;
        for(Building b : player.team().data().buildings){
            if(!(b instanceof CoreBlock.CoreBuild)) continue;
            float d = b.dst2(unit.x, unit.y);
            if(d < nearestDst){ nearestDst = d; nearest = b; }
            if(unit.stack.amount > 0 && b.acceptStack(unit.stack.item, unit.stack.amount, unit) > 0 && d < roomDst){
                roomDst = d;
                withRoom = b;
            }
        }
        return withRoom != null ? withRoom : nearest;
    }

    /**
     * Balances doctrine weight against current core stock so the highest-value
     * *scarce* resource is mined first instead of blindly following one item.
     */
    private Item chooseTarget(Building core){
        if(orderedItem != null && orderedTileValid()) return orderedItem;

        Item best = preferredItem;
        float bestScore = -1f;

        for(Item item : MINABLE){
            // Do not commit the player unit to an ore that is not present near
            // this core. Previously a high doctrine weight could paralyze mining.
            if(oreTile(core, item) == null) continue;

            float weight = doctrine.getResourceWeight(item.name, 0.3f);
            float stock = core.items.get(item);
            float target = targetStock(item);
            float deficit = Math.max(0f, Math.min(1f, 1f - stock / target));
            float score = weight * (0.35f + 0.65f * deficit);
            if(score > bestScore){
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private static float targetStock(Item item){
        if(item == Items.copper) return 600f;
        if(item == Items.lead) return 400f;
        if(item == Items.coal) return 300f;
        if(item == Items.titanium) return 250f;
        if(item == Items.sand) return 200f;
        return 200f;
    }

    private void assignMiners(Building core, Item targetItem){
        List<Unit> miners = new ArrayList<>();
        for(Unit unit : player.team().data().units){
            if(unit == null || !unit.isValid() || !unit.canMine()) continue;
            if(!(unit.controller() instanceof mindustry.ai.types.MinerAI)) continue;
            miners.add(unit);
        }
        if(miners.isEmpty()) return;

        List<Tile> tiles;
        if(targetItem == orderedItem && orderedTileValid()){
            tiles = new ArrayList<>();
            tiles.add(orderedTile);
            if(tiles.size() < miners.size()) tiles.addAll(oreTiles(core, targetItem, miners.size()));
        }else{
            tiles = oreTiles(core, targetItem, miners.size());
        }
        if(tiles.isEmpty()) return;

        Set<Long> used = new HashSet<>();
        int next = 0;
        for(Unit unit : miners){
            Tile current = unit.mineTile;
            if(current != null && current.drop() == targetItem
                && current.block() == Blocks.air && current.build == null
                && used.add((long)Point2.pack(current.x, current.y))){
                continue;
            }
            Tile pick = null;
            while(next < tiles.size()){
                Tile t = tiles.get(next++);
                if(used.add((long)Point2.pack(t.x, t.y))){ pick = t; break; }
            }
            if(pick != null){
                unit.mineTile = pick;
            }else if(current == null || current.drop() != targetItem){
                // More miners than known patches: sharing is better than idling.
                unit.mineTile = tiles.get(0);
            }
        }
    }

    /** Cached list of nearby ore tiles, nearest to the core first. */
    private List<Tile> oreTiles(Building core, Item item, int max){
        if(Time.time - lastOreScan > 300f){
            oreCache.clear();
            oreListCache.clear();
            lastOreScan = Time.time;
        }
        List<Tile> cached = oreListCache.get(item.name);
        if(cached != null){
            List<Tile> valid = new ArrayList<>();
            for(Tile t : cached){
                if(validOre(t, item)) valid.add(t);
                if(valid.size() >= max) break;
            }
            return valid;
        }

        int cx = core.tileX(), cy = core.tileY();
        int radius = 40;
        List<Tile> found = new ArrayList<>();
        for(int dx = -radius; dx <= radius; dx++){
            for(int dy = -radius; dy <= radius; dy++){
                Tile t = world.tile(cx + dx, cy + dy);
                if(validOre(t, item)) found.add(t);
            }
        }
        found.sort((a, b) -> Float.compare(
            a.dst2(core.x, core.y), b.dst2(core.x, core.y)));
        oreListCache.put(item.name, found);
        List<Tile> out = new ArrayList<>();
        for(Tile t : found){
            out.add(t);
            if(out.size() >= max) break;
        }
        return out;
    }

    private static boolean validOre(Tile t, Item item){
        return t != null && t.drop() == item && t.block() == Blocks.air && t.build == null;
    }

    /** Caches the closest ore tile for a few seconds to keep scans cheap. */
    private Tile oreTile(Building core, Item item){
        if(Time.time - lastOreScan > 300f){
            oreCache.clear();
            lastOreScan = Time.time;
        }
        String key = item.name;
        Tile cached = oreCache.get(key);
        if(cached != null && cached.drop() == item && cached.block() == Blocks.air && cached.build == null){
            return cached;
        }

        Tile ore = indexer.findClosestOre(core.x, core.y, item);
        if(ore != null && ore.block() == Blocks.air && ore.build == null){
            oreCache.put(key, ore);
            return ore;
        }
        oreCache.remove(key);
        return null;
    }

    public void clear(){
        preferredItem = Items.copper;
        oreCache.clear();
        lastOreScan = 0f;
    }
}
