package forge.web;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * A tiny WebSocket server (org.java_websocket) that bridges browsers to a single
 * {@link WebGuiGame}. On each engine state change the gui pushes GameView JSON,
 * which we broadcast to every connected browser. Inbound text frames are parsed
 * as {@code {"id":"...","cardId":"..."}} and forwarded to {@link WebGuiGame#submitAction}.
 *
 * <p>Message framing is intentionally minimal (no JSON library): outbound frames
 * are the serialized GameView; inbound frames are shallow-parsed for the two keys.
 */
public class WebMatchServer extends WebSocketServer implements WebGuiGame.Sink {

    private final WebGuiGame gui;
    private final Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile String lastJson = null;

    public WebMatchServer(int port, WebGuiGame gui) {
        this("127.0.0.1", port, gui);
    }

    public WebMatchServer(String host, int port, WebGuiGame gui) {
        super(new InetSocketAddress(host, port));
        this.gui = gui;
        gui.setSink(this);
        setReuseAddr(true);
    }

    // ---- WebGuiGame.Sink: broadcast engine state ----
    @Override
    public void onState(String json) {
        lastJson = json;
        for (WebSocket c : clients) {
            try { c.send(json); } catch (Exception ignored) {}
        }
    }

    // ---- WebSocketServer callbacks ----
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        System.out.println("[ws] client connected: " + conn.getRemoteSocketAddress()
                + " (" + clients.size() + " total)");
        String snapshot = lastJson;
        if (snapshot == null) snapshot = gui.buildJson();
        conn.send(snapshot); // hand the newcomer the current state immediately
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        System.out.println("[ws] client disconnected (" + reason + ")");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String id = extract(message, "id");
        if ("decide".equals(id)) {
            String reqStr = extract(message, "reqId");
            long reqId = reqStr == null ? -1 : safeLong(reqStr);
            int[] picks = extractIntArray(message, "picks");
            String value = extract(message, "value");
            System.out.println("[ws] decide: reqId=" + reqId + " picks=" + Arrays.toString(picks)
                    + " value=" + value);
            if (reqId >= 0) gui.submitDecision(reqId, picks, value);
            return;
        }
        String cardId = extract(message, "cardId");
        System.out.println("[ws] action: id=" + id + " cardId=" + cardId);
        if (id != null) {
            gui.submitAction(id, cardId);
        }
    }

    private static long safeLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; }
    }

    /** Parse a top-level JSON int array like {"picks":[0,2,3]}. Returns empty array if absent. */
    static int[] extractIntArray(String json, String key) {
        if (json == null) return new int[0];
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return new int[0];
        int lb = json.indexOf('[', k);
        int rb = lb < 0 ? -1 : json.indexOf(']', lb);
        if (lb < 0 || rb < 0) return new int[0];
        String body = json.substring(lb + 1, rb).trim();
        if (body.isEmpty()) return new int[0];
        String[] parts = body.split(",");
        java.util.List<Integer> vals = new java.util.ArrayList<>();
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty()) continue;
            try { vals.add(Integer.parseInt(p)); } catch (NumberFormatException ignored) {}
        }
        int[] out = new int[vals.size()];
        for (int i = 0; i < out.length; i++) out[i] = vals.get(i);
        return out;
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[ws] error: " + ex);
    }

    @Override
    public void onStart() {
        System.out.println("[ws] server started on port " + getPort());
    }

    /** Shallow-extract a string value for a top-level JSON key. Good enough for {id,cardId}. */
    static String extract(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            if (end < 0) return null;
            return json.substring(i + 1, end);
        }
        // bare token (number / literal)
        int end = i;
        while (end < json.length() && ",}] \t\r\n".indexOf(json.charAt(end)) < 0) end++;
        String v = json.substring(i, end).trim();
        return v.equals("null") || v.isEmpty() ? null : v;
    }
}
