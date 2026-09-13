package com.agentscope.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill 配置（从 SQLite skill 表装载）。
 *
 * <p>写入 workspace/skills/{name}.md 后，HarnessAgent 原生自动发现。
 * semanticBinding 转换为 Tool Schema description 的一部分。
 */
public class SkillConfig {
    public final String id;
    public final String name;
    public final String description;
    public final String source;
    public final String filePath;
    public final String content;            // 完整 .md 内容
    public final String semanticBinding;    // 自然语言：何时调用
    public final String status;

    public SkillConfig(String id, String name, String description, String source,
                       String filePath, String content, String semanticBinding, String status) {
        this.id = id; this.name = name; this.description = description == null ? "" : description;
        this.source = source == null ? "manual" : source; this.filePath = filePath;
        this.content = content == null ? "" : content; this.semanticBinding = semanticBinding == null ? "" : semanticBinding;
        this.status = status == null ? "active" : status;
    }

    /** 工具描述（注入 ToolSchema.description）。 */
    public String toolDescription() {
        StringBuilder sb = new StringBuilder();
        if (semanticBinding != null && !semanticBinding.isBlank()) sb.append(semanticBinding);
        if (description != null && !description.isBlank()) { if (sb.length() > 0) sb.append("\n"); sb.append(description); }
        return sb.length() > 0 ? sb.toString() : "Skill: " + name;
    }
}
