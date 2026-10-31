package aicommander;

import mindustry.content.*;
import mindustry.world.Block;

import java.util.*;

/**
 * Registry of named build recipes the Commander can reference by schematic name.
 *
 * Each entry maps a short logical name (sent in LLM JSON) to a BuildSpec that
 * BuilderRole can translate into actual BuildPlan calls.
 *
 * We keep it simple: single-block placements only for now.
 * Multi-block schematics would require the Schematic API which is harder to manage
 * without a file system — deferred to a later milestone.
 *
 * Available names (LLM should use these in "schematic" field of build orders):
 *   "copper-drill"     – mechanical drill (mines copper/lead/stone)
 *   "graphite-drill"   – graphite drill (faster, higher tier)
 *   "conveyor"         – basic conveyor
 *   "junction"         – junction block
 *   "duo"              – Duo turret (copper ammo, early defense)
 *   "scatter"          – Scatter turret (pyratite)
 *   "hail"             – Hail turret (blast compound, splash)
 *   "container"        – large container for resource buffering
 *   "vault"            – vault (large storage)
 *   "core-shard"       – core shard (handled specially, usually auto-placed)
 *   "power-node"       – basic power node
 *   "combustion"       – combustion generator (coal)
 *   "silicon-smelter"  – silicon smelter
 *   "graphite-press"   – graphite press
 *   "kiln"             – kiln (ceramics / surge alloy precursor)
 */
public final class SchematicLibrary{
    public record BuildSpec(Block block){
        /** Human-readable description for the LLM prompt. */
        public String describe(){
            return block.name;
        }
    }

    private static final Map<String, BuildSpec> REGISTRY = new LinkedHashMap<>();

    static{
        register("copper-drill",    Blocks.mechanicalDrill);
        register("graphite-drill",  Blocks.pneumaticDrill);
        register("conveyor",        Blocks.conveyor);
        register("router",          Blocks.router);
        register("distributor",     Blocks.distributor);
        register("junction",        Blocks.junction);
        register("bridge-conveyor", Blocks.itemBridge);
        register("duo",             Blocks.duo);
        register("copper-wall",     Blocks.copperWall);
        register("copper-wall-large", Blocks.copperWallLarge);
        register("scatter",         Blocks.scatter);
        register("hail",            Blocks.hail);
        register("container",       Blocks.container);
        register("vault",           Blocks.vault);
        register("power-node",      Blocks.powerNode);
        register("power-node-large",Blocks.powerNodeLarge);
        register("combustion",      Blocks.combustionGenerator);
        register("solar-panel",     Blocks.solarPanel);
        register("battery",         Blocks.battery);
        register("silicon-smelter", Blocks.siliconSmelter);
        register("graphite-press",  Blocks.graphitePress);
        register("kiln",            Blocks.kiln);
        register("air-factory",     Blocks.airFactory);
        register("ground-factory",  Blocks.groundFactory);
        register("repair-point",    Blocks.repairPoint);
        register("wave",            Blocks.wave);    // liquid source — useful for turret cooling
        register("lancer",          Blocks.lancer);
        register("arc",             Blocks.arc);
        register("salvo",           Blocks.salvo);
    }

    private SchematicLibrary(){}

    private static void register(String name, Block block){
        if(block != null) REGISTRY.put(name, new BuildSpec(block));
    }

    /** Returns null if not found. */
    public static BuildSpec get(String name){
        if(name == null) return null;
        return REGISTRY.get(name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Returns the full map for embedding in the prompt. */
    public static Map<String, BuildSpec> all(){
        return Collections.unmodifiableMap(REGISTRY);
    }

    /** Compact list of available names for embedding in the prompt. */
    public static String availableNames(){
        return String.join(", ", REGISTRY.keySet());
    }
}
