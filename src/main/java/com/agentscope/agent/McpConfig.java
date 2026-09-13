package com.agentscope.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置（从 SQLite mcp_server 表装载）。
 *
 * <p>连接后自动拉取 tools/list，semanticBinding 补充到每个工具 description。
 * transport=stdio: command+args+env; transport=sse: url。
 */
public class McpConfig {
    private static final ObjectMapper M = new ObjectMapper();

    public final String id;
    public final String name;
    public final String transport;     // stdio | sse
    public final String command;        // stdio: 可执行命令
    public final List<String> args;     // stdio: 参数
    public final Map<String, String> env; // stdio: 环境变量
    public final String url;            // sse: 端点
    public final McpSemanticBinding semanticBinding;
    public final String status;

    public McpConfig(String id, String name, String transport, String command,
                     List<String> args, Map<String, String> env, String url,
                     McpSemanticBinding semanticBinding, String status) {
        this.id = id; this.name = name; this.transport = transport == null ? "stdio" : transport;
        this.command = command; this.args = args == null ? List.of() : args;
        this.env = env == null ? Map.of() : env; this.url = url;
        this.semanticBinding = semanticBinding == null ? new McpSemanticBinding() : semanticBinding;
        this.status = status == null ? "inactive" : status;
    }

    public boolean isStdio() { return "stdio".equalsIgnoreCase(transport); }

    public String summary() {
        return semanticBinding.summary != null && !semanticBinding.summary.isBlank()
                ? semanticBinding.summary : "MCP Server: " + name;
    }

    // ---- 反序列化辅助 ----
    public static List<String> parseArgs(String json) {
        try { return M.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }
    public static Map<String, String> parseEnv(String json) {
        try { return M.readValue(json, new TypeReference<Map<String, String>>() {}); }
        catch (Exception e) { return Map.of(); }
    }
    public static McpSemanticBinding parseSemantic(String json) {
        try { return M.readValue(json, McpSemanticBinding.class); }
        catch (Exception e) { return new McpSemanticBinding(); }
    }

    public static class McpSemanticBinding {
        public String summary = "";
        public Map<String, String> toolSemantics = new java.util.LinkedHashMap<>();
    }
}
