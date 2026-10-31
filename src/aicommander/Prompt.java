package aicommander;

/**
 * Builds the prompt sent to the Commander LLM each polling cycle.
 *
 * The prompt includes:
 *  - A system preamble (role, rules, JSON contract)
 *  - Available schematics from SchematicLibrary
 *  - Current state JSON
 *  - Previous Commander message for continuity
 */
public final class Prompt{
    private static final String SYSTEM_HEADER = """
        You are the Commander of an AI-controlled team in Mindustry v160.
        The match is one endless survival game on the Fortress map under strict normal rules.
        Your score is the highest wave reached. The run ends when the team's last core is destroyed.

        You are not a unit controller. Scripted roles handle movement, mining, building, production,
        and combat. You issue durable orders and priorities. The game runs continuously while you
        think, so do not depend on pausing or on frame-perfect timing.

        Rules:
        - Never assume free resources, extra units, omniscient vision, or edited game rules.
        - Only use information in the provided state. Hidden enemies are not shown.
        - Prefer robust, resource-aware plans over risky all-ins.
        - Keep the core alive, keep the economy running, and scale defense with the wave number.
        - Orders persist until replaced. Only issue new orders when the situation has changed.
        - Do not issue more than 6 orders per turn. Prioritize the most urgent ones.

        Roles managed by scripts (you give orders, scripts execute):
          Builder   – places blocks from your "build" orders and drives player/builder units to construct them
          Defender  – holds positions from your "defend" orders, protects core, and engages nearby attackers
          Scout     – attacks/explores positions from your "attack" orders using native CommandAI
          Repair    – automatically prioritizes and repairs damaged turrets, walls, and structures
          Logistics – gathers copper/lead via "mine" orders or automatically mines to supply the core when idle

        Early Game Bootstrapping & Tactical Defense Strategy:
        1. Check "spawns" in state: know where enemy waves spawn and their distance to your core!
        2. Look at "ores" in state to find closest copper, lead, and coal.
        3. Place a mechanical drill ("copper-drill") on the ore coordinates.
        4. Connect the drill to the core using a conveyor line order:
           {"type": "build", "schematic": "conveyor", "x": drillX, "y": drillY, "x2": coreX, "y2": coreY}
           (The game will automatically lay down the entire conveyor path and orient it towards the core!)
        5. Build defensive choke points facing "spawns": "copper-wall-large", "duo", "scatter" (anti-air), and "mender".
           CRITICAL: Feed ammo (copper/lead) into your turrets via conveyors! Check "turrets" in state for ammo status.
        6. Monitor "power" in state (produced, needed, batteryStored):
           Place "combustion" power generators near coal and wire them with "power-node"s.
           (Power nodes automatically discover and link to nearby power buildings in laser range!)
        7. Build "air-factory" and issue {"type": "produce", "unit": "mono"} to produce autonomous miner units!

        """;

    private static final String JSON_CONTRACT = """

        Return exactly one JSON object, no markdown, no text outside it:
        {
          "message": "2-3 sentence situation report and reasoning",
          "orders": [
            {"type": "defend", "x": 0, "y": 0},
            {"type": "attack", "x": 0, "y": 0},
            {"type": "build", "schematic": "NAME", "x": 0, "y": 0, "rotation": 0},
            {"type": "build", "schematic": "conveyor", "x": 0, "y": 0, "x2": 100, "y2": 100},
            {"type": "produce", "unit": "mono"}
          ],
          "settings": {}
        }

        Coordinates are world PIXEL coordinates (not tile coords). Multiply tile coords by 8.
        Use an empty orders array when no new orders are needed.
        Allowed order types: defend, attack, build, mine, produce.
        For "build" with conveyor/bridge/conduit: providing "x2" and "y2" will lay a connected line of tiles.
        For "mine": use schematic "copper", "lead", or "coal".
        For "produce": unit can be "mono", "poly", "flare", or "dagger".
        """;

    private Prompt(){}

    public static String build(String stateJson, int callNumber, String previousMessage){
        StringBuilder out = new StringBuilder(2048);

        out.append(SYSTEM_HEADER);

        // Embed available schematics.
        out.append("Available build schematics (use exact name in \"schematic\" field):\n  ");
        out.append(SchematicLibrary.availableNames());
        out.append('\n');

        out.append(JSON_CONTRACT);

        out.append("\nCommander call: ").append(callNumber).append('\n');
        if(previousMessage != null && !previousMessage.isEmpty()){
            out.append("Your previous report: ").append(previousMessage).append('\n');
        }
        out.append("\nCurrent authoritative state:\n");
        out.append(stateJson);
        out.append("\n\nReturn the JSON object now.");

        return out.toString();
    }
}
