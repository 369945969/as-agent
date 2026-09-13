package com.agentscope.store;

import com.agentscope.agent.AgentConfig;
import com.agentscope.agent.DataApiConfig;
import com.agentscope.agent.McpConfig;
import com.agentscope.agent.SkillConfig;

import java.sql.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 智能体/Skill/数据服务API/MCP 的 SQLite CRUD。
 *
 * <p>复用 MessageStore 的同一个 SQLite Connection（同库不同表）。
 * 所有方法 synchronized（SQLite 单连接，与 MessageStore 一致）。
 */
public class AgentStore implements AutoCloseable {

    private final Connection conn;

    public AgentStore(Connection conn) throws SQLException {
        this.conn = conn;
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        runSchema();
    }

    /** 从外部 schema.sql 文件建表（DDL 幂等）。 */
    private void runSchema() throws SQLException {
        String sql = null;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("db/sqlite/schema.sql")) {
            if (is != null) sql = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        if (sql == null) {
            try { sql = Files.readString(Path.of("db/sqlite/schema.sql")); }
            catch (Exception e) { return; }
        }
        for (String stmt : sql.split(";")) {
            String s = stmt.trim();
            if (!s.isEmpty()) try (Statement st = conn.createStatement()) { st.execute(s); }
            catch (SQLException ignored) {}
        }
    }

    // ==================== Agent ====================

    public synchronized String createAgent(String title, String description, String systemPrompt,
                                            String modelProfileId, String icon) throws SQLException {
        String id = "agent-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO agent(id,title,description,system_prompt,model_profile_id,icon,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, title); ps.setString(3, description == null ? "" : description);
            ps.setString(4, systemPrompt == null ? "" : systemPrompt);
            ps.setString(5, modelProfileId == null ? "" : modelProfileId);
            ps.setString(6, icon == null ? "🤖" : icon);
            ps.setLong(7, now); ps.setLong(8, now);
            ps.executeUpdate();
        }
        return id;
    }

    public synchronized void updateAgent(String id, String title, String description,
                                         String systemPrompt, String modelProfileId, String icon) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE agent SET title=?,description=?,system_prompt=?,model_profile_id=?,icon=?,updated_at=? WHERE id=?")) {
            ps.setString(1, title); ps.setString(2, description == null ? "" : description);
            ps.setString(3, systemPrompt == null ? "" : systemPrompt);
            ps.setString(4, modelProfileId == null ? "" : modelProfileId);
            ps.setString(5, icon == null ? "🤖" : icon);
            ps.setLong(6, System.currentTimeMillis()); ps.setString(7, id);
            ps.executeUpdate();
        }
    }

    public synchronized void deleteAgent(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM agent WHERE id=?")) {
            ps.setString(1, id); ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM agent_binding WHERE agent_id=?")) {
            ps.setString(1, id); ps.executeUpdate();
        }
    }

    public synchronized List<Map<String, Object>> listAgents() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM agent ORDER BY updated_at DESC")) {
            while (rs.next()) out.add(agentRow(rs));
        }
        return out;
    }

    public synchronized Map<String, Object> getAgentRow(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM agent WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? agentRow(rs) : null; }
        }
    }

    /** 获取完整的 AgentConfig（含绑定关系）。 */
    public synchronized AgentConfig getAgentConfig(String agentId) throws SQLException {
        Map<String, Object> row = getAgentRow(agentId);
        if (row == null) return null;

        AgentConfig.Builder b = AgentConfig.builder()
                .id(agentId)
                .title((String) row.get("title"))
                .description((String) row.get("description"))
                .systemPrompt((String) row.get("system_prompt"))
                .modelProfileId((String) row.get("model_profile_id"))
                .icon((String) row.get("icon"));

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT binding_type, target_id FROM agent_binding WHERE agent_id=? ORDER BY binding_type, target_id")) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String bt = rs.getString("binding_type");
                    String tid = rs.getString("target_id");
                    switch (bt) {
                        case "skill" -> b.addSkill(tid);
                        case "api" -> b.addApi(tid);
                        case "mcp" -> b.addMcp(tid);
                    }
                }
            }
        }
        return b.build();
    }

    /** 全量更新智能体绑定（先删后插）。 */
    public synchronized void updateBindings(String agentId, List<String> skillIds, List<String> apiIds, List<String> mcpIds) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM agent_binding WHERE agent_id=?")) {
            del.setString(1, agentId); del.executeUpdate();
        }
        String insert = "INSERT INTO agent_binding(agent_id,binding_type,target_id) VALUES(?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            if (skillIds != null) for (String sid : skillIds) { ps.setString(1, agentId); ps.setString(2, "skill"); ps.setString(3, sid); ps.addBatch(); }
            if (apiIds != null) for (String aid : apiIds) { ps.setString(1, agentId); ps.setString(2, "api"); ps.setString(3, aid); ps.addBatch(); }
            if (mcpIds != null) for (String mid : mcpIds) { ps.setString(1, agentId); ps.setString(2, "mcp"); ps.setString(3, mid); ps.addBatch(); }
            ps.executeBatch();
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE agent SET updated_at=? WHERE id=?")) {
            ps.setLong(1, System.currentTimeMillis()); ps.setString(2, agentId); ps.executeUpdate();
        }
    }

    private Map<String, Object> agentRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("title", rs.getString("title"));
        m.put("description", rs.getString("description"));
        m.put("systemPrompt", rs.getString("system_prompt"));
        m.put("modelProfileId", rs.getString("model_profile_id"));
        m.put("icon", rs.getString("icon"));
        m.put("createdAt", rs.getLong("created_at"));
        m.put("updatedAt", rs.getLong("updated_at"));
        return m;
    }

    // ==================== Skill ====================

    public synchronized String createSkill(String name, String description, String source,
                                           String filePath, String content, String semanticBinding) throws SQLException {
        String id = "skill-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO skill(id,name,description,source,file_path,content,semantic_binding,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, name); ps.setString(3, description == null ? "" : description);
            ps.setString(4, source == null ? "manual" : source);
            ps.setString(5, filePath); ps.setString(6, content == null ? "" : content);
            ps.setString(7, semanticBinding == null ? "" : semanticBinding);
            ps.setString(8, "active"); ps.setLong(9, now); ps.setLong(10, now);
            ps.executeUpdate();
        }
        return id;
    }

    public synchronized void updateSkill(String id, String name, String description,
                                          String content, String semanticBinding, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE skill SET name=?,description=?,content=?,semantic_binding=?,status=?,updated_at=? WHERE id=?")) {
            ps.setString(1, name); ps.setString(2, description == null ? "" : description);
            ps.setString(3, content == null ? "" : content);
            ps.setString(4, semanticBinding == null ? "" : semanticBinding);
            ps.setString(5, status == null ? "active" : status);
            ps.setLong(6, System.currentTimeMillis()); ps.setString(7, id);
            ps.executeUpdate();
        }
    }

    public synchronized void deleteSkill(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM skill WHERE id=?")) { ps.setString(1, id); ps.executeUpdate(); }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM agent_binding WHERE binding_type='skill' AND target_id=?")) { ps.setString(1, id); ps.executeUpdate(); }
    }

    public synchronized List<Map<String, Object>> listSkills() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM skill ORDER BY updated_at DESC")) {
            while (rs.next()) { Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id")); m.put("name", rs.getString("name"));
                m.put("description", rs.getString("description")); m.put("source", rs.getString("source"));
                m.put("filePath", rs.getString("file_path")); m.put("content", rs.getString("content"));
                m.put("semanticBinding", rs.getString("semantic_binding")); m.put("status", rs.getString("status"));
                m.put("createdAt", rs.getLong("created_at")); m.put("updatedAt", rs.getLong("updated_at"));
                out.add(m);
            }
        }
        return out;
    }

    public synchronized SkillConfig getSkill(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM skill WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SkillConfig(rs.getString("id"), rs.getString("name"), rs.getString("description"),
                        rs.getString("source"), rs.getString("file_path"), rs.getString("content"),
                        rs.getString("semantic_binding"), rs.getString("status"));
            }
        }
    }

    public synchronized List<SkillConfig> getSkills(List<String> ids) throws SQLException {
        List<SkillConfig> out = new ArrayList<>();
        for (String id : ids) { SkillConfig s = getSkill(id); if (s != null) out.add(s); }
        return out;
    }

    // ==================== DataApi ====================

    public synchronized String createDataApi(String name, String method, String url, String headers,
                                             String inputs, String outputs, String semanticBinding) throws SQLException {
        String id = "api-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO data_api(id,name,method,url,headers,inputs,outputs,semantic_binding,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, name); ps.setString(3, method == null ? "GET" : method);
            ps.setString(4, url); ps.setString(5, headers == null ? "{}" : headers);
            ps.setString(6, inputs == null ? "[]" : inputs); ps.setString(7, outputs == null ? "[]" : outputs);
            ps.setString(8, semanticBinding == null ? "{}" : semanticBinding);
            ps.setString(9, "active"); ps.setLong(10, now); ps.setLong(11, now);
            ps.executeUpdate();
        }
        return id;
    }

    public synchronized void updateDataApi(String id, String name, String method, String url, String headers,
                                           String inputs, String outputs, String semanticBinding, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE data_api SET name=?,method=?,url=?,headers=?,inputs=?,outputs=?,semantic_binding=?,status=?,updated_at=? WHERE id=?")) {
            ps.setString(1, name); ps.setString(2, method == null ? "GET" : method);
            ps.setString(3, url); ps.setString(4, headers == null ? "{}" : headers);
            ps.setString(5, inputs == null ? "[]" : inputs); ps.setString(6, outputs == null ? "[]" : outputs);
            ps.setString(7, semanticBinding == null ? "{}" : semanticBinding);
            ps.setString(8, status == null ? "active" : status);
            ps.setLong(9, System.currentTimeMillis()); ps.setString(10, id);
            ps.executeUpdate();
        }
    }

    public synchronized void deleteDataApi(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM data_api WHERE id=?")) { ps.setString(1, id); ps.executeUpdate(); }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM agent_binding WHERE binding_type='api' AND target_id=?")) { ps.setString(1, id); ps.executeUpdate(); }
    }

    public synchronized List<Map<String, Object>> listDataApis() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM data_api ORDER BY updated_at DESC")) {
            while (rs.next()) { out.add(dataApiRow(rs)); }
        }
        return out;
    }

    public synchronized DataApiConfig getDataApi(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM data_api WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new DataApiConfig(rs.getString("id"), rs.getString("name"), rs.getString("method"),
                        rs.getString("url"), DataApiConfig.parseMap(rs.getString("headers")),
                        DataApiConfig.parseParams(rs.getString("inputs")), DataApiConfig.parseParams(rs.getString("outputs")),
                        DataApiConfig.parseSemantic(rs.getString("semantic_binding")), rs.getString("status"));
            }
        }
    }

    public synchronized List<DataApiConfig> getDataApis(List<String> ids) throws SQLException {
        List<DataApiConfig> out = new ArrayList<>();
        for (String id : ids) { DataApiConfig a = getDataApi(id); if (a != null) out.add(a); }
        return out;
    }

    private Map<String, Object> dataApiRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id")); m.put("name", rs.getString("name"));
        m.put("method", rs.getString("method")); m.put("url", rs.getString("url"));
        m.put("headers", rs.getString("headers")); m.put("inputs", rs.getString("inputs"));
        m.put("outputs", rs.getString("outputs")); m.put("semanticBinding", rs.getString("semantic_binding"));
        m.put("status", rs.getString("status"));
        m.put("createdAt", rs.getLong("created_at")); m.put("updatedAt", rs.getLong("updated_at"));
        return m;
    }

    // ==================== Mcp ====================

    public synchronized String createMcp(String name, String transport, String command, String args,
                                         String env, String url, String semanticBinding) throws SQLException {
        String id = "mcp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mcp_server(id,name,transport,command,args,env,url,semantic_binding,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, name); ps.setString(3, transport == null ? "stdio" : transport);
            ps.setString(4, command); ps.setString(5, args == null ? "[]" : args);
            ps.setString(6, env == null ? "{}" : env); ps.setString(7, url);
            ps.setString(8, semanticBinding == null ? "{}" : semanticBinding);
            ps.setString(9, "inactive"); ps.setLong(10, now); ps.setLong(11, now);
            ps.executeUpdate();
        }
        return id;
    }

    public synchronized void updateMcp(String id, String name, String transport, String command, String args,
                                        String env, String url, String semanticBinding, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mcp_server SET name=?,transport=?,command=?,args=?,env=?,url=?,semantic_binding=?,status=?,updated_at=? WHERE id=?")) {
            ps.setString(1, name); ps.setString(2, transport == null ? "stdio" : transport);
            ps.setString(3, command); ps.setString(4, args == null ? "[]" : args);
            ps.setString(5, env == null ? "{}" : env); ps.setString(6, url);
            ps.setString(7, semanticBinding == null ? "{}" : semanticBinding);
            ps.setString(8, status == null ? "inactive" : status);
            ps.setLong(9, System.currentTimeMillis()); ps.setString(10, id);
            ps.executeUpdate();
        }
    }

    public synchronized void deleteMcp(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM mcp_server WHERE id=?")) { ps.setString(1, id); ps.executeUpdate(); }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM agent_binding WHERE binding_type='mcp' AND target_id=?")) { ps.setString(1, id); ps.executeUpdate(); }
    }

    public synchronized List<Map<String, Object>> listMcps() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM mcp_server ORDER BY updated_at DESC")) {
            while (rs.next()) { out.add(mcpRow(rs)); }
        }
        return out;
    }

    public synchronized McpConfig getMcp(String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM mcp_server WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new McpConfig(rs.getString("id"), rs.getString("name"), rs.getString("transport"),
                        rs.getString("command"), McpConfig.parseArgs(rs.getString("args")), McpConfig.parseEnv(rs.getString("env")),
                        rs.getString("url"), McpConfig.parseSemantic(rs.getString("semantic_binding")), rs.getString("status"));
            }
        }
    }

    public synchronized List<McpConfig> getMcps(List<String> ids) throws SQLException {
        List<McpConfig> out = new ArrayList<>();
        for (String id : ids) { McpConfig m = getMcp(id); if (m != null) out.add(m); }
        return out;
    }

    private Map<String, Object> mcpRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id")); m.put("name", rs.getString("name"));
        m.put("transport", rs.getString("transport")); m.put("command", rs.getString("command"));
        m.put("args", rs.getString("args")); m.put("env", rs.getString("env"));
        m.put("url", rs.getString("url")); m.put("semanticBinding", rs.getString("semantic_binding"));
        m.put("status", rs.getString("status"));
        m.put("createdAt", rs.getLong("created_at")); m.put("updatedAt", rs.getLong("updated_at"));
        return m;
    }

    @Override
    public synchronized void close() {}
}
