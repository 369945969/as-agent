package com.agentscope.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据服务 API 配置（从 SQLite data_api 表装载）。
 *
 * <p>后端 DataApiTool 据此生成 ToolSchema + 执行 HTTP 调用。
 * semanticBinding 转换为 ToolSchema.description 的一部分，让 Agent 理解何时调用、输入输出语义。
 */
public class DataApiConfig {
    private static final ObjectMapper M = new ObjectMapper();

    public final String id;
    public final String name;
    public final String method;       // GET | POST | PUT | DELETE
    public final String url;
    public final Map<String, String> headers;
    public final List<Param> inputs;
    public final List<Param> outputs;
    public final SemanticBinding semanticBinding;
    public final String status;

    public DataApiConfig(String id, String name, String method, String url,
                         Map<String, String> headers, List<Param> inputs, List<Param> outputs,
                         SemanticBinding semanticBinding, String status) {
        this.id = id; this.name = name; this.method = method == null ? "GET" : method;
        this.url = url; this.headers = headers == null ? Map.of() : headers;
        this.inputs = inputs == null ? List.of() : inputs;
        this.outputs = outputs == null ? List.of() : outputs;
        this.semanticBinding = semanticBinding == null ? new SemanticBinding() : semanticBinding;
        this.status = status == null ? "active" : status;
    }

    /** 工具名（注册到 Toolkit 的唯一标识）。 */
    public String toolName() { return "api_" + sanitize(name); }

    /** 生成 ToolSchema.description（含语义绑定）。 */
    public String toolDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(semanticBinding.summary != null && !semanticBinding.summary.isBlank()
                ? semanticBinding.summary : "数据服务API: " + name);
        sb.append(" (").append(method).append(" ").append(url).append(")");

        if (!inputs.isEmpty()) {
            sb.append("\n\n输入参数：");
            for (Param p : inputs) {
                sb.append("\n- ").append(p.name).append(" (").append(p.type).append(")");
                if (p.required) sb.append(" [必填]");
                if (p.description != null && !p.description.isBlank()) sb.append(": ").append(p.description);
                String sem = semanticBinding.inputSemantics.get(p.name);
                if (sem != null && !sem.isBlank()) sb.append(" 语义: ").append(sem);
            }
        }
        if (!outputs.isEmpty()) {
            sb.append("\n\n输出：");
            for (Param p : outputs) {
                sb.append("\n- ").append(p.name).append(" (").append(p.type).append(")");
                if (p.description != null && !p.description.isBlank()) sb.append(": ").append(p.description);
                String sem = semanticBinding.outputSemantics.get(p.name);
                if (sem != null && !sem.isBlank()) sb.append(" 语义: ").append(sem);
            }
        }
        return sb.toString();
    }

    /** 生成 JSON Schema（用于 ToolSchema.inputSchema）。 */
    public Map<String, Object> toJsonSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Param p : inputs) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", jsonSchemaType(p.type));
            prop.put("description", p.description != null ? p.description : "");
            props.put(p.name, prop);
            if (p.required) required.add(p.name);
        }
        schema.put("properties", props);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private static String jsonSchemaType(String t) {
        if (t == null) return "string";
        return switch (t.toLowerCase()) {
            case "number", "integer", "int", "float", "double", "long" -> "number";
            case "boolean", "bool" -> "boolean";
            case "array" -> "array";
            case "object" -> "object";
            default -> "string";
        };
    }

    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }

    // ---- 反序列化辅助 ----
    public static List<Param> parseParams(String json) {
        try { return M.readValue(json, new TypeReference<List<Param>>() {}); }
        catch (Exception e) { return List.of(); }
    }
    public static Map<String, String> parseMap(String json) {
        try { return M.readValue(json, new TypeReference<Map<String, String>>() {}); }
        catch (Exception e) { return Map.of(); }
    }
    public static SemanticBinding parseSemantic(String json) {
        try { return M.readValue(json, SemanticBinding.class); }
        catch (Exception e) { return new SemanticBinding(); }
    }

    public static class Param {
        public String name;
        public String type = "string";
        public boolean required;
        public String description = "";
    }

    public static class SemanticBinding {
        public String summary = "";
        public Map<String, String> inputSemantics = new LinkedHashMap<>();
        public Map<String, String> outputSemantics = new LinkedHashMap<>();
    }
}
