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
 * Simple blocks and a few composite blueprints are supported. Multi-block
 * schematics use local offsets relative to the order's origin tile.
 *
 * Available names (LLM should use these in "schematic" field of build orders):
 *   "copper-drill"     – mechanical drill (mines copper/lead/coal/sand)
 *   "graphite-drill"   – pneumatic drill (faster, needs power)
 *   "conveyor"         – basic conveyor
 *   "junction"         – junction block
 *   "duo"              – Duo turret (copper/graphite/silicon ammo)
 *   "scatter"          – Scatter turret (lead/metaglass/scrap, early anti-air)
 *   "hail"             – Hail artillery (graphite/silicon/pyratite)
 *   "unloader"         – unloader (moves items from an adjacent core/container)
 *   "container"        – large container for resource buffering
 *   "vault"            – vault (large storage)
 *   "power-node"       – basic power node
 *   "combustion"       – combustion generator (coal)
 *   "solar-panel"      – solar panel (fuel-free, low output)
 *   "battery"          – power battery buffer
 *   "silicon-smelter"  – silicon smelter (coal + sand)
 *   "graphite-press"   – graphite press (coal)
 *   "kiln"             – kiln (coal + sand -> metaglass)
 */
public final class SchematicLibrary{
    public record BlockEntry(int dx, int dy, Block block, int rotation){}

    public record BuildSpec(Block block, List<BlockEntry> composite){
        public BuildSpec(Block block){
            this(block, Collections.singletonList(new BlockEntry(0, 0, block, 0)));
        }

        /** Human-readable description for the LLM prompt. */
        public String describe(){
            return block != null ? block.name : "composite";
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
        register("unloader",        Blocks.unloader);
        register("silicon-smelter", Blocks.siliconSmelter);
        register("graphite-press",  Blocks.graphitePress);
        register("kiln",            Blocks.kiln);
        register("air-factory",     Blocks.airFactory);
        register("ground-factory",  Blocks.groundFactory);
        register("repair-point",    Blocks.repairPoint);
        register("mender",          Blocks.mender);
        register("mend-projector",  Blocks.mendProjector);
        register("wave",            Blocks.wave);    // liquid turret (consumes water/cryofluid/slag)
        register("lancer",          Blocks.lancer);
        register("arc",             Blocks.arc);
        register("salvo",           Blocks.salvo);

        // Composite tactical blueprints
        registerComposite("duo-bunker", Arrays.asList(
            new BlockEntry(0, 0, Blocks.duo, 0),
            new BlockEntry(0, 1, Blocks.copperWall, 0),
            new BlockEntry(1, 0, Blocks.copperWall, 0),
            new BlockEntry(-1, 0, Blocks.copperWall, 0),
            new BlockEntry(0, -1, Blocks.router, 0)
        ));

        registerComposite("duo-battery-cluster", Arrays.asList(
            new BlockEntry(0, 0, Blocks.duo, 0),
            new BlockEntry(1, 0, Blocks.duo, 0),
            new BlockEntry(0, 1, Blocks.copperWall, 0),
            new BlockEntry(1, 1, Blocks.copperWall, 0),
            new BlockEntry(0, -1, Blocks.router, 0),
            new BlockEntry(1, -1, Blocks.copperWall, 0)
        ));

        registerComposite("scatter-nest", Arrays.asList(
            new BlockEntry(0, 0, Blocks.scatter, 0),
            new BlockEntry(0, 2, Blocks.copperWallLarge, 0),
            new BlockEntry(0, -2, Blocks.copperWallLarge, 0)
        ));

        registerComposite("drill-power-outpost", Arrays.asList(
            new BlockEntry(0, 0, Blocks.mechanicalDrill, 0),
            new BlockEntry(2, 0, Blocks.powerNode, 0)
        ));
    }

    private SchematicLibrary(){}

    private static void register(String name, Block block){
        if(block != null) REGISTRY.put(name, new BuildSpec(block));
    }

    private static void registerComposite(String name, List<BlockEntry> entries){
        if(entries != null && !entries.isEmpty()){
            REGISTRY.put(name, new BuildSpec(entries.get(0).block(), entries));
        }
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
