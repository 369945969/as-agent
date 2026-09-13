package com.agentscope.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 模型配置文件（~/.dsh/model-config.json）的解析结果。
 *
 * <p>支持多个 profile（多模型），每个 profile 独立带 apiKey / baseUrl / model，
 * 有默认激活的 profile（activeId）。与该目录下 dsh CLI 的配置格式保持一致。
 */
public class ModelConfig {
    private static final ObjectMapper M = new ObjectMapper();

    public String activeId = "";
    public List<Profile> profiles = Collections.emptyList();

    public static ModelConfig load(Path file) throws IOException {
        return M.readValue(file.toFile(), ModelConfig.class);
    }

    /** 默认激活的 profile；activeId 找不到时回退到第一个。 */
    public Profile active() {
        if (activeId != null && !activeId.isBlank()) {
            for (Profile p : profiles) {
                if (activeId.equals(p.id)) return p;
            }
        }
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    /**
     * 按任意标识查找 profile：id / displayName / model / route / profiles[].models[].id。
     * 支持大小写不敏感匹配；找不到返回 null。
     */
    public Profile find(String selector) {
        if (selector == null || selector.isBlank()) return null;
        String s = selector.trim();
        for (Profile p : profiles) {
            if (matches(p.id, s) || matches(p.displayName, s) || matches(p.model, s) || matches(p.route, s)) {
                return p;
            }
            if (p.models != null) {
                for (SubModel m : p.models) {
                    if (matches(m.id, s) || matches(m.name, s)) return p;
                }
            }
        }
        return null;
    }

    private static boolean matches(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    /** 单个模型条目（profiles[].models[]）。 */
    public static class SubModel {
        public String id;
        public String name;
    }

    /** 单个模型配置（profiles[]）。 */
    public static class Profile {
        public String id;
        public String displayName;
        public String apiKey;
        public String baseUrl;
        public String model;
        public String route;
        public List<SubModel> models = Collections.emptyList();

        /** profile 的唯一标识：优先 id，否则 displayName/model 兜底。 */
        public String profileId() {
            if (id != null && !id.isBlank()) return id;
            if (displayName != null && !displayName.isBlank()) return displayName;
            return model;
        }

        /** 展示名：displayName 或 id。 */
        public String label() {
            return displayName != null && !displayName.isBlank() ? displayName : profileId();
        }
    }
}