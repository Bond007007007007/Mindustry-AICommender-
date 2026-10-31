package aicommander;

import arc.util.Log;
import mindustry.game.Team;
import mindustry.gen.*;

import java.util.concurrent.*;

import static mindustry.Vars.*;

/**
 * PostMatchReview sends the run log to the LLM after the match ends.
 *
 * Sends a single review prompt containing:
 *  - final wave reached
 *  - total prompt count and error count
 *  - last LLM message
 *  - brief post-mortem request
 *
 * Runs on a background thread so it doesn't block the game thread.
 * The result is logged to the run log file and printed to the in-game log.
 *
 * Only runs if Settings.enabled() and the controller had an active session.
 */
public class PostMatchReview{
    private static final int TIMEOUT_MS = 30_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-commander-review");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean sent;

    /**
     * Trigger a post-match review.
     * Safe to call from the main thread; the actual HTTP call runs in the background.
     *
     * @param ctrl  the controller (must have a live sessionId to send review)
     * @param finalWave  the last wave number reached
     */
    public void send(CommanderController ctrl, int finalWave){
        if(sent) return;
        if(!Settings.enabled()) return;
        if(ctrl.sessionId() == null) return;

        sent = true;

        final String sessionId = ctrl.sessionId();
        final int prompts = ctrl.promptCount();
        final int errors  = ctrl.errorCount();
        final String lastMsg = ctrl.lastMessage();
        final String baseUrl = Settings.baseUrl();
        final String username = Settings.username();
        final String password = Settings.password();
        final String provider = Settings.provider();
        final String model    = Settings.model();

        executor.submit(() -> {
            try{
                ModelClient client = new ModelClient(baseUrl, username, password, provider, model);
                String prompt = buildPrompt(finalWave, prompts, errors, lastMsg);
                String review = client.prompt(sessionId, prompt);
                Log.info("[AI Commander/Review] Post-match review:\n@", review);
            }catch(Throwable e){
                Log.warn("[AI Commander/Review] review failed: @", e.getMessage());
            }
        });
    }

    public void reset(){
        sent = false;
    }

    private static String buildPrompt(int finalWave, int prompts, int errors, String lastMsg){
        return """
            The match has ended. Here is the final run summary:
              Final wave reached: %d
              Total Commander prompts: %d
              Total errors: %d
              Last Commander message: %s

            As the Commander, write a brief post-mortem (3-5 sentences):
            - What went well?
            - What caused the run to end?
            - What would you do differently next run?

            Return plain text only, no JSON.
            """.formatted(finalWave, prompts, errors,
                lastMsg == null || lastMsg.isEmpty() ? "(none)" : lastMsg);
    }
}
