package aicommander;

import arc.util.serialization.Jval;

import java.util.*;

/**
 * Typed representation of a single Commander order, parsed from the LLM JSON response.
 *
 * Supported types:
 *   defend  – x,y  (move units to hold position)
 *   attack  – x,y  (move units to attack position)
 *   build   – schematic, x, y, rotation
 *   repair  – x,y  (units repair the building at tile)
 *   mine    – x,y  (units mine ore at tile)
 *   produce – unit type name to produce
 */
public final class Order{
    public enum Type{
        defend, attack, build, repair, mine, produce, stance, unknown
    }

    public final Type type;
    /** World pixel coordinates (may be 0 if not applicable). */
    public final float x, y;
    /** End coordinates for line orders (e.g. conveyor line). */
    public final float x2, y2;
    /** For build orders: schematic name from SchematicLibrary. */
    public final String schematic;
    /** For build orders: 0-3 rotation. */
    public final int rotation;
    /** For produce orders: unit type name. */
    public final String unitType;
    /** For stance orders: defend, rally, assault, retreat. */
    public final String stance;
    /** Raw JSON for debugging. */
    public final String raw;

    private Order(Type type, float x, float y, float x2, float y2, String schematic, int rotation, String unitType, String stance, String raw){
        this.type = type;
        this.x = x;
        this.y = y;
        this.x2 = x2;
        this.y2 = y2;
        this.schematic = schematic;
        this.rotation = rotation;
        this.unitType = unitType;
        this.stance = stance;
        this.raw = raw;
    }

    public static Order parse(Jval obj){
        String typeStr = obj.getString("type", "unknown").trim().toLowerCase(Locale.ROOT);
        Type type;
        try{
            type = Type.valueOf(typeStr);
        }catch(IllegalArgumentException e){
            type = Type.unknown;
        }

        float x = (float)obj.getDouble("x", 0.0);
        float y = (float)obj.getDouble("y", 0.0);
        float x2 = (float)obj.getDouble("x2", 0.0);
        float y2 = (float)obj.getDouble("y2", 0.0);
        String schematic = obj.getString("schematic", "");
        int rotation = (int)obj.getDouble("rotation", 0.0);
        String unitType = obj.getString("unit", "");
        String stance = obj.getString("stance", "defend");
        String raw = obj.toString();

        return new Order(type, x, y, x2, y2, schematic, rotation, unitType, stance, raw);
    }

    public static Order build(String schematic, float x, float y, int rotation){
        return new Order(Type.build, x, y, 0, 0, schematic, rotation, "", "defend", "");
    }

    public static Order buildLine(String schematic, float x1, float y1, float x2, float y2){
        return new Order(Type.build, x1, y1, x2, y2, schematic, 0, "", "defend", "");
    }

    public static List<Order> parseAll(Jval ordersArray){
        List<Order> result = new ArrayList<>();
        if(ordersArray == null || !ordersArray.isArray()) return result;
        for(Jval item : ordersArray.asArray()){
            if(item != null && item.isObject()){
                result.add(parse(item));
            }
        }
        return result;
    }

    @Override
    public String toString(){
        return switch(type){
            case build -> (x2 != 0 || y2 != 0)
                ? "buildLine(" + schematic + "@" + (int)x + "," + (int)y + "->" + (int)x2 + "," + (int)y2 + ")"
                : "build(" + schematic + "@" + (int)x + "," + (int)y + " r" + rotation + ")";
            case produce -> "produce(" + unitType + ")";
            case stance -> "stance(" + stance + (x != 0 || y != 0 ? "@" + (int)x + "," + (int)y : "") + ")";
            default -> type.name() + "(" + (int)x + "," + (int)y + ")";
        };
    }
}
