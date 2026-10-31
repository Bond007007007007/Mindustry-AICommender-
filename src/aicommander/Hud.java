package aicommander;

import arc.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

/**
 * Minimal HUD overlay for AI Commander.
 *
 * Layout (top-right corner, always on top of hudGroup):
 *
 *   ┌─────────────────────────────┬───┐
 *   │ [AI Commander]         [▲/▼]│
 *   ├─────────────────────────────┤   │
 *   │ Status : ACTIVE / BUSY / -- │   │  (collapsed: only header visible)
 *   │ Map    : ...                │   │
 *   │ Wave   : 12                 │   │
 *   │ Prompts: 5  Errors: 0       │   │
 *   │ Orders : 3                  │   │
 *   │ Reply  : 4s ago             │   │
 *   │ Msg    : (truncated line)   │   │
 *   │ Err    : (if any)           │   │
 *   ├─────────────────────────────┤   │
 *   │  [Start]  /  [Stop]         │   │
 *   └─────────────────────────────┴───┘
 *
 * All UI work happens on the main thread via Core.app.post().
 * update() is called from Trigger.update (main thread), so refreshes are safe there.
 */
public class Hud{
    private static final float UPDATE_INTERVAL = 30f; // ticks (~0.5s at 60 fps)
    private static final int MSG_MAX_CHARS = 80;
    private static final int ERR_MAX_CHARS = 60;

    private final CommanderController ctrl;

    // Scene nodes
    private Table root;
    private Label statusLabel;
    private Label mapLabel;
    private Label waveLabel;
    private Label countsLabel;
    private Label ordersLabel;
    private Label replyLabel;
    private Label msgLabel;
    private Label errLabel;
    private Table bodyTable;
    private boolean collapsed = false;

    private float lastRefresh = -UPDATE_INTERVAL; // force first refresh

    public Hud(CommanderController ctrl){
        this.ctrl = ctrl;
    }

    /** Called once after ClientLoadEvent. Builds the scene graph and adds it to hudGroup. */
    public void build(){
        root = new Table(Styles.black3);
        root.setFillParent(false);
        root.touchable(arc.scene.Touchable.enabled);
        root.visible(true);

        // ── Header row ──────────────────────────────────────────────────────
        Table header = root.row().get();
        root.table(Styles.black3, h -> {
            h.label(() -> "[accent]AI Commander[]").left().padLeft(6f).padRight(8f).growX();
            h.button(this::toggleLabel, Styles.clearTransit, 28f, () -> {
                collapsed = !collapsed;
                bodyTable.visible(!collapsed);
            }).size(28f).padRight(4f);
        }).growX().pad(0f).row();

        // ── Body ─────────────────────────────────────────────────────────────
        root.table(Styles.black3, body -> {
            bodyTable = body;

            body.defaults().left().padLeft(6f).padRight(6f);

            statusLabel = addRow(body, "Status", "");
            mapLabel    = addRow(body, "Map",    "");
            waveLabel   = addRow(body, "Wave",   "");
            countsLabel = addRow(body, "Stats",  "");
            ordersLabel = addRow(body, "Orders", "");
            replyLabel  = addRow(body, "Reply",  "");

            body.row();
            msgLabel = body.add("").left().padLeft(6f).padRight(6f).maxWidth(220f).wrap(true).get();

            body.row();
            errLabel = body.add("").left().padLeft(6f).padRight(6f).maxWidth(220f).wrap(true).get();

            // ── Buttons ──────────────────────────────────────────────────────
            body.row();
            body.table(btn -> {
                btn.button("[lime]Start[]", Styles.clearTransit, () -> {
                    if(state.isGame()) ctrl.start();
                }).pad(4f).size(80f, 32f);
                btn.button("[scarlet]Stop[]", Styles.clearTransit, () -> {
                    ctrl.stop("manual");
                }).pad(4f).size(80f, 32f);
            }).pad(4f).row();

        }).growX().row();

        // ── Position: top-right ──────────────────────────────────────────────
        root.pack();
        ui.hudGroup.addChild(root);
        repositionTopRight();
    }

    /** Called from Trigger.update on the main thread. */
    public void update(){
        if(root == null) return;
        if(Time.time - lastRefresh < UPDATE_INTERVAL) return;
        refreshNow();
    }

    /** Force an immediate refresh (e.g. after world load). */
    public void refreshNow(){
        if(root == null) return;
        lastRefresh = Time.time;
        populate();
        repositionTopRight();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void populate(){
        // Status
        String status;
        if(!ctrl.isActive()){
            status = "[gray]STOPPED[]";
        } else if(ctrl.isBusy()){
            status = "[yellow]THINKING[]";
        } else {
            status = "[lime]ACTIVE[]";
        }
        statusLabel.setText(status);

        // Map / wave
        String map = ctrl.mapName();
        mapLabel.setText(map == null || map.isEmpty() ? "--" : map);
        waveLabel.setText(state.isGame() ? String.valueOf(state.wave) : "--");

        // Counters
        countsLabel.setText("prompts:" + ctrl.promptCount() + "  err:" + ctrl.errorCount());
        ordersLabel.setText(String.valueOf(ctrl.lastOrderCount()));

        // Reply age
        long replyAt = ctrl.lastReplyAt();
        if(replyAt <= 0L){
            replyLabel.setText("--");
        } else {
            long ago = (Time.millis() - replyAt) / 1000L;
            replyLabel.setText(ago + "s ago");
        }

        // Last message (single line, truncated)
        String msg = ctrl.lastMessage();
        if(msg == null || msg.isEmpty()){
            msgLabel.setText("");
        } else {
            String oneLine = msg.replace('\n', ' ').replace('\r', ' ');
            msgLabel.setText("[lightgray]" + truncate(oneLine, MSG_MAX_CHARS) + "[]");
        }

        // Last error
        String err = ctrl.lastError();
        if(err == null || err.isEmpty()){
            errLabel.setText("");
        } else {
            errLabel.setText("[scarlet]" + truncate(err, ERR_MAX_CHARS) + "[]");
        }

        root.pack();
    }

    private void repositionTopRight(){
        if(root == null) return;
        float sw = Core.graphics.getWidth();
        root.setPosition(sw - root.getWidth() - 8f,
                         Core.graphics.getHeight() - root.getHeight() - 8f);
    }

    /** Text shown on the collapse toggle button. */
    private String toggleLabel(){
        return collapsed ? "[accent]▼[]" : "[accent]▲[]";
    }

    /**
     * Adds a key-value row to the given table and returns the value Label
     * so callers can update it later.
     */
    private static Label addRow(Table t, String key, String initialValue){
        t.row();
        t.add("[gray]" + key + ":[] ").left();
        Label val = t.add(initialValue).left().get();
        return val;
    }

    private static String truncate(String s, int max){
        if(s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
