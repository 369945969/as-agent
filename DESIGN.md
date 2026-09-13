# AgentScope 独立工程设计

基于阿里 **AgentScope Java**（`io.agentscope:agentscope-harness`）构建独立智能体服务，提供 Web + WebSocket 访问能力。不依赖 dsh-java 任何包，全部以 agentscope 原生能力为核心。

## 1. 目标

- Web（HTTP REST + 静态 HTML/JS 交互界面）+ WebSocket 双通道访问 agent
- session 能力（多会话隔离）
- 系统提示词注入
- 用户 skill（从 skills/ 目录加载，agentscope Toolkit 原生支持）
- MCP 连接（agentscope Toolkit 原生支持 MCP server）
- token 鉴权（参考 dsh 的 ?token= 握手 + cookie）
- LLM：qwen3.7-max via DashScope（复用 sk-7c1ead… key）
- 不接数据库（session 内存态；agentscope 原生可选 MySQL/Redis，本期不开）

## 2. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| Agent 引擎 | `io.agentscope:agentscope-harness:2.0.1` | HarnessAgent（ReAct + skill/记忆/subagent + 事件流） |
| 模型 | `io.agentscope:agentscope-extensions-model-dashscope:2.0.1` | 从 `~/.dsh/model-config.json` 读取 apiKey/baseUrl/model（多模型） |
| HTTP | JDK 内置 `com.sun.net.httpserver.HttpServer` | 零额外依赖，托管静态 UI + REST |
| WebSocket | `org.java-websocket:Java-WebSocket` | 轻量 WS server，转发 agentscope 事件流 |
| JSON | Jackson（agentscope 传递依赖） | 请求/响应/事件帧/模型配置 |
| 构建 | Maven（独立 pom，非 dsh-java 子模块） | `scripts/build.sh` |
| 运行 | JDK 17+ | `scripts/start.sh`（同时开放 web + ws 端口） |

## 3. 目录结构

```
agentscope/
├── pom.xml                      # 独立 maven 工程，依赖 io.agentscope:* + sqlite-jdbc
├── DESIGN.md / DEPLOY.md / README.md
├── db/
│   └── sqlite/schema.sql        # DDL 唯一来源（构建时复制到 classpath: db/）；后续可扩展 db/mysql/
├── scripts/
│   ├── build.sh / build.bat     # mvn clean package（先杀占用 jar 的旧进程）
│   └── start.sh / start.bat     # 同时启动 web(HTTP)+ws(WebSocket)，日志打印端口
├── src/main/java/com/agentscope/
│   ├── AgentscopeApp.java       # main：装配 ModelConfig/AgentModels/MessageStore/ChatRunner + 启 Web/Ws
│   ├── config/Config.java       # token/port/skillsDir/workspaceDir/modelConfigFile/dbFile（env）
│   ├── config/ModelConfig.java  # 解析 ~/.dsh/model-config.json（activeId + profiles 多模型）
│   ├── agent/AgentModels.java   # 多模型 Agent 池：按 profile 惰性构建 HarnessAgent + 会话模型记忆 + 热加载
│   ├── agent/ChatRunner.java    # 运行编排：streamEvents→按 seq 原始入库 + 广播；断线不中断；HTTP 阻塞复用
│   ├── store/MessageStore.java  # SQLite 读写（session/event，sessionId 关联，seq 单调）
│   ├── agent/CustomPromptMiddleware.java # 按请求覆盖系统提示词 / 注入 skill 提示词
│   ├── web/WebServer.java       # 静态(no-cache)+/api/models+/api/agent/send+/api/sessions+/api/messages + token
│   └── ws/WsServer.java         # java-websocket：prompt/subscribe/cancel；帧含 seq/turn；基于 cookie 握手
├── src/main/resources/
│   ├── static/index.html        # 玻璃态科技风 UI（模型选择 + think/工具折叠 + Markdown 正文 + 连接灯）
│   └── static/app.js            # 单一 seq reducer：实时/重放/缓存同路径渲染
└── testcase/
    ├── events.mjs               # 英文输出：顺序/持久化/重放==实时/断线续播
    ├── e2e.ts                   # 历史：health/send/多轮记忆/401/WS
    └── run-e2e.sh
```

## 4. 核心 API 对接（AgentScope Java 2.0）

来自 agentscope-java README（`io.agentscope`）：

```java
// 从配置文件加载多模型（~/.dsh/model-config.json：activeId + profiles），热加载
// 每个 profile 直接构造 OpenAIChatModel（apiKey/baseUrl/model 均来自配置，走 OpenAI 兼容协议）
ModelConfig.Profile p = ...; // 按请求 model / 会话记忆 / active 解析
OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(p.apiKey)
    .baseUrl(p.baseUrl)        // 例：https://dashscope.aliyuncs.com/compatible-mode/v1
    .modelName(p.model)
    .stream(true)
    .build();

// 装配 agent（原生 HarnessAgent，AgentModels 惰性构建并按 profile 缓存）
HarnessAgent agent = HarnessAgent.builder()
    .name("agentscope-bot")
    .sysPrompt(systemPrompt)
    .model(model)                             // 直接注入 Model 实例
    .workspace(wsDir)
    .middleware(new CustomPromptMiddleware()) // 按请求覆盖 customSysPrompt / 注入 skillPrompt
    .build();

// 运行编排（ChatRunner）：streamEvents 的每个事件 → MessageStore 按 seq 原始入库 + 广播给在线端
RuntimeContext ctx = RuntimeContext.builder().sessionId(sid).userId(uid).build();
agent.streamEvents(new UserMessage(msg), ctx)
    .doOnNext(ev -> runner.mapAndEmit(run, ev))   // 先 store.append(seq) 再 listener.accept(frame)
    .subscribe();                                  // 订阅在后台线程，浏览器断开不影响
```

> 注：Toolkit 的 skills/mcp 构建器签名以 agentscope-java 实际 API 为准（编译期对齐，必要时 fetch 文档校准）。

## 5. Web 协议（HTTP REST）

| 方法 路径 | 作用 | 鉴权 |
|---|---|---|
| GET `/?token=xxx` | 换 cookie（同 dsh 握手） | token |
| GET `/api/agent/health` | 健康检查 | cookie |
| GET `/api/models` | 可用模型列表（{active, items:[{id,displayName,model}]}，前端下拉框） | cookie |
| POST `/api/agent/send` | 一次性对话（body: `{sessionId?,message,model?}` → `{sessionId,reply,model,tokens}`） | cookie |
| GET `/` | 静态 index.html | — |

> `model` 可选：匹配 profile 的 id/displayName/model/route；未指定则沿用会话上次模型（首次发送记住），新会话用 active。

## 6. WebSocket 协议（/ws/agent）

握手带 cookie（同源）。JSON 文本帧：

- C→S: `{"action":"prompt","sessionId":"s1","message":"..."}`
- C→S: `{"action":"cancel","sessionId":"s1"}`
- S→C: `{"event":"session|text|tool_call|tool_result|done|cancelled|error","sessionId":"s1","data":"..."}`
  - `text` = TextBlockDelta 累积
  - `tool_call`/`tool_result` = 工具调用/结果事件
  - `done` = 回合结束

## 7. Session 能力

- `SessionManager`：`ConcurrentHashMap<sessionId, SessionState>`，SessionState 持 RuntimeContext + 历史摘要。
- `sessionId` 由客户端传或缺省生成；`userId` 从 header `X-DSH-USERID` 取（缺省 `default`）。
- 内存态（重启丢）；agentscope 原生 `AgentStateStore` 可选 MySQL/Redis 扩展（本期不开）。

## 8. Skill / MCP

- Skill：`src/main/resources/skills/*.md`（agentscope skill 格式：frontmatter + 正文），`Toolkit.skills(dir)` 加载，agent 可发现并调用。
- MCP：`Toolkit.mcpServers(config)` 连接 stdio/SSE MCP server，其工具并入 agent Toolkit。配置走 env `DSH_MCP_SERVERS`（JSON，可选）。

## 9. Token 鉴权

- `DSH_TOKEN` env（默认固定值，便于测试）。
- web：`GET /?token=xxx` → set-cookie `dsh-auth=…`；后续 `/api/*` 校验 cookie。
- ws：握手带 cookie（`Cookie` header）。

## 9.1 多模型

- 配置文件：`~/.dsh/model-config.json`（env `DSH_MODEL_CONFIG` 可覆盖路径），包含 `activeId` + `profiles[]`（每个 profile 独立 `apiKey`/`baseUrl`/`model`）。
- `AgentModels` 为每个 profile 惰性构建一个 `HarnessAgent`（`DashScopeChatModel` 直接构造，不读 env）。
- 会话记忆：`sessionId → profile`（ConcurrentHashMap）。首次请求记住该会话模型；后续沿用；请求带新 `model` 则切换并覆盖记忆。

## 10. 脚本

- `build.sh`：`mvn -q clean package`，生成 `target/agentscope-*.jar` + classpath。
- `start.sh [port]`：同时开放 Web（`port`）+ WebSocket（`port+1`），启动日志打印两个端口，`java -cp … AgentscopeApp $PORT`。Windows 用 `start.bat`。

## 11. 测试（testcase，TypeScript）

> 用 TS 写，前端 `app.js`/`app.ts` 与测试共享同一套 client 库（HTTP/SSE + WS 调用逻辑复用）。

`testcase/` 结构：
```
testcase/
├── client.ts          # 共享 client：httpSend/httpHealth/wsPrompt（前端 + 测试复用）
├── e2e.ts             # 端到端用例（node 直跑，Node 22+ strip-types）
├── run-e2e.sh         # 包壳：确保服务起 + node testcase/e2e.ts
├── package.json       # 无运行时依赖（仅 Node 内置 fetch + WebSocket）
└── tsconfig.json
```
覆盖（带 token）：
1. `?token=` 握手换 cookie
2. `/api/agent/health` → ok
3. `/api/agent/send` → reply 非空 + token > 0
4. 多轮记忆（同 sessionId 记住→回忆）
5. skill.list（agent 发现 code-review skill）
6. WS：`prompt` → 收到 session/text/done 帧
7. WS：`cancel` → cancelled
8. 无 token → 401

跑法：`bash testcase/run-e2e.sh`（确保 web/ws 服务就绪 + `node testcase/e2e.ts`）。

## 12. 交付

- `DESIGN.md`（本文件）/ `DEPLOY.md`（部署）/ `README.md`
- 可运行工程（build + start）
- e2e 通过
