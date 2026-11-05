package aicommander;

import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.ai.types.CommandAI;
import mindustry.content.Blocks;
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
    private final Map<Long, Integer> attempts = new HashMap<>();
    private float lastTick;
    private Doctrine doctrine = Doctrine.defaultDoctrine();

    public void applyDoctrine(Doctrine doc){
        if(doc == null) return;
        this.doctrine = doc;
    }

    public void addOrder(Order order){
        if(order.type != Order.Type.build) return;
        SchematicLibrary.BuildSpec spec = SchematicLibrary.get(order.schematic);
        if(spec == null){
            Log.warn("[AI Commander/Builder] unknown schematic: @", order.schematic);
            return;
        }

        int tx = clampTile(world.toTile(order.x), world.width());
        int ty = clampTile(world.toTile(order.y), world.height());

        // Check if this is a line order (e.g. connecting a drill to core via conveyors)
        if(order.line){
            int tx2 = clampTile(world.toTile(order.x2), world.width());
            int ty2 = clampTile(world.toTile(order.y2), world.height());
            addLine(spec.block(), tx, ty, tx2, ty2);
            return;
        }

        int rotation = Math.max(0, Math.min(3, order.rotation));

        // Queue composite entries. Skip anything already standing so repeated
        // wave directives do not waste build capacity or queue slots.
        for(SchematicLibrary.BlockEntry entry : spec.composite()){
            int bx = tx + entry.dx();
            int by = ty + entry.dy();
            int rot = (rotation + entry.rotation()) % 4;

            if(bx < 0 || by < 0 || bx >= world.width() || by >= world.height()) continue;
            if(alreadyBuilt(entry.block(), bx, by)) continue;

            long key = Point2.pack(bx, by);
            if(queued.contains(key)) continue;
            if(queue.size() >= MAX_QUEUE) break;

            queue.addLast(new PlannedBlock(bx, by, entry.block(), rot));
            queued.add(key);
        }
    }

    /**
     * Builds a Manhattan line of conveyors / blocks between (x1,y1) and (x2,y2).
     * Calculates the correct rotation for conveyor directional flow along the path.
     */
    private void addLine(Block block, int x1, int y1, int x2, int y2){
        if(x1 == x2 && y1 == y2) return;

        // Route around buildings instead of silently leaving a broken belt.
        List<int[]> path = findLinePath(x1, y1, x2, y2);
        if(path != null && !path.isEmpty()){
            for(int i = 0; i < path.size() && queue.size() < MAX_QUEUE; i++){
                int[] cell = path.get(i);
                int nx = i + 1 < path.size() ? path.get(i + 1)[0] : x2;
                int ny = i + 1 < path.size() ? path.get(i + 1)[1] : y2;
                enqueueLineTile(block, cell[0], cell[1], rotationTo(cell[0], cell[1], nx, ny));
            }
            return;
        }

        // Fallback: straight Manhattan route for unusual maps where the A*
        // search hit its node cap.
        int dx = Integer.compare(x2, x1);
        int dy = Integer.compare(y2, y1);
        int cx = x1;
        int cy = y1;

        // Never build on the source or destination tiles: those are occupied by
        // the drill/core/turret/factory that the line connects.
        if(cx != x2){
            cx += dx;
        }else{
            cy += dy;
        }

        while(cx != x2 && queue.size() < MAX_QUEUE){
            enqueueLineTile(block, cx, cy, (dx > 0) ? 0 : 2);
            cx += dx;
        }
        while(cy != y2 && queue.size() < MAX_QUEUE){
            enqueueLineTile(block, cx, cy, (dy > 0) ? 1 : 3);
            cy += dy;
        }
    }

    /** Shortest 4-direction air-only route between the tiles adjacent to source and destination. */
    private List<int[]> findLinePath(int sx, int sy, int dx, int dy){
        ArrayDeque<int[]> open = new ArrayDeque<>();
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> dist = new HashMap<>();

        for(int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            int nx = sx + d[0], ny = sy + d[1];
            if(!linePassable(nx, ny, sx, sy, dx, dy)) continue;
            long key = Point2.pack(nx, ny);
            if(dist.containsKey(key)) continue;
            dist.put(key, 1);
            parent.put(key, (long)Point2.pack(sx, sy));
            open.addLast(new int[]{nx, ny});
        }

        long goal = Long.MIN_VALUE;
        int visited = 0;
        while(!open.isEmpty() && visited++ < 20000){
            int[] cur = open.pollFirst();
            long curKey = Point2.pack(cur[0], cur[1]);
            if(Math.abs(cur[0] - dx) + Math.abs(cur[1] - dy) == 1){
                goal = curKey;
                break;
            }
            int nextDist = dist.getOrDefault(curKey, 0) + 1;
            for(int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
                int nx = cur[0] + d[0], ny = cur[1] + d[1];
                if(!linePassable(nx, ny, sx, sy, dx, dy)) continue;
                long key = Point2.pack(nx, ny);
                if(dist.containsKey(key)) continue;
                dist.put(key, nextDist);
                parent.put(key, curKey);
                open.addLast(new int[]{nx, ny});
            }
        }
        if(goal == Long.MIN_VALUE) return null;

        LinkedList<int[]> reversed = new LinkedList<>();
        long cur = goal;
        while(cur != Point2.pack(sx, sy)){
            reversed.addFirst(new int[]{Point2.x((int)cur), Point2.y((int)cur)});
            Long p = parent.get(cur);
            if(p == null) return null;
            cur = p;
        }
        return reversed;
    }

    private boolean linePassable(int tx, int ty, int sx, int sy, int dx, int dy){
        if(tx < 0 || ty < 0 || tx >= world.width() || ty >= world.height()) return false;
        if((tx == sx && ty == sy) || (tx == dx && ty == dy)) return false;
        Tile tile = world.tile(tx, ty);
        return tile != null && tile.block() == Blocks.air && tile.build == null;
    }

    private static int rotationTo(int x, int y, int nx, int ny){
        if(nx > x) return 0;
        if(nx < x) return 2;
        if(ny > y) return 1;
        return 3;
    }

    /**
     * Skip occupied tiles while routing a line. This is essential for drills and
     * cores: a conveyor cannot be built on top of a 2x2/3x3/4x4 footprint, and
     * the old code silently lost every such plan.
     */
    private void enqueueLineTile(Block block, int tx, int ty, int rotation){
        if(tx < 0 || ty < 0 || tx >= world.width() || ty >= world.height()) return;

        Tile tile = world.tile(tx, ty);
        if(tile == null) return;
        if(tile.block() != Blocks.air && !(tile.block() instanceof ConstructBlock)) return;

        long key = Point2.pack(tx, ty);
        if(queued.contains(key)) return;

        queue.addLast(new PlannedBlock(tx, ty, block, rotation));
        queued.add(key);
    }

    private static int clampTile(int value, int limit){
        return Math.max(0, Math.min(limit - 1, value));
    }

    /**
     * Validate the entire multi-block footprint before requesting a placement.
     * This prevents silent failures on drill/core footprints, occupied tiles,
     * and blocks that cannot be placed on the current floor.
     */
    private boolean canPlaceFootprint(Block block, int tx, int ty, int rotation, Team team){
        int size = Math.max(1, block.size);
        for(int x = 0; x < size; x++){
            for(int y = 0; y < size; y++){
                Tile t = world.tile(tx + x, ty + y);
                if(t == null) return false;
                if(t.build instanceof ConstructBlock.ConstructBuild) return false;
                if(!block.canReplace(t.block())) return false;
            }
        }
        Tile center = world.tile(tx, ty);
        return center != null && block.canPlaceOn(center, team, rotation);
    }

    /** Called from main game loop every frame/tick. */
    public void update(){
        if(!state.isGame()) return;

        // Auto-link any power nodes placed that have available link capacity
        updatePowerGrid();

        // Drive the player unit to build if there are plans or construction blocks nearby
        updatePlayerBuilding();
        updateAiBuilders();

        if(Time.time - lastTick < TICK_INTERVAL) return;
        lastTick = Time.time;

        sortQueue();

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

            // Without a controllable unit we cannot start a construction site.
            // Keep the queue intact so it resumes after the player unit respawns.
            if(unit == null || !unit.isValid()){
                return;
            }

            // Deconfliction: if existing tile has an operational block of identical or larger tier, skip
            if(tile.build != null && tile.build.team == team && tile.block() == plan.block()){
                it.remove();
                queued.remove(Point2.pack(plan.tx(), plan.ty()));
                continue;
            }

            long key = Point2.pack(plan.tx(), plan.ty());

            // Validate the whole footprint before telling the game to place it.
            if(!canPlaceFootprint(plan.block(), plan.tx(), plan.ty(), plan.rotation(), team)){
                it.remove();
                queued.remove(key);
                attempts.remove(key);
                continue;
            }

            // Submit construction site
            Call.beginPlace(unit, plan.block(), team, plan.tx(), plan.ty(), plan.rotation(), null);

            Tile placedTile = world.tile(plan.tx(), plan.ty());
            boolean placed = placedTile != null
                && placedTile.build instanceof ConstructBlock.ConstructBuild
                && placedTile.block() == plan.block();

            if(!placed){
                int tries = attempts.merge(key, 1, Integer::sum);
                if(tries >= 5){
                    Log.warn("[AI Commander/Builder] placement failed after @ tries: @ at @,@", tries, plan.block().name, plan.tx(), plan.ty());
                    it.remove();
                    queued.remove(key);
                    attempts.remove(key);
                }
                continue;
            }

            attempts.remove(key);
            if(unit.canBuild()){
                unit.addBuild(new BuildPlan(plan.tx(), plan.ty(), plan.rotation(), plan.block()));
            }

            it.remove();
            queued.remove(key);
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
        List<Building> powerBuildings = new ArrayList<>();
        for(Building b : team.data().buildings){
            if(b != null && b.isValid() && b.power != null) powerBuildings.add(b);
        }

        for(Building b : powerBuildings){
            if(!(b instanceof mindustry.world.blocks.power.PowerNode.PowerNodeBuild node)) continue;
            mindustry.world.blocks.power.PowerNode block = (mindustry.world.blocks.power.PowerNode)node.block;
            if(node.power.links.size >= block.maxNodes) continue;

            float maxDst = block.laserRange * tilesize;

            // Nearest-first: prefer connecting consumers/producers before
            // spending link slots on long chains of other nodes.
            List<Building> nearby = new ArrayList<>();
            for(Building other : powerBuildings){
                if(other == node) continue;
                if(other.dst(node) > maxDst) continue;
                if(node.power.links.contains(other.pos())) continue;
                nearby.add(other);
            }
            nearby.sort(Comparator.comparingDouble(other -> other.dst2(node)));
            nearby.sort(Comparator.comparingInt(other ->
                other instanceof mindustry.world.blocks.power.PowerNode.PowerNodeBuild ? 1 : 0));

            for(Building other : nearby){
                if(node.power.links.size >= block.maxNodes) break;
                if(other instanceof mindustry.world.blocks.power.PowerNode.PowerNodeBuild otherNode){
                    mindustry.world.blocks.power.PowerNode otherBlock =
                        (mindustry.world.blocks.power.PowerNode)otherNode.block;
                    if(otherNode.power.links.size >= otherBlock.maxNodes) continue;
                }
                node.configure(other.pos());
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
            float buildRange = Math.max(8f, u.type.buildRange - 20f);

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
            if(!u.within(cb.x, cb.y, Math.max(8f, u.type.buildRange - 20f))){
                Vec2 dir = new Vec2(cb.x - u.x, cb.y - u.y).limit(u.speed());
                u.movePref(dir);
            }
        }
    }

    /** True when this exact block already exists on the tile for our team. */
    private boolean alreadyBuilt(Block block, int tx, int ty){
        Tile tile = world.tile(tx, ty);
        return tile != null && tile.build != null && tile.build.team == player.team()
            && tile.block() == block && tile.build.health > 0f;
    }

    /**
     * Defence and power infrastructure are more urgent than long conveyor
     * runs. Ordering by category (then by distance from the core) keeps the
     * builder from spending a whole wave laying belts while the core is bare.
     */
    private void sortQueue(){
        if(queue.size() < 2) return;
        Building core = player.closestCore();
        float cx = core != null ? core.x : player.unit() != null ? player.unit().x : 0f;
        float cy = core != null ? core.y : player.unit() != null ? player.unit().y : 0f;

        List<PlannedBlock> sorted = new ArrayList<>(queue);
        sorted.sort((a, b) -> {
            int pa = buildPriority(a.block());
            int pb = buildPriority(b.block());
            if(pa != pb) return Integer.compare(pb, pa);
            float da = (a.tx() * tilesize - cx) * (a.tx() * tilesize - cx) + (a.ty() * tilesize - cy) * (a.ty() * tilesize - cy);
            float db = (b.tx() * tilesize - cx) * (b.tx() * tilesize - cx) + (b.ty() * tilesize - cy) * (b.ty() * tilesize - cy);
            return Float.compare(da, db);
        });
        queue.clear();
        queue.addAll(sorted);
    }

    private static int buildPriority(Block block){
        if(block == null) return 0;
        if(block instanceof mindustry.world.blocks.defense.turrets.Turret) return 4;
        if(block instanceof mindustry.world.blocks.defense.Wall) return 3;
        if(block instanceof mindustry.world.blocks.power.PowerGenerator
            || block instanceof mindustry.world.blocks.power.PowerNode) return 2;
        if(block instanceof mindustry.world.blocks.production.Drill
            || block instanceof mindustry.world.blocks.production.GenericCrafter) return 1;
        return 0;
    }

    private float lastAiBuilderCheck;
    /**
     * Support builder units (Poly/Mega) will assist nearby construction, but
     * they otherwise remain at their last mining spot. Nudge idle builders
     * toward the nearest active construction site so they contribute.
     */
    private void updateAiBuilders(){
        if(Time.time - lastAiBuilderCheck < 120f) return;
        lastAiBuilderCheck = Time.time;

        List<ConstructBlock.ConstructBuild> sites = new ArrayList<>();
        for(Building b : player.team().data().buildings){
            if(b instanceof ConstructBlock.ConstructBuild cb && cb.isValid()){
                sites.add(cb);
            }
        }
        if(sites.isEmpty()) return;

        Unit playerUnit = player.unit();
        for(Unit unit : player.team().data().units){
            if(unit == null || !unit.isValid() || unit == playerUnit) continue;
            if(!unit.canBuild() || unit.hasWeapons()) continue;
            if(unit.controller() instanceof CommandAI) continue;

            ConstructBlock.ConstructBuild nearest = null;
            float best = Float.MAX_VALUE;
            for(ConstructBlock.ConstructBuild site : sites){
                float dst = unit.dst(site);
                if(dst < best){
                    best = dst;
                    nearest = site;
                }
            }
            float range = Math.max(16f, unit.type.buildRange - 16f);
            if(nearest != null && best > range){
                Vec2 dir = new Vec2(nearest.x - unit.x, nearest.y - unit.y).limit(unit.speed());
                unit.movePref(dir);
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
        attempts.clear();
    }
}
