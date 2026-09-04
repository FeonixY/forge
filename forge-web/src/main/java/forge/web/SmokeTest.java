package forge.web;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import forge.game.GameView;

/**
 * Offline smoke test — proves the bridge end to end WITHOUT a browser.
 *
 * <p>It boots the same headless human-vs-AI match, attaches a Sink that dumps
 * every pushed GameView JSON to {@code forge-web/smoke_gameview.jsonl} (and the
 * first snapshot pretty-ish to {@code forge-web/smoke_first.json} for feeding to
 * battle.html), and runs an auto-pilot that answers every "your turn to act"
 * prompt by passing priority. The AI plays normally, so the JSON stream shows a
 * live, evolving board until the game ends or a time cap is hit.
 *
 * <pre>
 *   mvn -q -pl forge-web -am exec:java -Dexec.mainClass=forge.web.SmokeTest
 * </pre>
 */
public final class SmokeTest {
    private SmokeTest() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        final Path outDir = Paths.get(System.getProperty("mdc.smoke.dir", "forge-web"));
        Files.createDirectories(outDir);
        final Path jsonl = outDir.resolve("smoke_gameview.jsonl");
        final Path first = outDir.resolve("smoke_first.json");

        final WebGuiGame gui = new WebGuiGame();
        final AtomicInteger pushes = new AtomicInteger();

        try (BufferedWriter w = new BufferedWriter(new FileWriter(jsonl.toFile()))) {
            gui.setSink(json -> {
                try {
                    w.write(json);
                    w.newLine();
                    w.flush();
                } catch (Exception ignored) {}
                int n = pushes.incrementAndGet();
                if (n == 1) {
                    try { Files.writeString(first, json); } catch (Exception ignored) {}
                }
                if (n % 20 == 0) System.out.println("[smoke] pushes=" + n);
            });

            MatchBootstrap.startHumanVsAi(gui);
            System.out.println("[smoke] match started; auto-passing priority...");

            long deadline = System.currentTimeMillis() + 90_000; // 90s wall-clock cap
            int maxPushes = Integer.getInteger("mdc.smoke.maxPushes", 600); // keep artifact small
            int lastEpoch = -1;
            int idleTicks = 0;
            while (System.currentTimeMillis() < deadline) {
                if (isGameOver(gui)) {
                    System.out.println("[smoke] game over detected.");
                    break;
                }
                if (pushes.get() >= maxPushes) {
                    System.out.println("[smoke] reached maxPushes=" + maxPushes + " (still live); stopping.");
                    break;
                }
                // A blocking dialog decision parks the game thread — answer with the
                // caller's default so the headless game keeps flowing to game-over.
                if (gui.hasPendingDecision()) {
                    gui.answerPendingDefault();
                    Thread.sleep(20);
                    continue;
                }
                int epoch = gui.getInputEpoch();
                if (epoch != lastEpoch) {
                    lastEpoch = epoch;
                    idleTicks = 0;
                    String sel = gui.firstSelectableCardId();
                    if (sel != null) {
                        // A selection is required (e.g. cleanup discard, targeting) or a
                        // playable card is offered — pick the first one.
                        gui.submitAction("select", sel);
                    } else if (gui.isOkEnabled()) {
                        gui.submitAction("pass", null); // like the human clicking OK / pass
                    }
                } else {
                    idleTicks++;
                }
                Thread.sleep(50);
            }

            System.out.println("[smoke] DONE. total pushes=" + pushes.get()
                    + ", output=" + jsonl.toAbsolutePath());
            System.out.println("[smoke] first snapshot=" + first.toAbsolutePath()
                    + " (rename to mock_gameview.json to view in battle.html)");
        }

        // Match runs on a daemon-ish game thread; exit explicitly.
        System.exit(0);
    }

    private static boolean isGameOver(WebGuiGame gui) {
        GameView gv = gui.getGameView();
        return gv != null && gv.isGameOver();
    }
}
