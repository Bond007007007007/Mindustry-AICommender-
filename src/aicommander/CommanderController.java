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
    private volatile int consecutiveErrors;
    private volatile int generation;
    private volatile long lastReplyAt;
    private volatile long lastLatencyMs = -1L;
    private volatile String snapshotJson = "{}";
    private volatile String sessionId;

    private final UnitRoleManager roles = new UnitRoleManager();
    private final PostMatchReview review = new PostMatchReview();
    private final FallbackAdvisor fallback = new FallbackAdvisor();
    private final ResearchManager research = new ResearchManager();

    private ModelClient client;
    private Fi logFile;
    private String mapName = "";
    private int finalWave;
    private float lastUpdate;
    private float lastPrompt;
    private float lastCoreHealth = -1f;
    private int lastWave = -1;
    private long matchStartMillis;

    private static final float PREP_WINDOW = 60f * 20f; // 20 seconds before next wave
    private static final float UPDATE_INTERVAL = 12f;   // ~0.2s, avoids per-frame snapshots

    private volatile Doctrine currentDoctrine = Doctrine.defaultDoctrine();
    private volatile int evaluatedWave = -1;

    public CommanderController(){
        Events.on(WaveEvent.class, e -> {
            event("wave", "wave " + state.wave);
            // Fallback trigger: if the prep-window evaluation did not run, plan now.
            if(active && !busy && evaluatedWave < state.wave){
                log("Wave " + state.wave + " reached: requesting Commander doctrine update");
                askCommander(state.wave);
            }
        });
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

        if(Settings.autoStart()){
            start();
        }
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

        generation++;
        active = true;
        busy = false;
        lastMessage = "";
        lastError = "";
        lastOrderCount = 0;
        promptCount = 0;
        errorCount = 0;
        consecutiveErrors = 0;
        lastReplyAt = 0;
        lastLatencyMs = -1L;
        lastPrompt = 0f;
        lastUpdate = 0f;
        matchStartMillis = Time.millis();
        sessionId = null;
        mapName = state.map.name();
        events.clear();

        CoreBuild core = core();
        lastCoreHealth = core == null ? -1f : core.health;
        lastWave = state.wave;

        client = new ModelClient(Settings.baseUrl(), Settings.username(), Settings.password(),
            Settings.provider(), Settings.model());

        currentDoctrine = Doctrine.defaultDoctrine();
        evaluatedWave = -1;
        StateSnapshot.reset();
        roles.applyDoctrine(currentDoctrine);
        openLog();
        snapshotJson = snapshotWithEvents().toString();
        log("started on " + mapName + " at wave " + state.wave);
        review.reset();
        finalWave = state.wave;
        askCommander();
    }

    public void stop(String reason){
        generation++;
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

        if(Time.time - lastUpdate < UPDATE_INTERVAL) return;
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
        fallback.update(this, roles);
        research.update();

        if(!active || busy || client == null) return;

        // Primary cadence: evaluate during the inter-wave preparation countdown so the
        // new doctrine is ready when the next wave spawns. state.wavetime is in ticks.
        boolean inPrepWindow = state.wavetime <= PREP_WINDOW;
        int targetWave = inPrepWindow ? state.wave + 1 : state.wave;
        int backoff = 1 << Math.min(4, consecutiveErrors);
        float retryCooldown = Math.max(60f * 45f, 60f * Math.max(5, Settings.interval()) * backoff);
        // Only retry while the doctrine is actually missing, and only during the
        // prep window or when the current wave has no doctrine at all. This avoids
        // mid-wave prompt storms after a failure.
        boolean needsDoctrine = evaluatedWave < targetWave
            && (inPrepWindow || evaluatedWave < state.wave || lastPrompt <= 0f);
        if(needsDoctrine && Time.time - lastPrompt >= retryCooldown){
            askCommander(targetWave);
        }
    }

    public void promptNow(String reason){
        if(!active || busy || client == null) return;
        log("manual prompt triggered: " + reason);
        askCommander(state.wave);
    }

    private void askCommander(){
        askCommander(state.wave);
    }

    private void askCommander(final int targetWave){
        if(!active || busy || client == null) return;

        final String stateText = snapshotJson;
        final int call = ++promptCount;
        final int gen = generation;
        final String prompt = Prompt.build(stateText, call, lastMessage);

        busy = true;
        lastPrompt = Time.time;

        try{
            executor.submit(() -> {
            long started = Time.millis();
            try{
                if(sessionId == null){
                    sessionId = client.createSession();
                    log("created model session " + sessionId);
                }

                String text = client.prompt(sessionId, prompt);
                if(gen != generation) return;
                CommanderResponse response = CommanderResponse.parse(text);

                lastMessage = response.message;
                lastOrderCount = response.orderCount;
                lastReplyAt = Time.millis();
                lastLatencyMs = lastReplyAt - started;
                lastError = "";
                consecutiveErrors = 0;
                evaluatedWave = targetWave;
                currentDoctrine = response.doctrine;
                log("reply #" + call + " (wave " + targetWave + ") in " + (lastReplyAt - started) + "ms: " + oneLine(response.message));

                // Dispatch doctrine and orders to role system (must happen on main thread).
                final CommanderResponse r = response;
                arc.Core.app.post(() -> {
                    if(gen != generation || !active) return;
                    roles.applyDoctrine(r.doctrine);
                    if(!r.orders.isEmpty()){
                        roles.applyOrders(r.orders);
                    }
                });
            }catch(Throwable error){
                if(gen != generation) return;
                lastReplyAt = Time.millis();
                errorCount++;
                consecutiveErrors++;
                lastError = error.getClass().getSimpleName() + ": " + safeMessage(error);
                log("error #" + call + " (retaining last-known doctrine): " + lastError);
                // Invalidate session if session was rejected or server crashed
                if(lastError.contains("404") || lastError.contains("Connection refused")
                    || lastError.contains("400") || lastError.contains("401") || lastError.contains("403")){
                    sessionId = null;
                }
            }finally{
                if(gen == generation) busy = false;
            }
            });
        }catch(Throwable scheduleError){
            busy = false;
            lastError = "scheduler: " + safeMessage(scheduleError);
            log("error scheduling prompt: " + lastError);
        }
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
        float at = Math.max(0f, (Time.millis() - matchStartMillis) / 1000f);
        events.addLast(String.format(Locale.ROOT, "%.1fs %s%s", at, type,
            detail == null || detail.isEmpty() ? "" : " " + detail));
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

    public long lastLatencyMs(){
        return lastLatencyMs;
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

    public Doctrine currentDoctrine(){
        return currentDoctrine;
    }

    public UnitRoleManager roles(){
        return roles;
    }
}
