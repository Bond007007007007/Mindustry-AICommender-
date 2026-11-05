package aicommander;

import arc.util.serialization.Jval;
import java.util.*;

/**
 * High-level strategic doctrine issued by the LLM Commander every wave.
 * Consumed by local 60 FPS deterministic bot roles to guide autonomous behavior.
 */
public final class Doctrine{
    public enum Posture{
        EXPAND, TURTLE, TECH_RUSH, FORTIFY
    }

    public enum DefenseFocus{
        ANTI_AIR, GROUND_SWARM, HEAVY_ARMOR, BALANCED, REPAIR
    }

    public final Posture posture;
    public final DefenseFocus defenseFocus;
    public final Map<String, Float> resourceWeights;
    public final String squadStance;
    public final List<Order> buildDirectives;
    public final String reasoning;
    public final String raw;

    public Doctrine(Posture posture, DefenseFocus defenseFocus, Map<String, Float> resourceWeights,
                    String squadStance, List<Order> buildDirectives, String reasoning, String raw){
        this.posture = posture;
        this.defenseFocus = defenseFocus;
        this.resourceWeights = resourceWeights;
        this.squadStance = squadStance;
        this.buildDirectives = buildDirectives;
        this.reasoning = reasoning;
        this.raw = raw;
    }

    /** Default fallback doctrine before first LLM response or when reset. */
    public static Doctrine defaultDoctrine(){
        Map<String, Float> weights = new HashMap<>();
        weights.put("copper", 1.0f);
        weights.put("lead", 0.8f);
        weights.put("coal", 0.5f);
        weights.put("silicon", 0.2f);
        return new Doctrine(Posture.EXPAND, DefenseFocus.BALANCED, weights, "defend",
                Collections.emptyList(), "Initial bootstrapping doctrine", "");
    }

    public static Doctrine parse(Jval root, String raw){
        if(root == null || !root.isObject()){
            return defaultDoctrine();
        }

        String postureStr = normalizeEnum(root.getString("posture", "EXPAND"));
        Posture posture;
        try{
            posture = Posture.valueOf(postureStr);
        }catch(Exception e){
            posture = Posture.EXPAND;
        }

        String defenseStr = normalizeEnum(root.getString("defenseFocus", "BALANCED"));
        DefenseFocus defenseFocus;
        try{
            defenseFocus = DefenseFocus.valueOf(defenseStr);
        }catch(Exception e){
            defenseFocus = DefenseFocus.BALANCED;
        }

        Map<String, Float> weights = new HashMap<>();
        Jval weightsNode = root.get("resourcePriorities");
        if(weightsNode == null) weightsNode = root.get("resourceWeights");
        if(weightsNode != null && weightsNode.isObject()){
            String[] commonItems = {"copper", "lead", "coal", "sand", "titanium", "thorium", "scrap", "silicon", "graphite", "metaglass", "plastanium", "phase-fabric", "surge-alloy"};
            for(String item : commonItems){
                if(weightsNode.has(item)){
                    float value = (float)weightsNode.getDouble(item, 0.5);
                    if(Float.isFinite(value)){
                        weights.put(item, Math.max(0f, Math.min(1f, value)));
                    }
                }
            }
        }
        boolean anyWeight = weights.values().stream().anyMatch(v -> v != null && v > 0f);
        if(!anyWeight){
            weights.clear();
            weights.put("copper", 1.0f);
            weights.put("lead", 0.8f);
            weights.put("coal", 0.5f);
            weights.put("sand", 0.3f);
            weights.put("silicon", 0.2f);
        }

        String squadStance = root.getString("squadStance", "defend").trim().toLowerCase(Locale.ROOT);
        if(!squadStance.equals("defend") && !squadStance.equals("rally")
            && !squadStance.equals("assault") && !squadStance.equals("retreat")){
            squadStance = "defend";
        }
        String reasoning = root.getString("reasoning", root.getString("message", "")).trim();
        if(reasoning.length() > 600) reasoning = reasoning.substring(0, 600) + "...";

        Jval directivesNode = root.get("buildDirectives");
        if(directivesNode == null) directivesNode = root.get("orders");
        List<Order> orders = new ArrayList<>();
        for(Order order : Order.parseAll(directivesNode)){
            if(order.type != Order.Type.unknown && orders.size() < 4){
                orders.add(order);
            }
        }

        return new Doctrine(posture, defenseFocus, weights, squadStance, orders, reasoning, raw);
    }

    private static String normalizeEnum(String value){
        if(value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    public float getResourceWeight(String item, float defaultVal){
        if(item == null) return defaultVal;
        return resourceWeights.getOrDefault(item.toLowerCase(Locale.ROOT), defaultVal);
    }
}
