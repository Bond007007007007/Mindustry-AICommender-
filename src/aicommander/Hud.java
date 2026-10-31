package aicommander;

import arc.*;
import arc.scene.*;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

/**
 * In-game HUD overlay for AI Commander.
 *
 * Shows a collapsible panel in the top-right corner with:
 *  - Status (ACTIVE / THINKING / STOPPED)
 *  - Map, Wave, Stats, Orders, Reply age
 *  - Role summary (build queue, defend/scout waypoints)
 *  - Last message (truncated)
 *  - Last error (if any)
 *  - Start / Stop buttons
 *  - Collapse toggle
 */
public class Hud{
    private static final float UPDATE_INTERVAL = 30f;
    private static final int MSG_MAX_CHARS = 80;
    private static final int ERR_MAX_CHARS = 60;

    private final CommanderController ctrl;

    private Table root;
    private Table bodyTable;
    private Label statusLabel;
    private Label mapLabel;
    private Label waveLabel;
    private Label countsLabel;
    private Label ordersLabel;
    private Label replyLabel;
    private Label rolesLabel;
    private Label msgLabel;
    private Label errLabel;
    private TextButton collapseBtn;

    private boolean collapsed = false;
    private float lastRefresh = -UPDATE_INTERVAL;

    public Hud(CommanderController ctrl){
        this.ctrl = ctrl;
    }

    /** Call once after ClientLoadEvent. */
    public void build(){
        root = new Table(Styles.black6);
        root.touchable = Touchable.enabled;

        // ── Header ──────────────────────────────────────────────────────────
        root.table(Styles.black8, h -> {
            h.add("[accent]AI Commander[]").left().padLeft(6f).padRight(8f).growX();
            collapseBtn = h.button("[accent]▲[]", Styles.cleart, () -> {
                collapsed = !collapsed;
                bodyTable.visible = !collapsed;
                collapseBtn.setText(collapsed ? "[accent]▼[]" : "[accent]▲[]");
                root.pack();
                repositionTopRight();
            }).size(32f).padRight(4f).get();
        }).growX().row();

        // ── Body ─────────────────────────────────────────────────────────────
        root.table(Styles.black6, body -> {
            bodyTable = body;
            body.defaults().left().padLeft(6f).padRight(6f);

            statusLabel = addRow(body, "Status", "");
            mapLabel    = addRow(body, "Map",    "");
            waveLabel   = addRow(body, "Wave",   "");
            countsLabel = addRow(body, "Stats",  "");
            ordersLabel = addRow(body, "Orders", "");
            replyLabel  = addRow(body, "Reply",  "");
            rolesLabel  = addRow(body, "Roles",  "");

            body.row();
            msgLabel = body.add("").left().padLeft(6f).padRight(6f).maxWidth(220f).get();
            msgLabel.setWrap(true);

            body.row();
            errLabel = body.add("").left().padLeft(6f).padRight(6f).maxWidth(220f).get();
            errLabel.setWrap(true);

            // ── Buttons ──────────────────────────────────────────────────────
            body.row();
            body.table(btn -> {
                btn.button("[lime]Start[]", Styles.cleart, () -> {
                    if(state.isGame()) ctrl.start();
                }).pad(2f).size(50f, 28f);
                btn.button("[scarlet]Stop[]", Styles.cleart, () -> {
                    ctrl.stop("manual");
                }).pad(2f).size(50f, 28f);
                btn.button("[accent]Prompt[]", Styles.cleart, () -> {
                    if(state.isGame() && ctrl.isActive()){
                        ctrl.promptNow("manual_hud");
                    }
                }).pad(2f).size(60f, 28f);
                btn.button(Settings.overlayDebug() ? "[stat]Map:ON[]" : "[gray]Map:OFF[]", Styles.cleart, () -> {
                    boolean next = !Settings.overlayDebug();
                    Settings.setOverlayDebug(next);
                    refreshNow();
                }).pad(2f).size(62f, 28f);
            }).pad(2f).row();
        }).growX().row();

        root.pack();
        ui.hudGroup.addChild(root);
        repositionTopRight();
    }

    /** Called every tick from Trigger.update (main thread). */
    public void update(){
        if(root == null) return;
        if(Time.time - lastRefresh < UPDATE_INTERVAL) return;
        refreshNow();
    }

    /** Force-refresh labels (e.g. after world load). */
    public void refreshNow(){
        if(root == null) return;
        lastRefresh = Time.time;
        populate();
        repositionTopRight();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void populate(){
        // Status
        if(!ctrl.isActive()){
            statusLabel.setText("[gray]STOPPED[]");
        } else if(ctrl.isBusy()){
            statusLabel.setText("[yellow]THINKING[]");
        } else {
            statusLabel.setText("[lime]ACTIVE[]");
        }

        String map = ctrl.mapName();
        mapLabel.setText(map == null || map.isEmpty() ? "--" : map);
        waveLabel.setText(state.isGame() ? String.valueOf(state.wave) : "--");
        countsLabel.setText("p:" + ctrl.promptCount() + " e:" + ctrl.errorCount());
        ordersLabel.setText(String.valueOf(ctrl.lastOrderCount()));

        long replyAt = ctrl.lastReplyAt();
        if(replyAt <= 0L){
            replyLabel.setText("--");
        } else {
            replyLabel.setText((Time.millis() - replyAt) / 1000L + "s ago");
        }

        if(ctrl.isActive()){
            UnitRoleManager roles = ctrl.roles();
            int combatUnits = roles.defender().squadManager().totalCombatUnits();
            rolesLabel.setText("bld:" + roles.builder().queueSize()
                + " sqd:" + combatUnits
                + " def:" + roles.defender().waypointCount()
                + " sct:" + roles.scout().waypointCount()
                + (roles.defender().isUrgentThreat() ? " [scarlet]!ALERT![]" : ""));
        } else {
            rolesLabel.setText("--");
        }

        String msg = ctrl.lastMessage();
        if(msg == null || msg.isEmpty()){
            msgLabel.setText("");
        } else {
            String oneLine = msg.replace('\n', ' ').replace('\r', ' ');
            msgLabel.setText("[lightgray]" + truncate(oneLine, MSG_MAX_CHARS) + "[]");
        }

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
        float sh = Core.graphics.getHeight();
        root.setPosition(sw - root.getWidth() - 8f, sh - root.getHeight() - 8f);
    }

    private static Label addRow(Table t, String key, String value){
        t.row();
        t.add("[gray]" + key + ":[] ").left();
        Label val = t.add(value).left().get();
        return val;
    }

    private static String truncate(String s, int max){
        if(s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
