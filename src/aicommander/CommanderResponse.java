package aicommander;

import arc.util.serialization.Jval;

public final class CommanderResponse{
    public final String message;
    public final int orderCount;
    public final String raw;

    private CommanderResponse(String message, int orderCount, String raw){
        this.message = message;
        this.orderCount = orderCount;
        this.raw = raw;
    }

    public static CommanderResponse parse(String text){
        String json = extractJson(text);
        Jval root = Jval.read(json);
        if(!root.isObject()){
            throw new IllegalArgumentException("model response is not a JSON object");
        }

        String message = root.getString("message", "");
        Jval orders = root.get("orders");
        int count = orders != null && orders.isArray() ? orders.asArray().size : 0;
        return new CommanderResponse(message, count, text);
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
