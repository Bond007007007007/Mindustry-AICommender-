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
        defend, attack, build, repair, mine, produce, unknown
    }

    public final Type type;
    /** World pixel coordinates (may be 0 if not applicable). */
    public final float x, y;
    /** For build orders: schematic name from SchematicLibrary. */
    public final String schematic;
    /** For build orders: 0-3 rotation. */
    public final int rotation;
    /** For produce orders: unit type name. */
    public final String unitType;
    /** Raw JSON for debugging. */
    public final String raw;

    private Order(Type type, float x, float y, String schematic, int rotation, String unitType, String raw){
        this.type = type;
        this.x = x;
        this.y = y;
        this.schematic = schematic;
        this.rotation = rotation;
        this.unitType = unitType;
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
        String schematic = obj.getString("schematic", "");
        int rotation = (int)obj.getDouble("rotation", 0.0);
        String unitType = obj.getString("unit", "");
        String raw = obj.toString();

        return new Order(type, x, y, schematic, rotation, unitType, raw);
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
            case build -> "build(" + schematic + "@" + (int)x + "," + (int)y + " r" + rotation + ")";
            case produce -> "produce(" + unitType + ")";
            default -> type.name() + "(" + (int)x + "," + (int)y + ")";
        };
    }
}
