package forge.web;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import forge.deck.Deck;

/**
 * WebSocket server bridging browsers to Forge. <b>Each connection gets its own
 * independent match</b>: its own {@link WebGuiGame} + {@link forge.gamemodes.match.HostedMatch}
 * on its own Forge game thread. A connection's state frames go only to that
 * connection, and its actions/decisions feed only its own match; disconnecting
 * tears its match down. {@code FModel.initialize} stays a single global step.
 *
 * <p>Protocol (inbound JSON, one message per frame):
 * <ul>
 *   <li>{@code {"id":"newgame"[, "deck":"<Arena decklist>"]}} — (re)start this
 *       connection's game; no/blank deck uses the default. Send before playing.</li>
 *   <li>{@code {"id":"pass"|"play"|"select"|"cancel"|"concede", "cardId":"..."}} — an action.
 *       {@code select}/{@code play} on any own hand card / permanent drives the Forge
 *       "click a card" inputs (cast/attack/block); the engine validates legality.</li>
 *   <li>{@code {"id":"selectPlayer","player":"you"|"opp"|"p1"|"p2"}} — select a player
 *       entity (attack target / defender / spell target).</li>
 *   <li>{@code {"id":"nextgame"}} / {@code {"id":"quitmatch"}} — between-games decision
 *       after a game ends (continue to the next game of the match, or quit).</li>
 *   <li>{@code {"id":"sideboard","reqId":N,"main":[fid...]}} — answer to a
 *       {@code {"status":"sideboard",...}} frame: the pool indices to keep in the main deck.</li>
 *   <li>{@code {"id":"decide","reqId":N,"picks":[i...],"value":"..."}} — a dialog answer.</li>
 * </ul>
 * On connect the server pushes one lobby frame ({@code {"status":"lobby",...}}).
 */
public class WebMatchServer extends WebSocketServer {

    /** Per-connection state. */
    private static final class Session {
        final WebSocket conn;
        volatile WebGuiGame gui;
        Session(WebSocket conn) { this.conn = conn; }
    }

    private final Map<WebSocket, Session> sessions = new ConcurrentHashMap<>();

    public WebMatchServer(int port) { this("127.0.0.1", port); }

    public WebMatchServer(String host, int port) {
        super(new InetSocketAddress(host, port));
        setReuseAddr(true);
    }

    // ---- WebSocketServer callbacks ----

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        sessions.put(conn, new Session(conn));
        System.out.println("[ws] open: " + conn.getRemoteSocketAddress()
                + " (" + sessions.size() + " sessions)");
        trySend(conn, lobbyFrame());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Session s = sessions.remove(conn);
        if (s != null && s.gui != null) s.gui.stop(); // concede + free the game thread
        System.out.println("[ws] close: " + conn.getRemoteSocketAddress()
                + " (" + sessions.size() + " sessions)");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Session s = sessions.get(conn);
        if (s == null) return;

        Map<String, Object> msg;
        try {
            Object o = Json.parse(message);
            if (!(o instanceof Map)) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            msg = m;
        } catch (Exception e) {
            System.err.println("[ws] bad message: " + e);
            return;
        }

        String id = str(msg.get("id"));
        if ("newgame".equals(id)) {
            startGame(s, str(msg.get("deck")));
            return;
        }
        WebGuiGame gui = s.gui;
        if (gui == null) return; // no game yet; ignore stray actions

        if ("decide".equals(id)) {
            long reqId = longOf(msg.get("reqId"));
            int[] picks = intArray(msg.get("picks"));
            String value = str(msg.get("value"));
            System.out.println("[ws] decide reqId=" + reqId + " picks=" + Arrays.toString(picks));
            if (reqId >= 0) gui.submitDecision(reqId, picks, value);
        } else if ("selectPlayer".equals(id)) {
            gui.submitSelectPlayer(str(msg.get("player")));
        } else if ("sideboard".equals(id)) {
            gui.submitSideboard(longOf(msg.get("reqId")), intArray(msg.get("main")));
        } else if ("nextgame".equals(id) || "continue".equals(id)) {
            gui.submitNextGame(true);
        } else if ("quitmatch".equals(id)) {
            gui.submitNextGame(false);
        } else if (id != null) {
            gui.submitAction(id, str(msg.get("cardId")));
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[ws] error: " + ex);
    }

    @Override
    public void onStart() {
        System.out.println("[ws] server started on port " + getPort());
    }

    // ---- game lifecycle ----

    private void startGame(Session s, String deckText) {
        // Tear down any previous game for this connection (new game / reconnect).
        if (s.gui != null) s.gui.stop();

        WebGuiGame gui = new WebGuiGame();
        final WebSocket conn = s.conn;
        gui.setSink(json -> trySend(conn, json)); // frames go ONLY to this connection
        s.gui = gui;

        Deck deck = null;
        try {
            deck = DeckParser.parseArena("Web Deck", deckText);
        } catch (Exception e) {
            System.err.println("[ws] deck parse failed: " + e);
        }
        try {
            MatchBootstrap.startHumanVsAi(gui, deck);
            System.out.println("[ws] new game for " + conn.getRemoteSocketAddress()
                    + " deck=" + (deck != null ? deck.getName() : "default"));
        } catch (Exception e) {
            e.printStackTrace();
            trySend(conn, Json.write(mapOf("status", "error", "prompt", "开局失败：" + e)));
        }
    }

    // ---- helpers ----

    private static void trySend(WebSocket conn, String json) {
        try { if (conn.isOpen()) conn.send(json); } catch (Exception ignored) {}
    }

    private static String lobbyFrame() {
        return Json.write(mapOf("status", "lobby", "prompt", "选择牌组开战，或快速对战"));
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static long longOf(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; } }
        return -1;
    }

    private static int[] intArray(Object o) {
        if (!(o instanceof List<?> list)) return new int[0];
        int[] out = new int[list.size()];
        int i = 0;
        for (Object e : list) {
            if (e instanceof Number n) out[i++] = n.intValue();
            else { try { out[i++] = Integer.parseInt(String.valueOf(e).trim()); } catch (Exception ex) { out[i++] = -1; } }
        }
        return out;
    }
}
