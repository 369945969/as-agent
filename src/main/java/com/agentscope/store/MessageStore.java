package com.agentscope.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQLite message store. Persists the RAW agent events (data column = the exact
 * JSON the backend emits, never rewritten) so the frontend can replay them and
 * render in exactly the backend arrival order.
 *
 * <p>Schema lives in {@code db/sqlite/schema.sql} (single source of truth,
 * packaged to classpath {@code db/} by maven-resources). Tables:
 * <ul>
 *   <li>{@code session} — meta row per chat, keyed by session_id</li>
 *   <li>{@code event}   — one row per raw event; (session_id, seq) unique;
 *       id is the global arrival order, seq is per-session cursor for incremental pulls</li>
 * </ul>
 */
public class MessageStore implements AutoCloseable {
    private static final String SCHEMA_RESOURCE = "db/sqlite/schema.sql";
    private static final String SCHEMA_DEV_FILE = "db/sqlite/schema.sql"; // relative to CWD when running from source

    private final Connection conn;

    public MessageStore(Path dbFile) throws SQLException, IOException {
        Path parent = dbFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        this.conn.setAutoCommit(true);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA foreign_keys=ON");
        }
        runSchema();
    }

    /** Execute the external schema script (statements split on ';'). */
    private void runSchema() throws IOException, SQLException {
        String sql = readSchema();
        for (String stmt : sql.split(";")) {
            String s = stripComments(stmt).trim();
            if (!s.isEmpty()) {
                try (Statement st = conn.createStatement()) { st.execute(s); }
            }
        }
    }

    private String readSchema() throws IOException {
        // 1) classpath (fat jar / target/classes)
        try (InputStream is = MessageStore.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 2) dev fallback: source tree relative to CWD
        Path p = Path.of(SCHEMA_DEV_FILE);
        if (Files.exists(p)) return Files.readString(p, StandardCharsets.UTF_8);
        throw new IOException("schema not found: classpath:" + SCHEMA_RESOURCE + " or file " + p.toAbsolutePath());
    }

    private static String stripComments(String block) {
        StringBuilder sb = new StringBuilder();
        for (String line : block.split("\n")) {
            String t = line.trim();
            if (t.startsWith("--") || t.isEmpty()) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ---------- session ----------

    /** Insert or touch the session row. Title set only when new; status/model refreshed on each turn. */
    public synchronized void upsertSession(String sessionId, String userId, String model, String title, String status) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO session(session_id,user_id,model,title,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?) " +
                "ON CONFLICT(session_id) DO UPDATE SET model=excluded.model, status=excluded.status, updated_at=excluded.updated_at")) {
            ps.setString(1, sessionId);
            ps.setString(2, userId == null ? "" : userId);
            ps.setString(3, model == null ? "" : model);
            ps.setString(4, title == null ? "" : title);
            ps.setString(5, status);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    public synchronized void setStatus(String sessionId, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE session SET status=?, updated_at=? WHERE session_id=?")) {
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, sessionId);
            ps.executeUpdate();
        }
    }

    public synchronized String getStatus(String sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM session WHERE session_id=?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 暴露底层 Connection（供 AgentStore 复用同一 SQLite 连接）。 */
    public Connection getConnection() { return conn; }

    /** Sessions of a user, newest first. */
    public synchronized List<Map<String, Object>> listSessions(String userId) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean filtered = userId != null && !userId.isBlank();
        String sql = "SELECT session_id, user_id, model, title, status, created_at, updated_at, " +
                     "(SELECT MAX(seq) FROM event e WHERE e.session_id=s.session_id) AS last_seq " +
                     "FROM session s " + (filtered ? "WHERE user_id=? " : "") + "ORDER BY updated_at DESC LIMIT 200";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filtered) ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(Map.of(
                            "sessionId", nz(rs.getString("session_id")),
                            "model", nz(rs.getString("model")),
                            "title", nz(rs.getString("title")),
                            "status", nz(rs.getString("status")),
                            "createdAt", rs.getLong("created_at"),
                            "updatedAt", rs.getLong("updated_at"),
                            "lastSeq", rs.getLong("last_seq")));
                }
            }
        }
        return out;
    }

    // ---------- event ----------

    public static final class Row {
        public final long id;
        public final int turn;
        public final int seq;
        public final String eventType;
        public final String data;   // raw JSON, untouched
        public final long createdAt;
        public Row(long id, int turn, int seq, String eventType, String data, long createdAt) {
            this.id = id; this.turn = turn; this.seq = seq;
            this.eventType = eventType; this.data = data; this.createdAt = createdAt;
        }
    }

    /**
     * Append one raw event for a session/turn. seq is allocated per-session as
     * MAX(seq)+1 under the same lock, so DB order == arrival order.
     * @return the assigned seq (client cursor).
     */
    public synchronized int append(String sessionId, int turn, String eventType, String rawJson) throws SQLException {
        int seq;
        try (PreparedStatement q = conn.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 FROM event WHERE session_id=?")) {
            q.setString(1, sessionId);
            try (ResultSet rs = q.executeQuery()) { seq = rs.next() ? rs.getInt(1) : 1; }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO event(session_id,turn,seq,event_type,data,created_at) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, sessionId);
            ps.setInt(2, turn);
            ps.setInt(3, seq);
            ps.setString(4, eventType);
            ps.setString(5, rawJson);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
        return seq;
    }

    /** All raw events of a session after the given seq cursor, in arrival order. */
    public synchronized List<Row> listEvents(String sessionId, int afterSeq) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, turn, seq, event_type, data, created_at FROM event WHERE session_id=? AND seq>? ORDER BY id ASC")) {
            ps.setString(1, sessionId);
            ps.setInt(2, afterSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new Row(rs.getLong("id"), rs.getInt("turn"), rs.getInt("seq"),
                            rs.getString("event_type"), rs.getString("data"), rs.getLong("created_at")));
            }
        }
        return out;
    }

    /** Next turn number (1 + max turn having a user_message; 1 if fresh session). */
    public synchronized int nextTurn(String sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(MAX(turn),0)+1 FROM event WHERE session_id=? AND event_type='user_message'")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { int t = rs.getInt(1); return t == 0 ? 1 : t; }
            }
        }
        return 1;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    @Override
    public synchronized void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}