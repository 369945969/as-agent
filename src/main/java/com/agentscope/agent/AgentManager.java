package com.agentscope.agent;

import com.agentscope.config.ModelConfig;
import com.agentscope.store.AgentStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 智能体管理器：根据 agentId 从 AgentStore 加载配置 → 构建/缓存 HarnessAgent。
 *
 * <p>智能体创建后复用现有 ChatRunner/WsServer/WebServer，仅在 prompt 时携带
 * agentId 做路由。本类负责：
 * <ol>
 *   <li>从 SQLite 装载 AgentConfig（含 skill/api/mcp 绑定）</li>
 *   <li>按配置构建 HarnessAgent（sysPrompt + model + toolkit + middleware）</li>
 *   <li>缓存：cacheKey 变化时重建（配置热更新）</li>
 *   <li>生成上下文注入摘要（供 ChatRunner 在 streamEvents 前注入事件）</li>
 * </ol>
 *
 * <p>agentId 为 null 或查不到时，回退到 AgentModels 全局默认 agent。
 */
public class AgentManager {

    private final AgentModels models;
    private final AgentStore store;
    private final Path workspace;
    private final String defaultSysPrompt;
    private final ConcurrentMap<String, CachedAgent> cache = new ConcurrentHashMap<>();

    public AgentManager(AgentModels models, AgentStore store, Path workspace, String defaultSysPrompt) {
        this.models = models;
        this.store = store;
        this.workspace = workspace;
        this.defaultSysPrompt = defaultSysPrompt;
    }

    /**
     * 获取或构建智能体。
     * @param agentId 智能体 ID；null 返回默认 agent
     * @return Holder 含 agent + config（config 可能为 null 表示默认）
     */
    public AgentHolder resolve(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            ModelConfig.Profile profile = models.active();
            HarnessAgent agent = models.agentFor(profile);
            return new AgentHolder(agent, null, profile);
        }
        try {
            AgentConfig cfg = store.getAgentConfig(agentId);
            if (cfg == null) {
                ModelConfig.Profile profile = models.active();
                return new AgentHolder(models.agentFor(profile), null, profile);
            }
            return getOrBuild(cfg);
        } catch (Exception e) {
            throw new RuntimeException("failed to load agent config: " + e.getMessage(), e);
        }
    }

    /** 失效缓存（配置更新后调用）。 */
    public void invalidate(String agentId) {
        CachedAgent c = cache.remove(agentId);
        if (c != null) try { c.agent.close(); } catch (Exception ignored) {}
    }

    /** 失效全部缓存。 */
    public void invalidateAll() {
        cache.values().forEach(c -> { try { c.agent.close(); } catch (Exception ignored) {} });
        cache.clear();
    }

    private AgentHolder getOrBuild(AgentConfig cfg) {
        String key = cfg.cacheKey();
        CachedAgent cached = cache.get(cfg.id);
        if (cached != null && cached.cacheKey.equals(key)) {
            return new AgentHolder(cached.agent, cfg, cached.profile);
        }
        // 重建
        if (cached != null) try { cached.agent.close(); } catch (Exception ignored) {}

        ModelConfig.Profile profile = models.resolveProfile(null, cfg.modelProfileId);
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(profile.apiKey).baseUrl(profile.baseUrl)
                .modelName(profile.model).stream(true).build();

        HarnessAgent.Builder b = HarnessAgent.builder()
                .name(cfg.title)
                .description(cfg.description)
                .sysPrompt(cfg.systemPrompt.isBlank() ? defaultSysPrompt : cfg.systemPrompt)
                .model(model)
                .workspace(workspace)
                .middleware(new CustomPromptMiddleware());

        // Skill：写入 workspace/skills/{name}.md，HarnessAgent 原生自动发现
        if (!cfg.skillIds.isEmpty()) {
            applySkills(b, cfg);
        }

        HarnessAgent agent = b.build();
        cache.put(cfg.id, new CachedAgent(agent, key, profile));
        return new AgentHolder(agent, cfg, profile);
    }

    /** 将绑定的 Skill 写入 workspace/skills/ 目录。 */
    private void applySkills(HarnessAgent.Builder b, AgentConfig cfg) {
        Path skillsDir = workspace.resolve("skills");
        try { Files.createDirectories(skillsDir); } catch (Exception ignored) {}
        for (String sid : cfg.skillIds) {
            try {
                SkillConfig skill = store.getSkill(sid);
                if (skill == null || skill.content == null || skill.content.isBlank()) continue;
                String fileName = skill.name != null && !skill.name.isBlank() ? skill.name : sid;
                Path file = skillsDir.resolve(fileName + ".md");
                Files.writeString(file, skill.content);
            } catch (Exception ignored) {}
        }
    }

    // ---- 上下文注入摘要（供 ChatRunner 在 streamEvents 前注入事件）----

    /** 生成注入事件数据（context/skill/mcp/api injection）。 */
    public List<InjectionEvent> buildInjectionEvents(AgentConfig cfg) {
        if (cfg == null || !cfg.hasInjections()) return List.of();
        List<InjectionEvent> events = new ArrayList<>();

        // 系统提示词注入
        if (!cfg.systemPrompt.isBlank()) {
            events.add(new InjectionEvent("context_injection", Map.of(
                    "type", "system_prompt",
                    "summary", "系统提示词已注入",
                    "detail", cfg.systemPrompt)));
        }

        // Skill 注入
        if (!cfg.skillIds.isEmpty()) {
            List<Map<String, Object>> skills = new ArrayList<>();
            for (String sid : cfg.skillIds) {
                try { SkillConfig s = store.getSkill(sid);
                    if (s != null) skills.add(Map.of("id", s.id, "name", s.name, "description", s.description,
                            "semanticBinding", s.semanticBinding));
                } catch (Exception ignored) {}
            }
            events.add(new InjectionEvent("skill_injection", Map.of(
                    "skills", skills,
                    "summary", "已注入" + skills.size() + "个技能")));
        }

        // API 注入
        if (!cfg.apiIds.isEmpty()) {
            List<Map<String, Object>> apis = new ArrayList<>();
            for (String aid : cfg.apiIds) {
                try { DataApiConfig a = store.getDataApi(aid);
                    if (a != null) apis.add(Map.of("id", a.id, "name", a.name, "method", a.method, "url", a.url));
                } catch (Exception ignored) {}
            }
            events.add(new InjectionEvent("api_injection", Map.of(
                    "apis", apis,
                    "summary", "已注入" + apis.size() + "个数据服务API")));
        }

        // MCP 注入
        if (!cfg.mcpIds.isEmpty()) {
            List<Map<String, Object>> mcps = new ArrayList<>();
            for (String mid : cfg.mcpIds) {
                try { McpConfig m = store.getMcp(mid);
                    if (m != null) mcps.add(Map.of("id", m.id, "name", m.name, "transport", m.transport));
                } catch (Exception ignored) {}
            }
            events.add(new InjectionEvent("mcp_injection", Map.of(
                    "mcps", mcps,
                    "summary", "已连接" + mcps.size() + "个MCP服务")));
        }
        return events;
    }

    // ---- 内部类 ----

    public static final class AgentHolder {
        public final HarnessAgent agent;
        public final AgentConfig config;
        public final ModelConfig.Profile profile;
        AgentHolder(HarnessAgent agent, AgentConfig config, ModelConfig.Profile profile) {
            this.agent = agent; this.config = config; this.profile = profile;
        }
    }

    public static final class InjectionEvent {
        public final String eventType;
        public final Object data;
        InjectionEvent(String eventType, Object data) { this.eventType = eventType; this.data = data; }
    }

    private static final class CachedAgent {
        final HarnessAgent agent;
        final String cacheKey;
        final ModelConfig.Profile profile;
        CachedAgent(HarnessAgent agent, String cacheKey, ModelConfig.Profile profile) {
            this.agent = agent; this.cacheKey = cacheKey; this.profile = profile;
        }
    }
}
