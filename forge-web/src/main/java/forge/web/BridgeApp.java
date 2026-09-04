package forge.web;

/**
 * Entry point: start a headless human-vs-AI Forge game and expose it over
 * WebSocket for the browser client (mdc-web/battle.html).
 *
 * <p>Run from the fat jar:
 * <pre>
 *   java -jar forge-web-*-shaded.jar                 # 127.0.0.1:8899 (behind nginx)
 *   java -jar forge-web-*-shaded.jar 0.0.0.0 8899    # host port as args
 *   java -Dmdc.ws.host=0.0.0.0 -Dmdc.ws.port=9000 -jar forge-web-*-shaded.jar
 *   MDC_WS_HOST=0.0.0.0 MDC_WS_PORT=9000 java -jar forge-web-*-shaded.jar
 *   # card DB: point at the res parent with -Dmdc.assetsDir=/path/to/forge-gui/
 * </pre>
 *
 * Config precedence for host/port: CLI args &gt; system property &gt; env var &gt; default.
 * Default host is 127.0.0.1 (loopback) so the bridge sits behind a reverse proxy;
 * pass 0.0.0.0 to listen on all interfaces.
 */
public final class BridgeApp {
    private BridgeApp() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        String host = firstNonEmpty(
                args.length > 0 ? args[0] : null,
                System.getProperty("mdc.ws.host"),
                System.getenv("MDC_WS_HOST"),
                "127.0.0.1");
        int port = parsePort(firstNonEmpty(
                args.length > 1 ? args[1] : null,
                System.getProperty("mdc.ws.port"),
                System.getenv("MDC_WS_PORT"),
                "8899"), 8899);

        WebGuiGame gui = new WebGuiGame();
        WebMatchServer server = new WebMatchServer(host, port, gui);
        server.start();
        System.out.println("[bridge] WebSocket bridge listening on ws://" + host + ":" + port);

        // Boot the match; it runs on Forge's game thread and blocks for browser input.
        MatchBootstrap.startHumanVsAi(gui);
        System.out.println("[bridge] match started (human vs AI). Connect a browser to play.");

        // Keep the JVM alive.
        Thread.currentThread().join();
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }

    private static int parsePort(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
