package aicommander;

import arc.util.Log;
import arc.util.Time;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock;
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild;

import java.util.HashMap;
import java.util.Map;

import static mindustry.Vars.*;

/**
 * FallbackAdvisor provides instant heuristic rules when the remote LLM is offline,
 * timing out, or returning consecutive errors, and fills tactical gaps between
 * doctrine updates. It respects the active Doctrine where possible.
 */
public class FallbackAdvisor{
    private static final float CHECK_INTERVAL = 300f; // ~5s
    private static final float FEED_COOLDOWN = 60f * 60f;
    private static final float FEED_THRESHOLD = 0.25f;
    private static final float FEED_RECOVERED = 0.75f;
    private static final int MAX_FEED_ATTEMPTS = 2;

    private float lastCheck;
    private final Map<Integer, Float> lastFed = new HashMap<>();
    private final Map<Integer, Integer> feedAttempts = new HashMap<>();

    public void update(CommanderController controller, UnitRoleManager roles){
        if(!Settings.autoFallback()) return;
        if(Time.time - lastCheck < CHECK_INTERVAL) return;
        lastCheck = Time.time;

        Building core = player.closestCore();
        if(core == null) return;

        Doctrine doctrine = roles.doctrine();
        int copper = core.items.get(Items.copper);

        // Only add opportunistic directives when the builder has spare capacity.
        if(roles.builder().queueSize() == 0
            && (player.unit() == null || player.unit().buildPlan() == null)){

            // 0. Power bootstrap: without a node, generators and factories are
            // disconnected. Place a node first, then a coal plant or solar panel.
            int nodes = countBlock(Blocks.powerNode);
            int generators = countBlock(Blocks.solarPanel) + countBlock(Blocks.combustionGenerator);
            if(nodes == 0 && copper >= 15){
                Tile spawn = spawner.getSpawns().firstOpt();
                float[] pos = defensePoint(core, spawn, 56f);
                int powerBuildings = 0;
                for(Building b : player.team().data().buildings){
                    if(b.power != null) powerBuildings++;
                }
                boolean large = powerBuildings >= 4 && canAfford(core, Blocks.powerNodeLarge);
                roles.builder().addOrder(Order.build(large ? "power-node-large" : "power-node", pos[0], pos[1], 0));
                Log.info("[AI Commander/Fallback] Queued first @ (power buildings: @).", large ? "large power node" : "power node", powerBuildings);
                return;
            }
            // 0b. If a power building is completely isolated, put a node next
            // to it. This happens when the first node was built far from a
            // later factory or generator.
            if(nodes > 0 && copper >= 15){
                for(Building b : player.team().data().buildings){
                    if(b == null || !b.isValid() || b.power == null) continue;
                    if(b.block instanceof mindustry.world.blocks.power.PowerNode) continue;
                    if(!b.power.links.isEmpty()) continue;
                    boolean covered = false;
                    for(Building n : player.team().data().buildings){
                        if(n instanceof mindustry.world.blocks.power.PowerNode.PowerNodeBuild node){
                            float range = ((mindustry.world.blocks.power.PowerNode)node.block).laserRange * tilesize;
                            if(n.dst(b) <= range){ covered = true; break; }
                        }
                    }
                    if(!covered){
                        float nearest = Float.MAX_VALUE;
                        Building nearestNode = null;
                        for(Building n : player.team().data().buildings){
                            if(!(n instanceof mindustry.world.blocks.power.PowerNode.PowerNodeBuild)) continue;
                            float d = n.dst(b);
                            if(d < nearest){ nearest = d; nearestNode = n; }
                        }
                        float normalRange = ((mindustry.world.blocks.power.PowerNode)Blocks.powerNode).laserRange * tilesize;
                        float largeRange = ((mindustry.world.blocks.power.PowerNode)Blocks.powerNodeLarge).laserRange * tilesize;
                        boolean large = nearestNode != null && nearest > normalRange
                            && nearest <= largeRange && canAfford(core, Blocks.powerNodeLarge);
                        roles.builder().addOrder(Order.build(large ? "power-node-large" : "power-node",
                            b.x + tilesize * 2f, b.y, 0));
                        Log.info("[AI Commander/Fallback] Queued @ for isolated @ at @,@ (nearest node @ tiles).",
                            large ? "large node" : "node", b.block.name, b.tileX(), b.tileY(),
                            nearest == Float.MAX_VALUE ? -1 : (int)(nearest / tilesize));
                        return;
                    }
                }
            }

            if(generators == 0){
                Tile spawn = spawner.getSpawns().firstOpt();
                Tile coal = indexer.findClosestOre(core.x, core.y, Items.coal);
                boolean buildCoal = coal != null
                    && canAfford(core, Blocks.combustionGenerator)
                    && canAfford(core, Blocks.mechanicalDrill)
                    && canAfford(core, Blocks.conveyor);
                Block generator = buildCoal ? Blocks.combustionGenerator : Blocks.solarPanel;
                if(canAfford(core, generator)){
                    float[] pos = defensePoint(core, spawn, 40f);
                    roles.builder().addOrder(Order.build(buildCoal ? "combustion" : "solar-panel", pos[0], pos[1], 0));
                    if(buildCoal){
                        // Feed the combustion generator directly from coal ore.
                        roles.builder().addOrder(Order.build("copper-drill", coal.worldx(), coal.worldy(), 0));
                        roles.builder().addOrder(Order.buildLine("conveyor", coal.worldx(), coal.worldy(), pos[0], pos[1]));
                        Log.info("[AI Commander/Fallback] Queued coal power plant and coal feed line.");
                    }else{
                        Log.info("[AI Commander/Fallback] Queued bootstrap solar panel (no affordable coal feed).");
                    }
                    return;
                }
            }

            // 1. Feed a dry turret from a nearby ore patch. Cores do NOT output
            // items onto conveyors; a drill -> turret line is the only autonomous
            // ammunition path that works without an unloader.
            TurretBuild hungry = null;
            float lowestFraction = 1f;
            for(Building b : player.team().data().buildings){
                if(!(b instanceof TurretBuild tb)) continue;
                float fraction = tb.getAmmoFraction();
                if(fraction >= FEED_RECOVERED){
                    feedAttempts.remove(tb.pos());
                    continue;
                }
                if(fraction >= FEED_THRESHOLD) continue;
                Item ammo = turretAmmo(tb);
                if(ammo == null || !drillable(ammo)) continue;
                if(feedAttempts.getOrDefault(tb.pos(), 0) >= MAX_FEED_ATTEMPTS) continue;
                float last = lastFed.getOrDefault(tb.pos(), -FEED_COOLDOWN);
                if(Time.time - last <= FEED_COOLDOWN) continue;
                if(fraction < lowestFraction){
                    lowestFraction = fraction;
                    hungry = tb;
                }
            }
            if(hungry != null){
                Item ammo = turretAmmo(hungry);
                Tile ammoOre = ammo != null ? indexer.findClosestOre(hungry.x, hungry.y, ammo) : null;
                if(ammoOre != null && ammoOre.build == null && ammoOre.block() == Blocks.air
                    && canAfford(core, Blocks.mechanicalDrill) && canAfford(core, Blocks.conveyor)){
                    lastFed.put(hungry.pos(), Time.time);
                    feedAttempts.merge(hungry.pos(), 1, Integer::sum);
                    roles.builder().addOrder(Order.build("copper-drill", ammoOre.worldx(), ammoOre.worldy(), 0));
                    roles.builder().addOrder(Order.buildLine("conveyor", ammoOre.worldx(), ammoOre.worldy(), hungry.x, hungry.y));
                    Log.info("[AI Commander/Fallback] Feeding turret at @,@ (@% ammo, attempt @) with @ from ore at @,@.",
                        hungry.tileX(), hungry.tileY(), Math.round(lowestFraction * 100f),
                        feedAttempts.get(hungry.pos()), ammo.name, ammoOre.x, ammoOre.y);
                    return;
                }
            }

            // 1b. Buffer power once generation exists.
            if(generators > 0 && countBlock(Blocks.battery) == 0 && canAfford(core, Blocks.battery)){
                float[] pos = defensePoint(core, spawner.getSpawns().firstOpt(), 48f);
                roles.builder().addOrder(Order.build("battery", pos[0], pos[1], 0));
                Log.info("[AI Commander/Fallback] Queued power battery buffer.");
                return;
            }

            // 1c. Sustainment: repair points heal units and menders repair
            // structures. Only build them once power generation exists, since
            // both consume power while active.
            int damaged = countDamaged();
            boolean wantsRepair = doctrine.defenseFocus == Doctrine.DefenseFocus.REPAIR || damaged >= 3;
            if(wantsRepair && generators > 0){
                if(countBlock(Blocks.repairPoint) == 0 && canAfford(core, Blocks.repairPoint)){
                    float[] pos = defensePoint(core, spawner.getSpawns().firstOpt(), 36f);
                    roles.builder().addOrder(Order.build("repair-point", pos[0], pos[1], 0));
                    Log.info("[AI Commander/Fallback] Queued repair point (@ damaged buildings).", damaged);
                    return;
                }
                if(damaged >= 3 && countBlock(Blocks.mender) == 0 && canAfford(core, Blocks.mender)){
                    float[] pos = defensePoint(core, spawner.getSpawns().firstOpt(), 72f);
                    roles.builder().addOrder(Order.build("mender", pos[0], pos[1], 0));
                    Log.info("[AI Commander/Fallback] Queued mender for @ damaged buildings.", damaged);
                    return;
                }
                if(doctrine.posture == Doctrine.Posture.FORTIFY && damaged >= 6
                    && countBlock(Blocks.mendProjector) == 0 && canAfford(core, Blocks.mendProjector)){
                    float[] pos = defensePoint(core, spawner.getSpawns().firstOpt(), 96f);
                    roles.builder().addOrder(Order.build("mend-projector", pos[0], pos[1], 0));
                    Log.info("[AI Commander/Fallback] Queued mend projector for fortifications.");
                    return;
                }
            }

            // 2. Anti-air doctrine: place a scatter turret when air threats are present.
            if(doctrine.defenseFocus == Doctrine.DefenseFocus.ANTI_AIR
                && canAfford(core, Blocks.scatter) && canAfford(core, Blocks.conveyor)){
                int scatterCount = countBlock(Blocks.scatter);
                if(scatterCount < 3){
                    Tile spawn = spawner.getSpawns().firstOpt();
                    float[] pos = defensePoint(core, spawn, 64f + scatterCount * 24f);
                    roles.builder().addOrder(Order.build("scatter", pos[0], pos[1], 0));
                    Tile leadOre = indexer.findClosestOre(pos[0], pos[1], Items.lead);
                    if(leadOre != null && leadOre.build == null && leadOre.block() == Blocks.air
                        && canAfford(core, Blocks.mechanicalDrill) && canAfford(core, Blocks.conveyor)){
                        roles.builder().addOrder(Order.build("copper-drill", leadOre.worldx(), leadOre.worldy(), 0));
                        roles.builder().addOrder(Order.buildLine("conveyor", leadOre.worldx(), leadOre.worldy(), pos[0], pos[1]));
                    }
                    Log.info("[AI Commander/Fallback] Queued anti-air scatter at @,@", (int)pos[0], (int)pos[1]);
                    return;
                }
            }

            // 3. Economy drills: EXPAND widens income to four drills, while
            // defensive postures keep a lean copper/lead baseline of two.
            int drills = countBlock(Blocks.mechanicalDrill);
            int targetDrills = doctrine.posture == Doctrine.Posture.EXPAND ? 4 : 2;
            if(drills < targetDrills
                && canAfford(core, Blocks.mechanicalDrill) && canAfford(core, Blocks.conveyor)){
                int copperStock = core.items.get(Items.copper);
                int leadStock = core.items.get(Items.lead);
                Item wanted = copperStock < 600 && (leadStock >= 400 || copperStock <= leadStock)
                    ? Items.copper : Items.lead;
                Tile ore = indexer.findClosestOre(core.x, core.y, wanted);
                if(ore == null || ore.build != null || ore.block() != Blocks.air){
                    wanted = wanted == Items.copper ? Items.lead : Items.copper;
                    ore = indexer.findClosestOre(core.x, core.y, wanted);
                }
                if(ore != null && ore.build == null && ore.block() == Blocks.air){
                    roles.builder().addOrder(Order.build("copper-drill", ore.worldx(), ore.worldy(), 0));
                    roles.builder().addOrder(Order.buildLine("conveyor", ore.worldx(), ore.worldy(), core.x, core.y));
                    Log.info("[AI Commander/Fallback] Queued @ economy drill (@/@, copper @ lead @).",
                        wanted.name, drills + 1, targetDrills, copperStock, leadStock);
                    return;
                }
            }

            // 5. Tech rush: bootstrap graphite and silicon production with feeds.
            if(doctrine.posture == Doctrine.Posture.TECH_RUSH){
                Tile coal = indexer.findClosestOre(core.x, core.y, Items.coal);
                if(countBlock(Blocks.graphitePress) == 0
                    && coal != null && coal.build == null && coal.block() == Blocks.air
                    && canAfford(core, Blocks.graphitePress)
                    && canAfford(core, Blocks.mechanicalDrill)
                    && canAfford(core, Blocks.conveyor)){
                    float[] pos = defensePoint(core, spawner.getSpawns().firstOpt(), 40f);
                    roles.builder().addOrder(Order.build("graphite-press", pos[0], pos[1], 0));
                    roles.builder().addOrder(Order.build("copper-drill", coal.worldx(), coal.worldy(), 0));
                    roles.builder().addOrder(Order.buildLine("conveyor", coal.worldx(), coal.worldy(), pos[0], pos[1]));
                    roles.builder().addOrder(Order.buildLine("conveyor", pos[0], pos[1], core.x, core.y));
                    Log.info("[AI Commander/Fallback] Queued graphite press with coal feed and core output.");
                    return;
                }
                Tile sand = indexer.findClosestOre(core.x, core.y, Items.sand);
                if(countBlock(Blocks.siliconSmelter) == 0
                    && coal != null && coal.build == null && coal.block() == Blocks.air
                    && sand != null && sand.build == null && sand.block() == Blocks.air
                    && canAfford(core, Blocks.siliconSmelter)
                    && canAfford(core, Blocks.mechanicalDrill)
                    && canAfford(core, Blocks.conveyor)){
                    float[] pos = defensePoint(core, spawner.getSpawns().firstOpt(), -40f);
                    roles.builder().addOrder(Order.build("silicon-smelter", pos[0], pos[1], 0));
                    roles.builder().addOrder(Order.build("copper-drill", coal.worldx(), coal.worldy(), 0));
                    roles.builder().addOrder(Order.buildLine("conveyor", coal.worldx(), coal.worldy(), pos[0], pos[1]));
                    roles.builder().addOrder(Order.build("copper-drill", sand.worldx(), sand.worldy(), 0));
                    roles.builder().addOrder(Order.buildLine("conveyor", sand.worldx(), sand.worldy(), pos[0], pos[1]));
                    roles.builder().addOrder(Order.buildLine("conveyor", pos[0], pos[1], core.x, core.y));
                    Log.info("[AI Commander/Fallback] Queued silicon smelter with coal/sand feeds and core output.");
                    return;
                }
            }

            // 6. Defensive posturing: duo turrets or walls between spawn and core.
            boolean turtle = doctrine.posture == Doctrine.Posture.TURTLE || doctrine.posture == Doctrine.Posture.FORTIFY;
            if(turtle && canAfford(core, Blocks.copperWallLarge)){
                int walls = countBlock(Blocks.copperWallLarge) + countBlock(Blocks.copperWall);
                if(walls < 24){
                    Tile spawn = spawner.getSpawns().firstOpt();
                    float[] pos = defensePoint(core, spawn, 56f + (walls % 6) * 12f);
                    roles.builder().addOrder(Order.build("copper-wall-large", pos[0], pos[1], 0));
                    return;
                }
            }

            if(canAfford(core, Blocks.duo)){
                int duoCount = countBlock(Blocks.duo);
                if(duoCount < 3){
                    Tile spawn = spawner.getSpawns().firstOpt();
                    float[] pos = defensePoint(core, spawn, 48f + duoCount * 16f);
                    roles.builder().addOrder(Order.build("duo", pos[0], pos[1], 0));
                    Tile copperOreNear = indexer.findClosestOre(pos[0], pos[1], Items.copper);
                    if(copperOreNear != null && copperOreNear.build == null && copperOreNear.block() == Blocks.air
                        && canAfford(core, Blocks.mechanicalDrill) && canAfford(core, Blocks.conveyor)){
                        roles.builder().addOrder(Order.build("copper-drill", copperOreNear.worldx(), copperOreNear.worldy(), 0));
                        roles.builder().addOrder(Order.buildLine("conveyor", copperOreNear.worldx(), copperOreNear.worldy(), pos[0], pos[1]));
                    }
                    Log.info("[AI Commander/Fallback] Queued defensive Duo turret with copper feed.");
                }
            }
        }
    }

    /** Preferred drillable ammunition item for a turret, or null if unsupported. */
    private static Item turretAmmo(TurretBuild turret){
        Block block = turret.block;
        if(block == Blocks.scatter) return Items.lead;
        if(block == Blocks.duo) return Items.copper;
        // Hail/Salvo need graphite/silicon/pyratite; those require factories,
        // not a simple drill line. Leave them to the builder doctrine.
        return null;
    }

    private static boolean drillable(Item item){
        return item == Items.copper || item == Items.lead || item == Items.coal
            || item == Items.sand || item == Items.titanium || item == Items.scrap;
    }

    /** Checks whether the core has every ingredient required by a block. */
    private static boolean canAfford(Building core, Block block){
        if(core == null || block == null) return false;
        ItemStack[] requirements = block.requirements;
        if(requirements == null) return true;
        for(ItemStack stack : requirements){
            if(core.items.get(stack.item) < stack.amount) return false;
        }
        return true;
    }

    private int countBlock(Block block){
        int count = 0;
        for(Building b : player.team().data().buildings){
            if(b.block == block || (b instanceof ConstructBlock.ConstructBuild cb && cb.current == block)){
                count++;
            }
        }
        return count;
    }

    /** Counts team buildings that are meaningfully below full health. */
    private int countDamaged(){
        int count = 0;
        for(Building b : player.team().data().buildings){
            if(b != null && b.isValid() && b.health < b.maxHealth * 0.99f){
                count++;
            }
        }
        return count;
    }

    /** Returns a world position offset from the core toward a spawn point. */
    private static float[] defensePoint(Building core, Tile spawn, float distance){
        float dx = 0f, dy = 0f;
        if(spawn != null){
            dx = spawn.worldx() - core.x;
            dy = spawn.worldy() - core.y;
        }else{
            dx = 1f;
        }
        float len = (float)Math.sqrt(dx * dx + dy * dy);
        if(len <= 0.001f){
            dx = 1f;
            dy = 0f;
            len = 1f;
        }
        return new float[]{core.x + (dx / len) * distance, core.y + (dy / len) * distance};
    }
}
