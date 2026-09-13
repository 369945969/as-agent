package com.agentscope;

import com.agentscope.agent.AgentManager;
import com.agentscope.agent.AgentModels;
import com.agentscope.agent.ChatRunner;
import com.agentscope.config.Config;
import com.agentscope.config.ModelConfig;
import com.agentscope.store.AgentStore;
import com.agentscope.store.MessageStore;
import com.agentscope.web.WebServer;
import com.agentscope.ws.WsServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AgentscopeApp {
    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : cfg.port;

        Path wsDir = Paths.get(cfg.workspaceDir);
        Files.createDirectories(wsDir);
        Path skillsAbs = Paths.get(cfg.skillsDir).isAbsolute()
                ? Paths.get(cfg.skillsDir)
                : Path.of(System.getProperty("user.dir"), cfg.skillsDir);

        // 模型配置支持热加载：AgentModels 内部按文件修改时间自动重载
        AgentModels models = new AgentModels(Paths.get(cfg.modelConfigFile), cfg.sysPrompt, wsDir);
        ModelConfig.Profile active = models.active();

        // SQLite 消息存储（原始事件入库，sessionId 关联，刷新可重放）
        Path dbFile = Paths.get(cfg.dbFile);
        MessageStore store = new MessageStore(dbFile);
        // 智能体/Skill/API/MCP 的 SQLite CRUD（复用同一 Connection）
        AgentStore agentStore = new AgentStore(store.getConnection());
        // 智能体管理器：根据 agentId 动态构建/缓存 HarnessAgent
        AgentManager agentManager = new AgentManager(models, agentStore, wsDir, cfg.sysPrompt);
        ChatRunner runner = new ChatRunner(models, store, agentManager);

        System.out.println("[agentscope] port=" + port);
        System.out.println("[agentscope] modelConfig=" + cfg.modelConfigFile);
        System.out.println("[agentscope] models=" + models.profiles().size()
                + " active=" + (active == null ? "-" : active.model));
        System.out.println("[agentscope] db=" + dbFile.toAbsolutePath());
        System.out.println("[agentscope] workspace=" + wsDir.toAbsolutePath());
        System.out.println("[agentscope] skills=" + skillsAbs.toAbsolutePath());
        System.out.println("[agentscope] token=" + cfg.token.substring(0, Math.min(8, cfg.token.length())) + "...");
        System.out.println("[agentscope] URL: http://localhost:" + port + "/?token=" + cfg.token);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { runner.close(); } catch (Exception ignored) {}
            try { agentManager.invalidateAll(); } catch (Exception ignored) {}
            try { models.close(); } catch (Exception ignored) {}
            try { agentStore.close(); } catch (Exception ignored) {}
            try { store.close(); } catch (Exception ignored) {}
        }));

        // 启动 Web API（HTTP）
        int wsPort = port + 1;
        System.out.println("[agentscope] Web server listening on http://localhost:" + port + " (web)");
        WebServer web = new WebServer(port, models, runner, store, agentStore, agentManager, cfg);
        web.start();

        // 启动 WS streaming（始终同时开放）
        System.out.println("[agentscope] WebSocket server listening on ws://localhost:" + wsPort + " (ws)");
        WsServer ws = new WsServer(port, runner, store, cfg);
        ws.start();

        System.out.println("[agentscope] ready (web=" + port + ", ws=" + wsPort + ")");
    }
}