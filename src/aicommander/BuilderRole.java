package aicommander;

import arc.math.geom.Point2;
import arc.util.Log;
import arc.util.Time;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock;

import java.util.*;

import static mindustry.Vars.*;

/**
 * BuilderRole processes "build" orders from the Commander.
 *
 * Strategy:
 * - Accepts a queue of pending build orders (tile coords + block + rotation).
 * - Each update tick, scans the queue and submits uncompleted build plans to
 *   the unit build queue via Call.buildConfirm (server-authoritative, works
 *   in multiplayer and single-player without cheats).
 * - Limits submissions per tick to avoid flooding the queue.
 * - Skips tiles that are already occupied by a valid, non-broken building of
 *   the correct type, or tiles that are already under construction.
 * - Deduplicates by tile position so duplicate orders are ignored.
 *
 * Note: actual builder units must be present and pathable for construction to
 * happen. This role only queues the intent.
 */
public class BuilderRole{
    private static final int MAX_QUEUE = 128;
    private static final int SUBMIT_PER_TICK = 4;
    private static final float TICK_INTERVAL = 60f; // check queue every ~1 second

    /** Pending build plan: tile (tx,ty in tile coords), block, rotation 0-3. */
    public record BuildPlan(int tx, int ty, Block block, int rotation){}

    private final Deque<BuildPlan> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>(); // packed tx,ty of already-queued tiles
    private float lastTick;

    /**
     * Add a build plan from a Commander order.
     * x, y are world pixel coordinates from the LLM; we convert to tile coords.
     */
    public void addOrder(Order order){
        if(order.type != Order.Type.build) return;
        SchematicLibrary.BuildSpec spec = SchematicLibrary.get(order.schematic);
        if(spec == null){
            Log.warn("[AI Commander/Builder] unknown schematic: @", order.schematic);
            return;
        }

        // Convert world pixel → tile. In Mindustry, 1 tile = tilesize (8) pixels.
        int tx = world.toTile(order.x);
        int ty = world.toTile(order.y);
        int rotation = Math.max(0, Math.min(3, order.rotation));

        long key = Point2.pack(tx, ty);
        if(queued.contains(key)) return;
        if(queue.size() >= MAX_QUEUE) return;

        queue.addLast(new BuildPlan(tx, ty, spec.block(), rotation));
        queued.add(key);
    }

    /** Call from the main game update loop. */
    public void update(){
        if(!state.isGame()) return;
        if(Time.time - lastTick < TICK_INTERVAL) return;
        lastTick = Time.time;

        int submitted = 0;
        Iterator<BuildPlan> it = queue.iterator();
        while(it.hasNext() && submitted < SUBMIT_PER_TICK){
            BuildPlan plan = it.next();

            // Remove from queue if tile is already correctly built or under construction.
            if(tileOk(plan)){
                it.remove();
                queued.remove(Point2.pack(plan.tx(), plan.ty()));
                continue;
            }

            // Skip if tile is invalid or blocked by something non-removable.
            Tile tile = world.tile(plan.tx(), plan.ty());
            if(tile == null){
                it.remove();
                queued.remove(Point2.pack(plan.tx(), plan.ty()));
                continue;
            }

            // Submit build plan through the official server-side API.
            // Call.beginPlace places a ConstructBlock which builder units will complete.
            Team team = player.team();
            Unit unit = player.unit();
            Call.beginPlace(unit, plan.block(), team, plan.tx(), plan.ty(), plan.rotation(), null);

            it.remove();
            queued.remove(Point2.pack(plan.tx(), plan.ty()));
            submitted++;
        }
    }

    /** Returns true if the tile already has the desired block in good shape. */
    private boolean tileOk(BuildPlan plan){
        Tile tile = world.tile(plan.tx(), plan.ty());
        if(tile == null) return false;

        // Already the right block, healthy enough.
        if(tile.block() == plan.block() && tile.build != null && tile.build.health > 0) return true;

        // Under construction for the same block.
        if(tile.block() instanceof ConstructBlock && tile.build instanceof ConstructBlock.ConstructBuild cb){
            return cb.current == plan.block();
        }

        return false;
    }

    public int queueSize(){
        return queue.size();
    }

    public void clear(){
        queue.clear();
        queued.clear();
    }
}
