package aicommander;

import arc.util.serialization.Jval;
import mindustry.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.*;

import static mindustry.Vars.*;

public final class StateSnapshot{
    private static final int MAX_UNITS = 60;
    private static final int MAX_BUILDINGS = 60;
    private static final int MAX_ENEMIES = 40;

    private StateSnapshot(){
    }

    public static Jval capture(){
        Jval root = Jval.newObject();
        root.put("map", state.map.name());
        root.put("wave", state.wave);
        root.put("waveIn", Math.max(0, Math.round(state.wavetime / 60f)));
        root.put("tick", (double)state.tick);
        root.put("gameOver", state.gameOver);

        Team team = player.team();
        CoreBuild core = team.cores().firstOpt();

        if(core != null){
            root.put("core", core(core));
        }

        Unit commander = player.unit();
        if(commander != null){
            root.put("player", unit(commander));
        }

        root.put("ores", ores(core));
        root.put("units", units(team));
        root.put("buildings", buildings(team));
        root.put("enemies", enemies(team));

        return root;
    }

    private static Jval ores(CoreBuild core){
        Jval list = Jval.newArray();
        if(core == null || indexer == null) return list;

        Item[] targets = {Items.copper, Items.lead, Items.coal, Items.titanium};
        for(Item item : targets){
            Tile closest = indexer.findClosestOre(core.x, core.y, item);
            if(closest != null){
                Jval o = Jval.newObject();
                o.put("item", item.name);
                o.put("x", (double)closest.worldx());
                o.put("y", (double)closest.worldy());
                o.put("dist", (double)Math.round(core.dst(closest)));
                list.add(o);
            }
        }
        return list;
    }

    private static Jval core(CoreBuild core){
        Jval out = Jval.newObject();
        out.put("x", (double)core.x);
        out.put("y", (double)core.y);
        out.put("health", (double)core.health);
        out.put("maxHealth", (double)core.maxHealth);
        out.put("capacity", core.storageCapacity);

        Jval items = Jval.newObject();
        for(Item item : content.items()){
            int amount = core.items.get(item);
            if(amount > 0) items.put(item.name, amount);
        }
        out.put("items", items);
        return out;
    }

    private static Jval unit(Unit unit){
        Jval out = Jval.newObject();
        out.put("id", unit.id);
        out.put("type", unit.type.name);
        out.put("x", (double)unit.x);
        out.put("y", (double)unit.y);
        out.put("health", (double)unit.health);
        out.put("maxHealth", (double)unit.maxHealth);

        if(unit.controller() instanceof mindustry.ai.types.CommandAI ai){
            out.put("command", ai.currentCommand().name);
            if(ai.targetPos != null){
                out.put("targetX", (double)ai.targetPos.x);
                out.put("targetY", (double)ai.targetPos.y);
            }
        }

        return out;
    }

    private static Jval units(Team team){
        Jval out = Jval.newObject();
        Jval list = Jval.newArray();
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;

        for(Unit unit : team.data().units){
            if(unit == null || !unit.isValid()) continue;
            total++;
            counts.merge(unit.type.name, 1, Integer::sum);
            if(list.asArray().size < MAX_UNITS) list.add(unit(unit));
        }

        Jval byType = Jval.newObject();
        for(Map.Entry<String, Integer> entry : counts.entrySet()){
            byType.put(entry.getKey(), entry.getValue());
        }

        out.put("total", total);
        out.put("byType", byType);
        out.put("list", list);
        return out;
    }

    private static Jval buildings(Team team){
        Jval out = Jval.newObject();
        Jval list = Jval.newArray();
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;

        for(Building build : team.data().buildings){
            if(build == null || !build.isValid()) continue;
            total++;
            counts.merge(build.block.name, 1, Integer::sum);
            if(list.asArray().size < MAX_BUILDINGS){
                Jval entry = Jval.newObject();
                entry.put("id", build.id);
                entry.put("type", build.block.name);
                entry.put("x", (double)build.x);
                entry.put("y", (double)build.y);
                entry.put("health", (double)build.health);
                entry.put("maxHealth", (double)build.maxHealth);
                list.add(entry);
            }
        }

        Jval byType = Jval.newObject();
        for(Map.Entry<String, Integer> entry : counts.entrySet()){
            byType.put(entry.getKey(), entry.getValue());
        }

        out.put("total", total);
        out.put("byType", byType);
        out.put("list", list);
        return out;
    }

    private static Jval enemies(Team team){
        Jval out = Jval.newObject();
        Jval list = Jval.newArray();
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;

        for(Unit unit : Groups.unit){
            if(unit == null || !unit.isValid() || unit.team == team || unit.team == Team.derelict) continue;
            if(unit.inFogTo(team)) continue;

            total++;
            counts.merge(unit.type.name, 1, Integer::sum);
            if(list.asArray().size < MAX_ENEMIES){
                Jval entry = Jval.newObject();
                entry.put("id", unit.id);
                entry.put("type", unit.type.name);
                entry.put("team", unit.team.name);
                entry.put("x", (double)unit.x);
                entry.put("y", (double)unit.y);
                entry.put("health", (double)unit.health);
                entry.put("maxHealth", (double)unit.maxHealth);
                list.add(entry);
            }
        }

        Jval byType = Jval.newObject();
        for(Map.Entry<String, Integer> entry : counts.entrySet()){
            byType.put(entry.getKey(), entry.getValue());
        }

        out.put("total", total);
        out.put("byType", byType);
        out.put("list", list);
        return out;
    }
}
