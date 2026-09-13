package com.agentscope.agent;

import com.agentscope.config.ModelConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 多模型 Agent 池。
 *
 * <p>从 ModelConfig 加载所有 profile，为每个 profile 惰性构建一个 HarnessAgent
 * （独立 apiKey / baseUrl / model）。根据会话记忆选择模型：
 * <ul>
 *   <li>请求中指定 model → 使用该模型，并记住（首次发送）</li>
 *   <li>未指定 → 沿用本会话上次使用的模型；无记录则用 active profile</li>
 * </ul>
 *
 * <p>配置文件支持热加载：每次访问时检查文件修改时间，变更则重新加载并清空 agent 缓存。
 */
public class AgentModels {
    private final Path configFile;
    private final String sysPrompt;
    private final Path workspace;
    private volatile ModelConfig config;
    private volatile long configMtime;
    private final ConcurrentMap<String, HarnessAgent> agents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionModels = new ConcurrentHashMap<>();

    public AgentModels(Path configFile, String sysPrompt, Path workspace) throws IOException {
        this.configFile = configFile;
        this.sysPrompt = sysPrompt;
        this.workspace = workspace;
        this.config = ModelConfig.load(configFile);
        this.configMtime = mtime();
    }

    /** 检查配置文件是否变更，若变更则热加载。 */
    public synchronized void refresh() {
        long m = mtime();
        if (m != 0 && m != configMtime) {
            try {
                this.config = ModelConfig.load(configFile);
                this.agents.clear();
                this.configMtime = m;
                System.out.println("[agentscope] model config reloaded: " + configFile
                        + " (profiles=" + config.profiles.size() + ")");
            } catch (IOException e) {
                System.err.println("[agentscope] reload model config failed: " + e.getMessage());
                this.configMtime = m; // 避免每次请求重复报错
            }
        }
    }

    private long mtime() {
        try {
            return Files.getLastModifiedTime(configFile).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** 全部可用模型 profile（用于 /api/models 列表）。 */
    public List<ModelConfig.Profile> profiles() {
        refresh();
        return config.profiles;
    }

    /** 当前激活 profile。 */
    public ModelConfig.Profile active() {
        refresh();
        return config.active();
    }

    /**
     * 解析本会话应使用的模型（请求可选 model 参数）。
     *
     * @param sessionId 会话 id，用于记忆上次模型
     * @param requested 请求指定的模型标识；可为 null（沿用记忆/默认）
     * @return 对应 profile
     * @throws IllegalArgumentException 指定的模型不存在
     */
    public ModelConfig.Profile resolveProfile(String sessionId, String requested) {
        refresh();
        if (requested != null && !requested.isBlank()) {
            ModelConfig.Profile p = config.find(requested);
            if (p == null) throw new IllegalArgumentException("unknown model: " + requested);
            if (sessionId != null) sessionModels.put(sessionId, p.profileId());
            return p;
        }
        if (sessionId != null) {
            String remembered = sessionModels.get(sessionId);
            if (remembered != null) {
                ModelConfig.Profile p = config.find(remembered);
                if (p != null) return p;
            }
        }
        return active();
    }

    /** 获取某 profile 对应的 HarnessAgent（惰性构建并缓存）。 */
    public HarnessAgent agentFor(ModelConfig.Profile profile) {
        if (profile == null) throw new IllegalArgumentException("no model profile available");
        String key = profile.profileId();
        return agents.computeIfAbsent(key, k -> build(profile));
    }

    private HarnessAgent build(ModelConfig.Profile p) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(p.apiKey)
                .baseUrl(p.baseUrl)
                .modelName(p.model)
                .stream(true)
                .build();
        return HarnessAgent.builder()
                .name("agentscope-bot")
                .sysPrompt(sysPrompt)
                .model(model)
                .workspace(workspace)
                .middleware(new CustomPromptMiddleware())
                .build();
    }

    /** 关闭所有 agent。 */
    public void close() {
        agents.values().forEach(a -> { try { a.close(); } catch (Exception ignored) {} });
    }
}