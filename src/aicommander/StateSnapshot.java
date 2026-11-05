package aicommander;

import arc.util.serialization.Jval;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.power.Battery;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.blocks.storage.Unloader;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Produces compact, information-dense state summaries designed specifically
 * for LLM wave-boundary evaluation without token bloat.
 */
public final class StateSnapshot{
    private static final Map<String, Integer> lastItems = new HashMap<>();
    private static float lastCaptureTime = 0f;
    private static float matchStartTime = 0f;
    private static final float NEAR_CORE_RADIUS = 600f;

    private StateSnapshot(){}

    /** Clears rate-tracking state so a new match does not inherit old deltas. */
    public static void reset(){
        lastItems.clear();
        lastCaptureTime = 0f;
        matchStartTime = arc.util.Time.time;
    }

    public static Jval capture(){
        Jval root = Jval.newObject();
        root.put("map", state.map.name());
        root.put("wave", state.wave);
        root.put("waveCountdown", Math.max(0, Math.round(state.wavetime / 60f)));
        root.put("elapsed", matchStartTime > 0f ? Math.max(0, Math.round((arc.util.Time.time - matchStartTime) / 60f)) : 0);
        root.put("world", summarizeWorld());

        Team team = player.team();
        CoreBuild core = team.cores().firstOpt();

        if(core != null){
            root.put("core", summarizeCore(core));
            root.put("ores", summarizeOres(core));
            root.put("spawns", summarizeSpawns(core));
        }

        root.put("power", summarizePower(team));
        root.put("production", summarizeProduction(team));
        root.put("defenses", summarizeDefenses(team));
        root.put("units", summarizeUnits(team));
        root.put("threats", summarizeThreats(team, core));

        return root;
    }

    private static Jval summarizeWorld(){
        Jval out = Jval.newObject();
        out.put("tilesW", world.width());
        out.put("tilesH", world.height());
        out.put("pixelsW", world.width() * tilesize);
        out.put("pixelsH", world.height() * tilesize);
        return out;
    }

    private static Jval summarizeCore(CoreBuild core){
        Jval out = Jval.newObject();
        out.put("x", Math.round(core.x));
        out.put("y", Math.round(core.y));
        out.put("hpPct", Math.round((core.health / core.maxHealth) * 100f));
        out.put("capacity", core.storageCapacity);
        out.put("used", core.items.total());
        out.put("free", Math.max(0, core.storageCapacity - core.items.total()));
        out.put("capacityPct", core.storageCapacity > 0
            ? Math.min(100, Math.round(core.items.total() / (float)core.storageCapacity * 100f))
            : 0);

        Jval items = Jval.newObject();
        Jval rates = Jval.newObject();

        float now = arc.util.Time.time;
        float dt = (lastCaptureTime > 0f) ? (now - lastCaptureTime) / 60f : 0f;

        for(Item item : content.items()){
            int amount = core.items.get(item);
            if(amount > 0) items.put(item.name, amount);

            if(dt >= 1f){
                int prev = lastItems.getOrDefault(item.name, amount);
                float rate = (amount - prev) / dt;
                if(Math.abs(rate) >= 0.1f){
                    rates.put(item.name, (double)Math.round(rate * 10f) / 10.0);
                }
                lastItems.put(item.name, amount);
            }
        }
        if(dt >= 1f || lastCaptureTime == 0f){
            lastCaptureTime = now;
        }

        out.put("items", items);
        out.put("rates", rates);
        return out;
    }

    private static Jval summarizePower(Team team){
        Jval out = Jval.newObject();
        float produced = 0f;
        float needed = 0f;
        float batteryStored = 0f;
        float batteryCapacity = 0f;

        Set<mindustry.world.blocks.power.PowerGraph> graphs = new HashSet<>();
        int nodes = 0;
        int powerBuildings = 0;
        int isolated = 0;
        for(Building b : team.data().buildings){
            if(b.power == null) continue;
            powerBuildings++;
            if(b.block instanceof mindustry.world.blocks.power.PowerNode){
                nodes++;
            }
            if(b.power.links.isEmpty()) isolated++;
            if(b.power.graph != null && graphs.add(b.power.graph)){
                produced += b.power.graph.getPowerProduced();
                needed += b.power.graph.getPowerNeeded();
                batteryStored += b.power.graph.getBatteryStored();
                batteryCapacity += b.power.graph.getBatteryCapacity();
            }
        }

        out.put("produced", Math.round(produced * 60f));
        out.put("needed", Math.round(needed * 60f));
        out.put("balance", Math.round((produced - needed) * 60f));
        out.put("batteryPct", batteryCapacity > 0f ? Math.round((batteryStored / batteryCapacity) * 100f) : 0);
        out.put("nodes", nodes);
        out.put("buildings", powerBuildings);
        out.put("graphs", graphs.size());
        out.put("isolated", isolated);
        out.put("satisfactionPct", needed > 0f ? Math.min(100, Math.round(produced / needed * 100f)) : 100);
        return out;
    }

    private static Jval summarizeProduction(Team team){
        Jval out = Jval.newObject();
        int drills = 0;
        int conveyors = 0;
        int factories = 0;
        int generators = 0;
        int batteries = 0;
        int unloaders = 0;
        int walls = 0;
        Map<String, Integer> factoryTypes = new HashMap<>();
        Map<String, Integer> wallTypes = new HashMap<>();

        for(Building b : team.data().buildings){
            if(b == null || !b.isValid()) continue;
            if(b.block instanceof Drill) drills++;
            if(b.block instanceof Conveyor) conveyors++;
            if(b.block instanceof GenericCrafter){
                factories++;
                factoryTypes.merge(b.block.name, 1, Integer::sum);
            }
            if(b.block instanceof PowerGenerator) generators++;
            if(b.block instanceof Battery) batteries++;
            if(b.block instanceof Unloader) unloaders++;
            if(b.block instanceof Wall){
                walls++;
                wallTypes.merge(b.block.name, 1, Integer::sum);
            }
        }

        out.put("drills", drills);
        out.put("conveyors", conveyors);
        out.put("factories", factories);
        out.put("generators", generators);
        out.put("batteries", batteries);
        out.put("unloaders", unloaders);
        out.put("walls", walls);

        Jval fact = Jval.newObject();
        for(Map.Entry<String, Integer> e : factoryTypes.entrySet()){
            fact.put(e.getKey(), e.getValue());
        }
        out.put("factoryTypes", fact);

        Jval wall = Jval.newObject();
        for(Map.Entry<String, Integer> e : wallTypes.entrySet()){
            wall.put(e.getKey(), e.getValue());
        }
        out.put("wallTypes", wall);
        return out;
    }

    private static Jval summarizeDefenses(Team team){        Jval out = Jval.newObject();
        int totalTurrets = 0;
        int ammoSupplied = 0;
        int emptyTurrets = 0;
        int lowAmmoTurrets = 0;
        int damagedBuildings = 0;
        int totalAmmo = 0;
        int ammoCapacity = 0;
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> ammoTypes = new HashMap<>();

        for(Building b : team.data().buildings){
            if(b.health < b.maxHealth - 1f) damagedBuildings++;
            if(b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild tb){
                totalTurrets++;
                totalAmmo += tb.totalAmmo;
                ammoCapacity += tb.block instanceof mindustry.world.blocks.defense.turrets.Turret t
                    ? t.maxAmmo * Math.max(1, tb.ammo.size) : 0;
                if(tb.hasAmmo()){
                    ammoSupplied++;
                    if(tb.getAmmoFraction() < 0.25f) lowAmmoTurrets++;
                    if(tb.getAmmoContent() != null){
                        ammoTypes.merge(tb.getAmmoContent().name, 1, Integer::sum);
                    }
                }else{
                    emptyTurrets++;
                }
                counts.merge(tb.block.name, 1, Integer::sum);
            }
        }

        out.put("totalTurrets", totalTurrets);
        out.put("ammoSupplied", ammoSupplied);
        out.put("emptyTurrets", emptyTurrets);
        out.put("lowAmmoTurrets", lowAmmoTurrets);
        out.put("totalAmmo", totalAmmo);
        out.put("ammoCapacity", ammoCapacity);
        out.put("ammoPct", ammoCapacity > 0 ? Math.min(100, Math.round(totalAmmo / (float)ammoCapacity * 100f)) : 0);
        out.put("damagedBuildings", damagedBuildings);

        Jval ammo = Jval.newObject();
        for(Map.Entry<String, Integer> e : ammoTypes.entrySet()){
            ammo.put(e.getKey(), e.getValue());
        }
        out.put("ammoTypes", ammo);

        Jval turretTypes = Jval.newObject();
        for(Map.Entry<String, Integer> e : counts.entrySet()){
            turretTypes.put(e.getKey(), e.getValue());
        }
        out.put("turretTypes", turretTypes);
        return out;
    }

    private static Jval summarizeUnits(Team team){
        Jval out = Jval.newObject();
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        int builders = 0;
        int miners = 0;
        int combat = 0;
        Unit playerUnit = player.unit();
        boolean playerAlive = playerUnit != null && playerUnit.isValid();

        for(Unit unit : team.data().units){
            if(unit == null || !unit.isValid()) continue;
            total++;
            if(unit != playerUnit){
                if(unit.canBuild()) builders++;
                if(unit.canMine()) miners++;
                if(unit.hasWeapons()) combat++;
            }
            counts.merge(unit.type.name, 1, Integer::sum);
        }

        out.put("total", total);
        out.put("builders", builders);
        out.put("miners", miners);
        out.put("combat", combat);
        out.put("playerAlive", playerAlive);
        Jval byType = Jval.newObject();
        for(Map.Entry<String, Integer> e : counts.entrySet()){
            byType.put(e.getKey(), e.getValue());
        }
        out.put("byType", byType);
        return out;
    }

    private static Jval summarizeOres(CoreBuild core){
        Jval list = Jval.newArray();
        if(core == null || indexer == null) return list;

        Item[] targets = {Items.copper, Items.lead, Items.coal, Items.titanium, Items.sand};
        for(Item item : targets){
            Tile closest = indexer.findClosestOre(core.x, core.y, item);
            if(closest != null){
                Jval o = Jval.newObject();
                o.put("item", item.name);
                o.put("x", Math.round(closest.worldx()));
                o.put("y", Math.round(closest.worldy()));
                o.put("dist", Math.round(core.dst(closest)));
                list.add(o);
            }
        }
        return list;
    }

    private static Jval summarizeSpawns(CoreBuild core){
        Jval list = Jval.newArray();
        if(spawner == null) return list;

        arc.struct.Seq<Tile> groundSpawns = spawner.getSpawns();
        if(groundSpawns != null){
            for(Tile tile : groundSpawns){
                if(tile == null) continue;
                Jval s = Jval.newObject();
                float wx = tile.worldx();
                float wy = tile.worldy();
                s.put("x", Math.round(wx));
                s.put("y", Math.round(wy));
                if(core != null){
                    s.put("distToCore", Math.round(core.dst(wx, wy)));
                }
                list.add(s);
            }
        }
        return list;
    }

    private static Jval summarizeThreats(Team team, CoreBuild core){
        Jval out = Jval.newObject();
        Map<String, Integer> enemyCounts = new HashMap<>();
        int totalEnemies = 0;
        int flying = 0;
        int nearCore = 0;
        int nearCoreAir = 0;
        float nearestDist = Float.MAX_VALUE;
        String nearestType = "";
        Unit nearestUnit = null;

        for(Unit unit : Groups.unit){
            if(unit == null || !unit.isValid() || unit.team == team || unit.team == mindustry.game.Team.derelict) continue;
            totalEnemies++;
            if(unit.isFlying()) flying++;
            enemyCounts.merge(unit.type.name, 1, Integer::sum);

            if(core != null){
                float dst = unit.dst(core);
                if(dst <= NEAR_CORE_RADIUS){
                    nearCore++;
                    if(unit.isFlying()) nearCoreAir++;
                }
                if(dst < nearestDist){
                    nearestDist = dst;
                    nearestType = unit.type.name;
                    nearestUnit = unit;
                }
            }
        }

        out.put("activeEnemies", totalEnemies);
        out.put("flyingEnemies", flying);
        out.put("nearCore", nearCore);
        out.put("nearCoreAir", nearCoreAir);
        if(core != null && nearestDist < Float.MAX_VALUE){
            out.put("nearestDist", Math.round(nearestDist));
            out.put("nearestType", nearestType);
            if(nearestUnit != null){
                out.put("nearestX", Math.round(nearestUnit.x));
                out.put("nearestY", Math.round(nearestUnit.y));
                float dx = nearestUnit.x - core.x;
                float dy = nearestUnit.y - core.y;
                float bearing = (float)Math.toDegrees(Math.atan2(-dy, dx));
                if(bearing < 0f) bearing += 360f;
                out.put("nearestBearing", Math.round(bearing));
                String[] dirs = {"E", "NE", "N", "NW", "W", "SW", "S", "SE"};
                out.put("nearestDir", dirs[Math.round(bearing / 45f) % 8]);
            }
        }

        Jval byType = Jval.newObject();
        for(Map.Entry<String, Integer> e : enemyCounts.entrySet()){
            byType.put(e.getKey(), e.getValue());
        }
        out.put("byType", byType);
        return out;
    }
}
