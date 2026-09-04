package forge.web;

/**
 * Entry point: start a headless human-vs-AI Forge game and expose it over
 * WebSocket for the browser client (mdc-web/battle.html).
 *
 * <pre>
 *   mvn -q -pl forge-web -am exec:java -Dexec.mainClass=forge.web.BridgeApp
 *   # optional: -Dexec.args="8899"   (port, default 8899)
 * </pre>
 *
 * Then point battle.html at ws://localhost:8899 and it will render the pushed
 * GameView JSON; clicking an action sends {"id":...,"cardId":...} back.
 */
public final class BridgeApp {
    private BridgeApp() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8899;

        WebGuiGame gui = new WebGuiGame();
        WebMatchServer server = new WebMatchServer(port, gui);
        server.start();
        System.out.println("[bridge] WebSocket bridge listening on ws://localhost:" + port);

        // Boot the match; it runs on Forge's game thread and blocks for browser input.
        MatchBootstrap.startHumanVsAi(gui);
        System.out.println("[bridge] match started (human vs AI). Connect a browser to play.");

        // Keep the JVM alive.
        Thread.currentThread().join();
    }
}
