package aicommander;

import arc.util.serialization.Jval;

import java.util.*;

import static mindustry.Vars.tilesize;

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
    /** True when this order explicitly carries a line endpoint. */
    public final boolean line;
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

    private Order(Type type, float x, float y, float x2, float y2, boolean line, String schematic, int rotation, String unitType, String stance, String raw){
        this.type = type;
        this.x = x;
        this.y = y;
        this.x2 = x2;
        this.y2 = y2;
        this.line = line;
        this.schematic = schematic;
        this.rotation = rotation;
        this.unitType = unitType;
        this.stance = stance;
        this.raw = raw;
    }

    public static Order parse(Jval obj){
        String typeStr = obj.getString("type", "unknown").trim().toLowerCase(Locale.ROOT);
        Type type = mapType(typeStr);

        boolean hasPos = hasCoordinate(obj, "x", "tx") && hasCoordinate(obj, "y", "ty");
        boolean hasEnd = hasCoordinate(obj, "x2", "tx2") && hasCoordinate(obj, "y2", "ty2");
        float x = coordinate(obj, "x", "tx");
        float y = coordinate(obj, "y", "ty");
        float x2 = coordinate(obj, "x2", "tx2");
        float y2 = coordinate(obj, "y2", "ty2");
        String schematic = firstString(obj, "schematic", "block", "name");
        int rotation = ((int)obj.getDouble("rotation", 0.0)) % 4;
        if(rotation < 0) rotation += 4;
        String unitType = firstString(obj, "unit", "unitType", "unitName");
        String stance = firstString(obj, "stance", "order");
        if(stance.isEmpty()) stance = "defend";
        String raw = obj.toString();

        if((type == Type.build || type == Type.defend || type == Type.attack || type == Type.mine || type == Type.repair) && !hasPos){
            type = Type.unknown;
        }
        if(type == Type.build && schematic.isBlank()){
            type = Type.unknown;
        }
        if(type == Type.produce && unitType.isBlank()){
            type = Type.unknown;
        }

        boolean line = hasEnd && (obj.has("x2") || obj.has("y2") || obj.has("tx2") || obj.has("ty2"));
        x = clampX(x);
        y = clampY(y);
        x2 = clampX(x2);
        y2 = clampY(y2);
        return new Order(type, x, y, x2, y2, line, schematic, rotation, unitType, stance, raw);
    }

    private static Type mapType(String value){
        return switch(value){
            case "build-line", "buildline", "line", "conveyor-line" -> Type.build;
            case "move", "hold", "defend" -> Type.defend;
            case "attackmove", "attack_move", "assault" -> Type.attack;
            case "produce", "unit", "train" -> Type.produce;
            case "repair", "mine", "stance", "attack", "build" -> Type.valueOf(value);
            default -> Type.unknown;
        };
    }

    /** Reads a pixel coordinate, or a tile coordinate (tx/ty) converted to pixels. */
    private static float coordinate(Jval obj, String pixelKey, String tileKey){
        float value;
        if(obj.has(pixelKey)){
            value = (float)obj.getDouble(pixelKey, 0.0);
        }else if(obj.has(tileKey)){
            value = (float)obj.getDouble(tileKey, 0.0) * tilesize;
        }else{
            return 0f;
        }
        return Float.isFinite(value) ? value : 0f;
    }

    private static boolean hasCoordinate(Jval obj, String pixelKey, String tileKey){
        if(obj.has(pixelKey)) return Float.isFinite((float)obj.getDouble(pixelKey, 0.0));
        if(obj.has(tileKey)) return Float.isFinite((float)obj.getDouble(tileKey, 0.0));
        return false;
    }

    private static float clampX(float value){
        if(mindustry.Vars.world == null) return Math.max(0f, value);
        return clamp(value, mindustry.Vars.world.width() * (float)tilesize);
    }

    private static float clampY(float value){
        if(mindustry.Vars.world == null) return Math.max(0f, value);
        return clamp(value, mindustry.Vars.world.height() * (float)tilesize);
    }

    private static float clamp(float value, float max){
        if(!Float.isFinite(value) || max <= 0f) return 0f;
        return Math.max(0f, Math.min(max - 0.001f, value));
    }

    private static String firstString(Jval obj, String... keys){
        for(String key : keys){
            if(obj.has(key)){
                String value = obj.getString(key, "");
                if(value != null && !value.isBlank()) return value.trim();
            }
        }
        return "";
    }

    public static Order build(String schematic, float x, float y, int rotation){
        return new Order(Type.build, x, y, 0, 0, false, schematic, rotation, "", "defend", "");
    }

    public static Order buildLine(String schematic, float x1, float y1, float x2, float y2){
        return new Order(Type.build, x1, y1, x2, y2, true, schematic, 0, "", "defend", "");
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
            case build -> line
                ? "buildLine(" + schematic + "@" + (int)x + "," + (int)y + "->" + (int)x2 + "," + (int)y2 + ")"
                : "build(" + schematic + "@" + (int)x + "," + (int)y + " r" + rotation + ")";
            case produce -> "produce(" + unitType + ")";
            case stance -> "stance(" + stance + (x != 0 || y != 0 ? "@" + (int)x + "," + (int)y : "") + ")";
            default -> type.name() + "(" + (int)x + "," + (int)y + ")";
        };
    }
}
