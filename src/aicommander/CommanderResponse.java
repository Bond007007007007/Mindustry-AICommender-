package aicommander;

import arc.util.serialization.Jval;
import java.util.List;

/**
 * Extended parse of the LLM JSON response, parsing Doctrine, directives, and status report.
 */
public final class CommanderResponse{
    public final String message;
    public final Doctrine doctrine;
    public final List<Order> orders;
    public final int orderCount;
    public final String raw;

    private CommanderResponse(String message, Doctrine doctrine, List<Order> orders, String raw){
        this.message = message;
        this.doctrine = doctrine;
        this.orders = orders;
        this.orderCount = orders.size();
        this.raw = raw;
    }

    public static CommanderResponse parse(String text){
        String json = extractJson(text);
        Jval root = Jval.read(json);
        if(!root.isObject()){
            throw new IllegalArgumentException("model response is not a JSON object");
        }

        Doctrine doc = Doctrine.parse(root, text);
        String message = doc.reasoning;

        return new CommanderResponse(message, doc, doc.buildDirectives, text);
    }

    private static String extractJson(String text){
        String value = text == null ? "" : text.trim();

        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if(start >= 0 && end > start){
            return value.substring(start, end + 1);
        }

        throw new IllegalArgumentException("model response contains no JSON object");
    }
}

