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
          Builder   – places blocks from your "build" orders using native BuilderAI
          Defender  – holds positions from your "defend" orders using native CommandAI
          Scout     – attacks/explores positions from your "attack" orders using native CommandAI

        """;

    private static final String JSON_CONTRACT = """

        Return exactly one JSON object, no markdown, no text outside it:
        {
          "message": "2-3 sentence situation report and reasoning",
          "orders": [
            {"type": "defend", "x": 0, "y": 0},
            {"type": "attack", "x": 0, "y": 0},
            {"type": "build", "schematic": "NAME", "x": 0, "y": 0, "rotation": 0}
          ],
          "settings": {}
        }

        Coordinates are world PIXEL coordinates (not tile coords). Multiply tile coords by 8.
        Use an empty orders array when no new orders are needed.
        Allowed order types: defend, attack, build.
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
