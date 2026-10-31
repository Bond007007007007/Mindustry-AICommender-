package aicommander;

import arc.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.ui.dialogs.SettingsMenuDialog.*;

public final class Settings{
    public static final String ENABLED = "ai-commander.enabled";
    public static final String AUTO_START = "ai-commander.auto-start";
    public static final String BASE_URL = "ai-commander.base-url";
    public static final String USERNAME = "ai-commander.username";
    public static final String PASSWORD = "ai-commander.password";
    public static final String PROVIDER = "ai-commander.provider";
    public static final String MODEL = "ai-commander.model";
    public static final String INTERVAL = "ai-commander.interval";

    private Settings(){
    }

    public static void install(){
        Vars.ui.settings.addCategory("AI Commander", Icon.settings, Settings::build);
    }

    private static void build(SettingsTable table){
        table.checkPref(ENABLED, true);
        table.checkPref(AUTO_START, true);
        table.textPref(BASE_URL, "http://127.0.0.1:4098");
        table.textPref(USERNAME, "");
        table.textPref(PASSWORD, "");
        table.textPref(PROVIDER, "opencode");
        table.textPref(MODEL, "deepseek-v4.1-flash-free");
        table.sliderPref(INTERVAL, 15, 5, 60, 1, i -> i + "s");
    }

    public static boolean enabled(){
        return Core.settings.getBool(ENABLED, true);
    }

    public static boolean autoStart(){
        return Core.settings.getBool(AUTO_START, true);
    }

    public static String baseUrl(){
        return trimSlash(Core.settings.getString(BASE_URL, "http://127.0.0.1:4098"));
    }

    public static String username(){
        return Core.settings.getString(USERNAME, "");
    }

    public static String password(){
        return Core.settings.getString(PASSWORD, "");
    }

    public static String provider(){
        return Core.settings.getString(PROVIDER, "opencode");
    }

    public static String model(){
        return Core.settings.getString(MODEL, "deepseek-v4.1-flash-free");
    }

    public static int interval(){
        return Core.settings.getInt(INTERVAL, 15);
    }

    private static String trimSlash(String value){
        if(value == null || value.isEmpty()) return "http://127.0.0.1:4098";
        while(value.endsWith("/")){
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
