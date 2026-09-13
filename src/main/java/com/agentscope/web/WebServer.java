package com.agentscope.web;

import com.agentscope.agent.AgentManager;
import com.agentscope.agent.AgentManager;
import com.agentscope.agent.AgentModels;
import com.agentscope.agent.ChatRunner;
import com.agentscope.config.Config;
import com.agentscope.config.ModelConfig;
import com.agentscope.store.AgentStore;
import com.agentscope.store.MessageStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class WebServer {
    private static final ObjectMapper M = new ObjectMapper();
    private final HttpServer server;
    private final Config cfg;

    public WebServer(int port, AgentModels models, ChatRunner runner, MessageStore store,
                     AgentStore agentStore, AgentManager agentManager, Config cfg) throws IOException {
        this.cfg = cfg;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        // 静态文件（index.html / app.js）
        server.createContext("/", new StaticHandler());

        // API — 原有
        server.createContext("/api/agent/health", new HealthHandler());
        server.createContext("/api/models", new ModelsHandler(models));
        server.createContext("/api/agent/send", new SendHandler(runner, cfg));
        server.createContext("/api/sessions", new SessionsHandler(store));
        server.createContext("/api/messages", new MessagesHandler(store));

        // API — 智能体管理
        server.createContext("/api/agents", new AgentApiHandler(agentStore, agentManager, models));
        // API — Skill 管理
        server.createContext("/api/skills", new SkillApiHandler(agentStore));
        // API — 数据服务 API 管理
        server.createContext("/api/data-apis", new DataApiHandler(agentStore));
        // API — MCP 管理
        server.createContext("/api/mcps", new McpApiHandler(agentStore));

        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() { server.start(); }

    // ---- token 校验 ----
    static boolean checkToken(HttpExchange ex, Config cfg) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null && cookie.contains("dsh-auth=" + cfg.token)) return true;
        String q = ex.getRequestURI().getQuery();
        return q != null && q.contains("token=" + cfg.token);
    }

    static boolean unauthorized(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(401, 0); ex.getResponseBody().close();
        return false;
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new LinkedHashMap<>();
        String q = ex.getRequestURI().getQuery();
        if (q != null) for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                             URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static void json(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = M.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        OutputStream os = ex.getResponseBody(); os.write(b); os.close();
    }

    // ---- 静态文件 ----
    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();

            // token 握手：URL 带 ?token= → 设 cookie（后续 API/WS 复用）
            if (query != null && query.contains("token=")) {
                String[] parts = query.split("token=");
                if (parts.length > 1) {
                    String t = parts[1].split("&")[0];
                    ex.getResponseHeaders().set("Set-Cookie", "dsh-auth=" + t + "; Path=/; HttpOnly");
                }
            }

            if (path.equals("/") || path.equals("/index.html")) path = "/index.html";
            Path resDir = Path.of(System.getProperty("user.dir"), "src/main/resources/static");
            Path file = resDir.resolve(path.substring(1));
            if (!Files.exists(file)) {
                file = Path.of(System.getProperty("user.dir"), "agentscope/src/main/resources/static" + path);
            }
            if (!Files.exists(file)) { ex.sendResponseHeaders(404, 0); ex.getResponseBody().close(); return; }
            byte[] body = Files.readAllBytes(file);
            String ct = path.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8";
            ex.getResponseHeaders().set("Content-Type", ct);
            ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            ex.sendResponseHeaders(200, body.length);
            OutputStream os = ex.getResponseBody(); os.write(body); os.close();
        }
    }

    // ---- health ----
    static class HealthHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, new Config())) { unauthorized(ex); return; }
            json(ex, 200, Map.of("status", "ok"));
        }
    }

    // ---- models（可用的多模型列表）----
    static class ModelsHandler implements HttpHandler {
        private final AgentModels models;
        ModelsHandler(AgentModels m) { models = m; }

        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, new Config())) { unauthorized(ex); return; }
            List<Map<String, Object>> items = new ArrayList<>();
            for (ModelConfig.Profile p : models.profiles()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.profileId());
                m.put("displayName", p.label());
                m.put("model", p.model);
                items.add(m);
            }
            ModelConfig.Profile active = models.active();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("active", active == null ? "" : active.profileId());
            r.put("items", items);
            json(ex, 200, r);
        }
    }

    // ---- send（HTTP 回退：阻塞走同一持久化 pipeline，返回聚合结果）----
    static class SendHandler implements HttpHandler {
        private final ChatRunner runner;
        private final Config cfg;
        SendHandler(ChatRunner r, Config c) { runner = r; cfg = c; }

        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, cfg)) { unauthorized(ex); return; }
            String body = readBody(ex);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> req = M.readValue(body, Map.class);
                String message = (String) req.getOrDefault("message", "");
                String sessionId = (String) req.getOrDefault("sessionId", UUID.randomUUID().toString());
                String userId = (String) req.getOrDefault("userId", "default");
                String requestedModel = (String) req.get("model");
                String systemPrompt = (String) req.get("systemPrompt");
                String skillPrompt = (String) req.get("skillPrompt");
                String agentId = (String) req.get("agentId");

                ChatRunner.RunResult res = runner.submitAndAwait(
                        sessionId, userId, message, requestedModel, systemPrompt, skillPrompt, agentId, 600_000);

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("sessionId", sessionId);
                resp.put("reply", res.reply.toString());
                resp.put("thinking", res.thinking.toString());
                resp.put("toolCalls", res.toolCalls);
                resp.put("model", res.model);
                resp.put("status", res.status);
                json(ex, 200, resp);
            } catch (IllegalArgumentException e) {
                json(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
            } catch (IllegalStateException e) {
                json(ex, 409, Map.of("error", String.valueOf(e.getMessage())));
            } catch (Exception e) {
                json(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        }
    }

    // ---- sessions（DB 列表，含 status 供前端判断是否需要续订）----
    static class SessionsHandler implements HttpHandler {
        private final MessageStore store;
        SessionsHandler(MessageStore s) { store = s; }
        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, new Config())) { unauthorized(ex); return; }
            String userId = query(ex).getOrDefault("userId", "");
            try {
                json(ex, 200, Map.of("items", store.listSessions(userId.isEmpty() ? null : userId)));
            } catch (Exception e) { json(ex, 500, Map.of("error", String.valueOf(e.getMessage()))); }
        }
    }

    // ---- messages（原始事件重放：data 为未修改的原始 JSON）----
    static class MessagesHandler implements HttpHandler {
        private final MessageStore store;
        MessagesHandler(MessageStore s) { store = s; }
        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, new Config())) { unauthorized(ex); return; }
            Map<String, String> q = query(ex);
            String sid = q.getOrDefault("sessionId", "");
            int after = 0;
            try { after = Integer.parseInt(q.getOrDefault("after", "0")); } catch (Exception ignored) {}
            if (sid.isEmpty()) { json(ex, 400, Map.of("error", "sessionId required")); return; }
            try {
                List<Map<String, Object>> items = new ArrayList<>();
                int lastSeq = after;
                for (MessageStore.Row r : store.listEvents(sid, after)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seq", r.seq);
                    m.put("turn", r.turn);
                    m.put("event", r.eventType);
                    m.put("data", M.readTree(r.data)); // RAW, parsed for transport only
                    items.add(m);
                    lastSeq = r.seq;
                }
                String status = store.getStatus(sid);
                if (status == null) status = "unknown";
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("sessionId", sid);
                resp.put("status", status);
                resp.put("lastSeq", lastSeq);
                resp.put("items", items);
                json(ex, 200, resp);
            } catch (Exception e) { json(ex, 500, Map.of("error", String.valueOf(e.getMessage()))); }
        }
    }

    static String readBody(HttpExchange ex) throws IOException {
        InputStream is = ex.getRequestBody();
        return new String(is.readAllBytes());
    }

    static class AgentApiHandler implements HttpHandler {
        private final AgentStore store;
        private final AgentManager agentManager;
        private final AgentModels models;
        AgentApiHandler(AgentStore s, AgentManager am, AgentModels m) { store = s; agentManager = am; models = m; }

        @SuppressWarnings("unchecked")
        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, new Config())) { unauthorized(ex); return; }
            try {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                String base = path.substring("/api/agents".length());

                if ("GET".equals(method) && (base.isEmpty() || base.equals("/"))) {
                    json(ex, 200, Map.of("items", store.listAgents()));
                    return;
                }
                if ("GET".equals(method) && base.startsWith("/") && !base.contains("/")) {
                    String id = base.substring(1);
                    Map<String, Object> agent = store.getAgentRow(id);
                    if (agent == null) { json(ex, 404, Map.of("error", "not found")); return; }
                    var cfg = store.getAgentConfig(id);
                    if (cfg != null) {
                        agent.put("skillIds", cfg.skillIds);
                        agent.put("apiIds", cfg.apiIds);
                        agent.put("mcpIds", cfg.mcpIds);
                    }
                    json(ex, 200, agent);
                    return;
                }
                if ("POST".equals(method) && (base.isEmpty() || base.equals("/"))) {
                    Map<String, Object> req = M.readValue(readBody(ex), Map.class);
                    String title = (String) req.get("title");
                    if (title == null || title.isBlank()) { json(ex, 400, Map.of("error", "title required")); return; }
                    String id = store.createAgent(title, (String) req.get("description"),
                            (String) req.get("systemPrompt"), (String) req.get("modelProfileId"),
                            (String) req.getOrDefault("icon", "🤖"));
                    json(ex, 200, Map.of("id", id));
                    return;
                }
                if ("POST".equals(method) && base.equals("/generate-prompt")) {
                    Map<String, Object> req = M.readValue(readBody(ex), Map.class);
                    String title = (String) req.getOrDefault("title", "");
                    String description = (String) req.getOrDefault("description", "");
                    String prompt = generatePrompt(title, description);
                    json(ex, 200, Map.of("prompt", prompt));
                    return;
                }
                if ("POST".equals(method) && base.endsWith("/bindings")) {
                    String id = base.substring(1, base.length() - "/bindings".length());
                    Map<String, Object> req = M.readValue(readBody(ex), Map.class);
                    List<String> skillIds = (List<String>) req.getOrDefault("skillIds", List.of());
                    List<String> apiIds = (List<String>) req.getOrDefault("apiIds", List.of());
                    List<String> mcpIds = (List<String>) req.getOrDefault("mcpIds", List.of());
                    store.updateBindings(id, skillIds, apiIds, mcpIds);
                    agentManager.invalidate(id);
                    json(ex, 200, Map.of("ok", true));
                    return;
                }
                if ("PUT".equals(method) && base.startsWith("/") && !base.contains("/")) {
                    String id = base.substring(1);
                    Map<String, Object> req = M.readValue(readBody(ex), Map.class);
                    store.updateAgent(id, (String) req.get("title"), (String) req.get("description"),
                            (String) req.get("systemPrompt"), (String) req.get("modelProfileId"),
                            (String) req.getOrDefault("icon", "🤖"));
                    agentManager.invalidate(id);
                    json(ex, 200, Map.of("ok", true));
                    return;
                }
                if ("DELETE".equals(method) && base.startsWith("/") && !base.contains("/")) {
                    String id = base.substring(1);
                    store.deleteAgent(id);
                    agentManager.invalidate(id);
                    json(ex, 200, Map.of("ok", true));
                    return;
                }
                json(ex, 404, Map.of("error", "not found"));
            } catch (Exception e) {
                json(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        }

        private String generatePrompt(String title, String description) {
            try {
                var profile = models.active();
                if (profile == null) return "";
                var agent = models.agentFor(profile);
                String instruction = "你是一个提示词工程师。根据以下信息生成一个专业的系统提示词：\n"
                        + "标题：" + title + "\n描述：" + description + "\n\n"
                        + "要求：1.明确角色定位、能力边界、输出格式 2.使用中文 3.直接输出提示词内容不要解释";
                var result = agent.call(new io.agentscope.core.message.UserMessage(instruction),
                        io.agentscope.core.agent.RuntimeContext.builder().sessionId("prompt-gen").userId("system").build())
                        .block(java.time.Duration.ofSeconds(30));
                return result != null ? result.getTextContent() : "";
            } catch (Exception e) { return ""; }
        }
    }

    // ==================== Skill 管理 API ====================

    static class SkillApiHandler implements HttpHandler {
        private final AgentStore store;
        SkillApiHandler(AgentStore s) { store = s; }

        @SuppressWarnings("unchecked")
        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, new Config())) { unauthorized(ex); return; }
            try {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                String base = path.substring("/api/skills".length());

                if ("GET".equals(method) && (base.isEmpty() || base.equals("/"))) {
                    json(ex, 200, Map.of("items", store.listSkills()));
                    return;
                }
                if ("POST".equals(method) && (base.isEmpty() || base.equals("/"))) {
                    Map<String, Object> req = M.readValue(readBody(ex), Map.class);
                    String id = store.createSkill((String) req.get("name"), (String) req.get("description"),
                            (String) req.getOrDefault("source", "manual"), (String) req.get("filePath"),
                            (String) req.get("content"), (String) req.get("semanticBinding"));
                    json(ex, 200, Map.of("id", id));
                    return;
                }
                if ("PUT".equals(method) && base.startsWith("/")) {
                    String id = base.substring(1);
                    Map<String, Object> req = M.readValue(readBody(ex), Map.class);
                    store.updateSkill(id, (String) req.get("name"), (String) req.get("description"),
                            (String) req.get("content"), (String) req.get("semanticBinding"),
                            (String) req.getOrDefault("status", "active"));
                    json(ex, 200, Map.of("ok", true));
                    return;
                }
                if ("DELETE".equals(method) && base.startsWith("/")) {
                    store.deleteSkill(base.substring(1));
                    json(ex, 200, Map.of("ok", true));
                    return;
                }
                json(ex, 404, Map.of("error", "not found"));
            } catch (Exception e) { json(ex, 500, Map.of("error", String.valueOf(e.getMessage()))); }
        }
    }

    // ==================== 数据服务 API 管理 ====================

    static class DataApiHandler implements HttpHandler {
        private final AgentStore store;
        DataApiHandler(AgentStore s) { store = s; }

        @SuppressWarnings("unchecked")
        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, new Config())) { unauthorized(ex); return; }
            try {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                String base = path.substring("/api/data-apis".length());

                if ("GET".equals(method) && (base.isEmpty() || base.equals("/"))) {
                    json(ex, 200, Map.of("items", store.listDataApis()));
                    return;
                }
                if ("POST".equals(method) && (base.isEmpty() || base.equals("/"))) {
                    Map<String, Object> req = M.readValue(readBody(ex), Map.class);
                    String id = store.createDataApi((String) req.get("name"), (String) req.get("method"),
                            (String) req.get("url"), (String) req.getOrDefault("headers", "{}"),
                            (String) req.getOrDefault("inputs", "[]"), (String) req.getOrDefault("outputs", "[]"),
                            (String) req.getOrDefault("semanticBinding", "{}"));
                    json(ex, 200, Map.of("id", id));
                    return;
                }
                if ("PUT".equals(method) && base.startsWith("/")) {
                    String id = base.substring(1);
                    Map<String, Object> req = M.readValue(readBody(ex), Map.class);
                    store.updateDataApi(id, (String) req.get("name"), (String) req.get("method"),
                            (String) req.get("url"), (String) req.getOrDefault("headers", "{}"),
                            (String) req.getOrDefault("inputs", "[]"), (String) req.getOrDefault("outputs", "[]"),
                            (String) req.getOrDefault("semanticBinding", "{}"),
                            (String) req.getOrDefault("status", "active"));
                    json(ex, 200, Map.of("ok", true));
                    return;
                }
                if ("DELETE".equals(method) && base.startsWith("/")) {
                    store.deleteDataApi(base.substring(1));
                    json(ex, 200, Map.of("ok", true));
                    return;
                }
                json(ex, 404, Map.of("error", "not found"));
            } catch (Exception e) { json(ex, 500, Map.of("error", String.valueOf(e.getMessage()))); }
        }
    }

    // ==================== MCP 管理 API ====================

    static class McpApiHandler implements HttpHandler {
        private final AgentStore store;
        McpApiHandler(AgentStore s) { store = s; }

        @SuppressWarnings("unchecked")
        public void handle(HttpExchange ex) throws IOException {
            if (!checkToken(ex, new Config())) { unauthorized(ex); return; }
            try {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                String base = path.substring("/api/mcps".length());

                if ("GET".equals(method) && (base.isEmpty() || base.equals("/"))) {
                    json(ex, 200, Map.of("items", store.listMcps()));
                    return;
                }
                if ("POST".equals(method) && (base.isEmpty() || base.equals("/"))) {
                    Map<String, Object> req = M.readValue(readBody(ex), Map.class);
                    String id = store.createMcp((String) req.get("name"), (String) req.get("transport"),
                            (String) req.get("command"), (String) req.getOrDefault("args", "[]"),
                            (String) req.getOrDefault("env", "{}"), (String) req.get("url"),
                            (String) req.getOrDefault("semanticBinding", "{}"));
                    json(ex, 200, Map.of("id", id));
                    return;
                }
                if ("PUT".equals(method) && base.startsWith("/")) {
                    String id = base.substring(1);
                    Map<String, Object> req = M.readValue(readBody(ex), Map.class);
                    store.updateMcp(id, (String) req.get("name"), (String) req.get("transport"),
                            (String) req.get("command"), (String) req.getOrDefault("args", "[]"),
                            (String) req.getOrDefault("env", "{}"), (String) req.get("url"),
                            (String) req.getOrDefault("semanticBinding", "{}"),
                            (String) req.getOrDefault("status", "inactive"));
                    json(ex, 200, Map.of("ok", true));
                    return;
                }
                if ("DELETE".equals(method) && base.startsWith("/")) {
                    store.deleteMcp(base.substring(1));
                    json(ex, 200, Map.of("ok", true));
                    return;
                }
                json(ex, 404, Map.of("error", "not found"));
            } catch (Exception e) { json(ex, 500, Map.of("error", String.valueOf(e.getMessage()))); }
        }
    }
}
