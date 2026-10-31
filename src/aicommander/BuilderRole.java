package aicommander;

import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.ai.types.CommandAI;
import mindustry.entities.units.BuildPlan;
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
 * Polished Mechanics:
 * 1. Enqueues requests from orders.
 * 2. Directly assigns BuildPlan to the player unit (Alpha/Beta/Gamma) if no other builders exist.
 * 3. Uses Call.beginPlace to place a construction site on the world.
 * 4. Autonomous Player Unit Builder AI: if the player unit is idle or has queued build plans,
 *    moves the player unit towards the nearest unbuilt site so construction actually proceeds!
 * 5. Supports existing team builder units (Poly, Mega) naturally.
 */
public class BuilderRole{
    private static final int MAX_QUEUE = 128;
    private static final int SUBMIT_PER_TICK = 6;
    private static final float TICK_INTERVAL = 30f; // check queue every ~0.5s

    public record PlannedBlock(int tx, int ty, Block block, int rotation){}

    private final Deque<PlannedBlock> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private float lastTick;

    public void addOrder(Order order){
        if(order.type != Order.Type.build) return;
        SchematicLibrary.BuildSpec spec = SchematicLibrary.get(order.schematic);
        if(spec == null){
            Log.warn("[AI Commander/Builder] unknown schematic: @", order.schematic);
            return;
        }

        int tx = world.toTile(order.x);
        int ty = world.toTile(order.y);

        // Check if this is a line order (e.g. connecting a drill to core via conveyors)
        if(order.x2 != 0 || order.y2 != 0){
            int tx2 = world.toTile(order.x2);
            int ty2 = world.toTile(order.y2);
            addLine(spec.block(), tx, ty, tx2, ty2);
            return;
        }

        int rotation = Math.max(0, Math.min(3, order.rotation));

        long key = Point2.pack(tx, ty);
        if(queued.contains(key)) return;
        if(queue.size() >= MAX_QUEUE) return;

        queue.addLast(new PlannedBlock(tx, ty, spec.block(), rotation));
        queued.add(key);
    }

    /**
     * Builds a Manhattan line of conveyors / blocks between (x1,y1) and (x2,y2).
     * Calculates the correct rotation for conveyor directional flow along the path.
     */
    private void addLine(Block block, int x1, int y1, int x2, int y2){
        int dx = Integer.compare(x2, x1);
        int dy = Integer.compare(y2, y1);

        int cx = x1;
        int cy = y1;

        // First move horizontally
        while(cx != x2 && queue.size() < MAX_QUEUE){
            int rot = (dx > 0) ? 0 : 2; // 0=East, 2=West
            long key = Point2.pack(cx, cy);
            if(!queued.contains(key)){
                queue.addLast(new PlannedBlock(cx, cy, block, rot));
                queued.add(key);
            }
            cx += dx;
        }

        // Then move vertically
        while(cy != y2 && queue.size() < MAX_QUEUE){
            int rot = (dy > 0) ? 1 : 3; // 1=North, 3=South
            long key = Point2.pack(cx, cy);
            if(!queued.contains(key)){
                queue.addLast(new PlannedBlock(cx, cy, block, rot));
                queued.add(key);
            }
            cy += dy;
        }

        // Final tile at destination
        long key = Point2.pack(cx, cy);
        if(!queued.contains(key) && queue.size() < MAX_QUEUE){
            // Point towards end direction if moving, else default 0
            int rot = (dx != 0) ? (dx > 0 ? 0 : 2) : (dy > 0 ? 1 : 3);
            queue.addLast(new PlannedBlock(cx, cy, block, rot));
            queued.add(key);
        }
    }

    /** Called from main game loop every frame/tick. */
    public void update(){
        if(!state.isGame()) return;

        // Auto-link any power nodes placed that have available link capacity
        updatePowerGrid();

        // Drive the player unit to build if there are plans or construction blocks nearby
        updatePlayerBuilding();

        if(Time.time - lastTick < TICK_INTERVAL) return;
        lastTick = Time.time;

        int submitted = 0;
        Iterator<PlannedBlock> it = queue.iterator();
        while(it.hasNext() && submitted < SUBMIT_PER_TICK){
            PlannedBlock plan = it.next();

            if(tileOk(plan)){
                it.remove();
                queued.remove(Point2.pack(plan.tx(), plan.ty()));
                continue;
            }

            Tile tile = world.tile(plan.tx(), plan.ty());
            if(tile == null){
                it.remove();
                queued.remove(Point2.pack(plan.tx(), plan.ty()));
                continue;
            }

            Team team = player.team();
            Unit unit = player.unit();

            // Submit construction site
            Call.beginPlace(unit, plan.block(), team, plan.tx(), plan.ty(), plan.rotation(), null);

            // Also queue on player unit's personal build plan list if it can build
            if(unit != null && unit.canBuild()){
                unit.addBuild(new BuildPlan(plan.tx(), plan.ty(), plan.rotation(), plan.block()));
            }

            it.remove();
            queued.remove(Point2.pack(plan.tx(), plan.ty()));
            submitted++;
        }
    }

    /**
     * Periodically inspects team power nodes and connects unlinked generators, consumers,
     * batteries, and neighboring power nodes within laser range.
     */
    private float lastPowerCheck;
    private void updatePowerGrid(){
        if(Time.time - lastPowerCheck < 120f) return; // every ~2 seconds
        lastPowerCheck = Time.time;

        Team team = player.team();
        for(Building b : team.data().buildings){
            if(b instanceof mindustry.world.blocks.power.PowerNode.PowerNodeBuild node){
                mindustry.world.blocks.power.PowerNode block = (mindustry.world.blocks.power.PowerNode)node.block;
                if(node.power.links.size < block.maxNodes){
                    // Search nearby buildings within laserRange
                    float maxDst = block.laserRange * tilesize;
                    for(Building other : team.data().buildings){
                        if(node.power.links.size >= block.maxNodes) break;
                        if(other == node || other.power == null) continue;
                        if(other.dst(node) <= maxDst && !node.power.links.contains(other.pos())){
                            // Link node to other power building
                            node.configure(other.pos());
                        }
                    }
                }
            }
        }
    }

    /**
     * Drives the player unit toward active build sites when there are construction jobs.
     * Prevents the player unit from being static and useless during the early game.
     */
    private void updatePlayerBuilding(){
        Unit u = player.unit();
        if(u == null || !u.isValid() || !u.canBuild()) return;

        // If player unit already has plans queued
        BuildPlan plan = u.buildPlan();
        if(plan != null){
            float targetX = plan.tile().worldx();
            float targetY = plan.tile().worldy();
            float buildRange = u.type.buildRange - 20f;

            if(!u.within(targetX, targetY, buildRange)){
                // Move towards build site
                Vec2 dir = new Vec2(targetX - u.x, targetY - u.y).limit(u.speed());
                u.movePref(dir);
            }
            return;
        }

        // If player unit has no plan, look for closest unbuilt ConstructBuild belonging to our team
        Team team = player.team();
        Building closest = null;
        float minDist = Float.MAX_VALUE;

        for(Building b : team.data().buildings){
            if(b instanceof ConstructBlock.ConstructBuild cb){
                float dist = u.dst(cb);
                if(dist < minDist){
                    minDist = dist;
                    closest = cb;
                }
            }
        }

        if(closest instanceof ConstructBlock.ConstructBuild cb){
            // Queue this construct plan to the player unit
            u.addBuild(new BuildPlan(cb.tileX(), cb.tileY(), cb.rotation, cb.current));
            if(!u.within(cb.x, cb.y, u.type.buildRange - 20f)){
                Vec2 dir = new Vec2(cb.x - u.x, cb.y - u.y).limit(u.speed());
                u.movePref(dir);
            }
        }
    }

    private boolean tileOk(PlannedBlock plan){
        Tile tile = world.tile(plan.tx(), plan.ty());
        if(tile == null) return false;

        if(tile.block() == plan.block() && tile.build != null && tile.build.health > 0) return true;

        if(tile.block() instanceof ConstructBlock && tile.build instanceof ConstructBlock.ConstructBuild cb){
            return cb.current == plan.block();
        }

        return false;
    }

    public int queueSize(){
        return queue.size();
    }

    public List<PlannedBlock> peekQueue(int max){
        List<PlannedBlock> list = new ArrayList<>();
        int count = 0;
        for(PlannedBlock pb : queue){
            list.add(pb);
            if(++count >= max) break;
        }
        return list;
    }

    public void clear(){
        queue.clear();
        queued.clear();
    }
}
