package aicommander;

import arc.*;
import arc.files.*;
import arc.util.*;
import arc.util.serialization.Jval;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.*;
import java.util.concurrent.*;

import static mindustry.Vars.*;

public class CommanderController{
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ai-commander-model");
        thread.setDaemon(true);
        return thread;
    });

    private final ArrayDeque<String> events = new ArrayDeque<>();

    private volatile boolean active;
    private volatile boolean busy;
    private volatile String lastMessage = "";
    private volatile String lastError = "";
    private volatile int lastOrderCount;
    private volatile int promptCount;
    private volatile int errorCount;
    private volatile long lastReplyAt;
    private volatile String snapshotJson = "{}";
    private volatile String sessionId;

    private final UnitRoleManager roles = new UnitRoleManager();
    private final PostMatchReview review = new PostMatchReview();

    private ModelClient client;
    private Fi logFile;
    private String mapName = "";
    private int finalWave;
    private float lastUpdate;
    private float lastPrompt;
    private float lastCoreHealth = -1f;
    private int lastWave = -1;

    public CommanderController(){
        Events.on(WaveEvent.class, e -> event("wave", "wave " + state.wave));
        Events.on(UnitDestroyEvent.class, e -> {
            if(e.unit != null && e.unit.team == player.team()){
                event("unit_lost", e.unit.type.name);
            }
        });
        Events.on(BlockDestroyEvent.class, e -> {
            if(e.tile != null && e.tile.build != null && e.tile.build.team == player.team()){
                event("block_lost", e.tile.block().name);
            }
        });
        Events.on(GameOverEvent.class, e -> {
            event("game_over", "core destroyed");
            stop("game over");
        });
    }

    public void onWorldLoad(){
        if(!Settings.enabled()){
            stop("disabled in settings");
            return;
        }

        if(!state.isGame() || state.isCampaign() || !state.rules.waves){
            stop("not a survival match");
            return;
        }

        mapName = state.map.name();

        if(Settings.autoStart() && !isFortress(mapName)){
            stop("waiting for Fortress");
            return;
        }

        start();
    }

    public void start(){
        if(active) return;

        if(!state.isGame() || state.isCampaign() || !state.rules.waves){
            lastError = "not an endless survival match";
            return;
        }

        if(!Settings.enabled()){
            lastError = "disabled in settings";
            return;
        }

        active = true;
        busy = false;
        lastMessage = "";
        lastError = "";
        lastOrderCount = 0;
        promptCount = 0;
        errorCount = 0;
        lastReplyAt = 0;
        lastPrompt = 0f;
        lastUpdate = 0f;
        sessionId = null;
        mapName = state.map.name();
        events.clear();

        CoreBuild core = core();
        lastCoreHealth = core == null ? -1f : core.health;
        lastWave = state.wave;

        client = new ModelClient(Settings.baseUrl(), Settings.username(), Settings.password(),
            Settings.provider(), Settings.model());

        openLog();
        snapshotJson = snapshotWithEvents().toString();
        log("started on " + mapName + " at wave " + state.wave);
        review.reset();
        finalWave = state.wave;
        askCommander();
    }

    public void stop(String reason){
        boolean wasActive = active;
        active = false;
        busy = false;
        if(wasActive){
            log("stopped: " + reason);
            if(reason.contains("game over")){
                review.send(this, finalWave);
            }
            roles.clear();
        }
    }

    public void update(){
        if(!state.isGame()){
            if(active) stop("left the game");
            return;
        }

        if(Time.time - lastUpdate < 0.2f) return;
        lastUpdate = Time.time;

        snapshotJson = snapshotWithEvents().toString();

        CoreBuild core = core();
        if(core != null){
            if(lastCoreHealth >= 0f && core.health < lastCoreHealth - 5f){
                event("core_damaged", "core at " + Math.round(core.health) + " hp");
            }
            lastCoreHealth = core.health;
        }

        if(state.wave != lastWave){
            lastWave = state.wave;
            finalWave = state.wave;
        }

        roles.update();

        if(active && !busy && client != null && Time.time - lastPrompt >= Math.max(5, Settings.interval())){
            askCommander();
        }
    }

    private void askCommander(){
        if(!active || busy || client == null) return;

        final String stateText = snapshotJson;
        final int call = ++promptCount;
        final String prompt = Prompt.build(stateText, call, lastMessage);

        busy = true;
        lastPrompt = Time.time;

        executor.submit(() -> {
            long started = Time.millis();
            try{
                if(sessionId == null){
                    sessionId = client.createSession();
                    log("created model session " + sessionId);
                }

                String text = client.prompt(sessionId, prompt);
                CommanderResponse response = CommanderResponse.parse(text);

                lastMessage = response.message;
                lastOrderCount = response.orderCount;
                lastReplyAt = Time.millis();
                lastError = "";
                log("reply #" + call + " in " + (lastReplyAt - started) + "ms: " + oneLine(response.message));

                // Dispatch orders to role system (must happen on main thread).
                if(!response.orders.isEmpty()){
                    final CommanderResponse r = response;
                    arc.Core.app.post(() -> roles.applyOrders(r.orders));
                }
            }catch(Throwable error){
                lastReplyAt = Time.millis();
                errorCount++;
                lastError = error.getClass().getSimpleName() + ": " + safeMessage(error);
                log("error #" + call + ": " + lastError);
            }finally{
                busy = false;
            }
        });
    }

    private Jval snapshotWithEvents(){
        Jval root = StateSnapshot.capture();
        Jval list = Jval.newArray();
        for(String item : events){
            list.add(item);
        }
        root.put("events", list);
        return root;
    }

    private void event(String type, String detail){
        if(events.size() >= 30){
            events.removeFirst();
        }
        events.addLast(Time.millis() + " " + type + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    private CoreBuild core(){
        return player.team().cores().firstOpt();
    }

    private void openLog(){
        try{
            logFile = Core.settings.getDataDirectory().child("ai-commander").child("run.log");
            logFile.parent().mkdirs();
            logFile.writeString("");
        }catch(Throwable error){
            logFile = null;
            Log.warn("[AI Commander] could not open run log: @", safeMessage(error));
        }
    }

    private void log(String message){
        Log.info("[AI Commander] @", message);
        if(logFile != null){
            try{
                logFile.writeString(Time.millis() + " " + message + "\n", true);
            }catch(Throwable ignored){
            }
        }
    }

    private static boolean isFortress(String name){
        return name != null && name.toLowerCase(Locale.ROOT).contains("fortress");
    }

    private static String safeMessage(Throwable error){
        String message = error.getMessage();
        return truncate(message == null ? error.getClass().getSimpleName() : message, 400);
    }

    private static String oneLine(String value){
        if(value == null) return "";
        return truncate(value.replace('\n', ' ').replace('\r', ' '), 400);
    }

    private static String truncate(String value, int max){
        if(value.length() <= max) return value;
        return value.substring(0, max) + "...";
    }

    public boolean isActive(){
        return active;
    }

    public boolean isBusy(){
        return busy;
    }

    public String lastMessage(){
        return lastMessage;
    }

    public String lastError(){
        return lastError;
    }

    public int lastOrderCount(){
        return lastOrderCount;
    }

    public int promptCount(){
        return promptCount;
    }

    public int errorCount(){
        return errorCount;
    }

    public long lastReplyAt(){
        return lastReplyAt;
    }

    public String sessionId(){
        return sessionId;
    }

    public String snapshotJson(){
        return snapshotJson;
    }

    public String mapName(){
        return mapName;
    }

    public UnitRoleManager roles(){
        return roles;
    }
}
