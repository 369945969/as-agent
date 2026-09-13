package com.agentscope.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体配置（从 SQLite agent + agent_binding 表装载的聚合视图）。
 *
 * <p>ChatRunner 在 prompt 时携带 agentId → AgentManager.get(agentId) 获取此配置 →
 * 构建（或从缓存取）对应的 HarnessAgent。配置变更后缓存失效重建。
 *
 * <p>智能体创建后复用现有 ChatRunner/WsServer/WebServer：
 * 仅 sysPrompt / model / toolkit（skill+api+mcp）按配置覆盖，执行引擎不变。
 */
public class AgentConfig {

    /** 智能体 ID（UUID），null 表示默认助手（用 AgentModels 全局 active）。 */
    public final String id;
    /** 标题（显示名，同时作为 HarnessAgent.name）。 */
    public final String title;
    /** 描述（同时作为 HarnessAgent.description）。 */
    public final String description;
    /** 系统提示词（同时作为 HarnessAgent.sysPrompt）。 */
    public final String systemPrompt;
    /** 绑定模型 profile id（空=用 active profile）。 */
    public final String modelProfileId;
    /** 图标 emoji。 */
    public final String icon;

    /** 绑定的 Skill ID 列表。 */
    public final List<String> skillIds;
    /** 绑定的数据服务 API ID 列表。 */
    public final List<String> apiIds;
    /** 绑定的 MCP Server ID 列表。 */
    public final List<String> mcpIds;

    public AgentConfig(String id, String title, String description, String systemPrompt,
                       String modelProfileId, String icon,
                       List<String> skillIds, List<String> apiIds, List<String> mcpIds) {
        this.id = id;
        this.title = title;
        this.description = description == null ? "" : description;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.modelProfileId = modelProfileId == null ? "" : modelProfileId;
        this.icon = icon == null ? "🤖" : icon;
        this.skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        this.apiIds = apiIds == null ? List.of() : List.copyOf(apiIds);
        this.mcpIds = mcpIds == null ? List.of() : List.copyOf(mcpIds);
    }

    /** 是否有上下文注入（skill / api / mcp 任一非空）。 */
    public boolean hasInjections() {
        return !skillIds.isEmpty() || !apiIds.isEmpty() || !mcpIds.isEmpty();
    }

    /** 缓存 key：配置变更（updatedAt 变化）时 AgentManager 据此判断是否重建。 */
    public String cacheKey() {
        return id + ":" + modelProfileId + ":" + skillIds + ":" + apiIds + ":" + mcpIds;
    }

    // ---- 内部 Builder（从 DB Row 构建）----
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id, title, description = "", systemPrompt = "", modelProfileId = "", icon = "🤖";
        private final List<String> skillIds = new ArrayList<>();
        private final List<String> apiIds = new ArrayList<>();
        private final List<String> mcpIds = new ArrayList<>();

        public Builder id(String v) { id = v; return this; }
        public Builder title(String v) { title = v; return this; }
        public Builder description(String v) { description = v; return this; }
        public Builder systemPrompt(String v) { systemPrompt = v; return this; }
        public Builder modelProfileId(String v) { modelProfileId = v; return this; }
        public Builder icon(String v) { icon = v; return this; }
        public Builder addSkill(String v) { if (v != null && !v.isBlank()) skillIds.add(v); return this; }
        public Builder addApi(String v) { if (v != null && !v.isBlank()) apiIds.add(v); return this; }
        public Builder addMcp(String v) { if (v != null && !v.isBlank()) mcpIds.add(v); return this; }

        public AgentConfig build() {
            return new AgentConfig(id, title, description, systemPrompt, modelProfileId, icon,
                    skillIds, apiIds, mcpIds);
        }
    }
}
