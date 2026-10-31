package aicommander;

import arc.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.mod.*;

public class AICommanderMod extends Mod{
    public static final String NAME = "ai-commander";
    public static final String VERSION = "0.7.0";

    private CommanderController controller;
    private Hud hud;
    private TacticalOverlay overlay;

    public AICommanderMod(){
        Log.info("[AI Commander] constructor, version @", VERSION);

        Events.on(ClientLoadEvent.class, e -> {
            Settings.install();
            controller = new CommanderController();
            hud = new Hud(controller);
            hud.build();
            overlay = new TacticalOverlay(controller);
            Log.info("[AI Commander] client ready");
        });

        Events.on(WorldLoadEvent.class, e -> {
            if(controller != null) controller.onWorldLoad();
            if(hud != null) hud.refreshNow();
        });

        Events.on(GameOverEvent.class, e -> {
            if(controller != null) controller.stop("game over");
        });

        Events.run(Trigger.update, () -> {
            if(controller != null) controller.update();
            if(hud != null) hud.update();
        });

        Events.run(Trigger.draw, () -> {
            if(overlay != null) overlay.draw();
        });
    }

    @Override
    public void init(){
        Log.info("[AI Commander] init");
    }

    @Override
    public void loadContent(){
        Log.info("[AI Commander] loadContent");
    }
}
