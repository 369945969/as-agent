package com.agentscope.ws;

import com.agentscope.agent.ChatRunner;
import com.agentscope.config.Config;
import com.agentscope.store.MessageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * WebSocket streaming channel. It is a thin, resumable view over ChatRunner:
 * the browser sends {@code prompt}/{@code subscribe}; the runner owns generation
 * server-side, so closing the socket (page refresh) never interrupts the turn.
 * Every frame carries its {@code seq} so the client renders in strict arrival
 * order and de-duplicates on resume/replay.
 */
public class WsServer extends WebSocketServer {
    private static final ObjectMapper M = new ObjectMapper();

    private final ChatRunner runner;
    private final MessageStore store;
    private final Config cfg;
    // per-connection listener currently attached to each session's live run (for clean detach)
    private final Map<WebSocket, Map<String, Consumer<ChatRunner.Frame>>> attached = new ConcurrentHashMap<>();

    public WsServer(int port, ChatRunner runner, MessageStore store, Config cfg) {
        super(new InetSocketAddress(port + 1));
        this.runner = runner;
        this.store = store;
        this.cfg = cfg;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String cookie = handshake.getFieldValue("cookie");
        if (cookie == null || !cookie.contains("dsh-auth=" + cfg.token)) {
            send(conn, raw("error", "", 0, 0, "\"unauthorized: token required\""));
            conn.close(1008, "unauthorized"); // 1008 = Policy Violation
            return;
        }
        attached.put(conn, new ConcurrentHashMap<>());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Map<String, Consumer<ChatRunner.Frame>> m = attached.remove(conn);
        if (m != null) m.forEach((sid, l) -> runner.detach(sid, l));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onMessage(WebSocket conn, String message) {
        Map<String, Consumer<ChatRunner.Frame>> mine = attached.get(conn);
        if (mine == null) { send(conn, raw("error", "", 0, 0, "\"no session\"")); return; }
        try {
            Map<String, Object> req = M.readValue(message, Map.class);
            String action = (String) req.getOrDefault("action", "");
            String sid = (String) req.getOrDefault("sessionId", UUID.randomUUID().toString());

            if ("prompt".equals(action)) {
                String msg = (String) req.getOrDefault("message", "");
                String model = (String) req.get("model");
                String uid = (String) req.getOrDefault("userId", "default");
                String sysPrompt = (String) req.get("systemPrompt");
                String skillPrompt = (String) req.get("skillPrompt");
                String agentId = (String) req.get("agentId");

                Consumer<ChatRunner.Frame> listener = f ->
                        send(conn, raw(f.event(), sid, f.turn(), f.seq(), f.dataJson()));
                mine.put(sid, listener);
                try {
                    runner.startAsync(sid, uid, msg, model, sysPrompt, skillPrompt, agentId, listener);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    send(conn, raw("error", sid, 0, 0, q(e.getMessage())));
                    mine.remove(sid);
                } catch (Exception e) {
                    send(conn, raw("error", sid, 0, 0, q(e.getMessage())));
                    mine.remove(sid);
                }

            } else if ("subscribe".equals(action)) {
                int after = intOf(req.get("after"), 0);
                // 1) replay stored events after cursor (in arrival order)
                for (MessageStore.Row r : store.listEvents(sid, after))
                    send(conn, raw(r.eventType, sid, r.turn, r.seq, r.data));
                // 2) attach for live tail if still running
                Consumer<ChatRunner.Frame> listener = f ->
                        send(conn, raw(f.event(), sid, f.turn(), f.seq(), f.dataJson()));
                if (runner.attach(sid, listener)) {
                    Consumer<ChatRunner.Frame> old = mine.put(sid, listener);
                    if (old != null) runner.detach(sid, old);
                }

            } else if ("cancel".equals(action)) {
                runner.cancel(sid);
                send(conn, raw("cancelled", sid, 0, 0, "\"\""));
            } else {
                send(conn, raw("error", sid, 0, 0, q("unknown action: " + action)));
            }
        } catch (Exception e) {
            send(conn, raw("error", "", 0, 0, q(e.getMessage())));
        }
    }

    @Override public void onError(WebSocket conn, Exception ex) {}
    @Override public void onStart() {}

    void send(WebSocket conn, String json) { try { if (conn != null && conn.isOpen()) conn.send(json); } catch (Exception ignored) {} }

    /** Build a frame: data is already a raw JSON string, injected verbatim. */
    private static String raw(String event, String sid, int turn, int seq, String dataJson) {
        return "{\"event\":\"" + event + "\",\"sessionId\":\"" + esc(sid) + "\",\"turn\":" + turn
                + ",\"seq\":" + seq + ",\"data\":" + (dataJson == null ? "null" : dataJson) + "}";
    }
    private static String q(String s) { return s == null ? "null" : ("\"" + esc(s) + "\""); }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    private static int intOf(Object o, int dft) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.parseInt(s); } catch (Exception e) { return dft; } }
        return dft;
    }
}