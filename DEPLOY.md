# AgentScope 独立工程部署文档

## 1. 概述

基于阿里 AgentScope Java（`io.agentscope:agentscope-harness:2.0.1`）的独立智能体服务，提供 Web HTTP + WebSocket 双通道访问。不依赖 dsh-java 任何包。

### 能力清单
- ✅ Web HTTP REST + 静态 HTML/JS 交互界面（玻璃态科技风，含模型选择、think/工具折叠、Markdown 渲染）
- ✅ WebSocket 流式（think + 工具调用 + 正文分段实时下发，与 Web 同时开放）
- ✅ 多模型：读取 `~/.dsh/model-config.json`，请求带 `model` id 切换，会话记忆；配置热加载
- ✅ 事件持久化：SQLite（`db/sqlite/schema.sql`），按 `session_id` 关联，原始事件不加工入库
- ✅ 刷新不中断：后端持有 run，断线继续产出；`/api/messages?after=seq` 重放 + WS `subscribe` 续播，顺序与实时一致
- ✅ session 隔离（agentscope 原生 AgentStateStore + 本工程 SQLite 会话/事件表）
- ✅ 系统提示词注入（`.sysPrompt(...)`）；按请求可覆盖
- ✅ 用户 skill（`workspace/skills/*.md`，agentscope 原生自动加载）
- ✅ token 鉴权（环境变量 `DSH_TOKEN`，Web `?token=` 握手换 cookie；WS 基于 cookie 握手）
- ✅ LLM：OpenAI 兼容模式（`agentscope-extensions-model-openai`），apiKey/baseUrl/model 均来自配置文件

## 2. 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | AgentScope Java 最低要求 |
| Maven | 3.9+ | 构建 |
| Node.js | 22+ | 运行 testcase（e2e.ts / events.mjs） |
| SQLite | xerial sqlite-jdbc 3.46+ | 由 Maven 依赖自动引入，无需单独安装 |
| DashScope/LLM Key | — | 写入 `~/.dsh/model-config.json`（model 配置，非环境变量） |

## 3. 构建与启动

### 3.1 编译

```bash
cd agentscope
bash scripts/build.sh   # mvn clean package → target/agentscope-1.0.0.jar
```

### 3.2 启动（同时开放 Web + WebSocket）

```bash
export DSH_TOKEN="my-secret-token"
bash scripts/start.sh 8766
# 启动日志打印两个端口：
#   Web: http://localhost:8766/?token=my-secret-token
#   WS:  ws://localhost:8767/ws/agent
```

### 3.3 模型配置（~/.dsh/model-config.json）

```json
{
  "activeId": "qwen37max",
  "profiles": [
    {
      "id": "glm",
      "displayName": "glm",
      "apiKey": "sk-...",
      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "model": "glm-5.2"
    },
    {
      "id": "qwen37max",
      "displayName": "qwen3.7-max",
      "apiKey": "sk-...",
      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "model": "qwen3.7-max"
    }
  ]
}
```

选择模型：请求 body 可带 `model`（匹配 profile 的 `id`/`displayName`/`model`/`route`）。
未指定时沿用会话上次使用的模型；新会话用 `activeId` 对应的 profile。

### 3.4 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `DSH_TOKEN` | `agentscope-default-token` | 访问令牌（授权 key，仍需环境变量） |
| `DSH_MODEL_CONFIG` | `~/.dsh/model-config.json` | 模型配置文件（apiKey/baseUrl/model），热加载 |
| `DSH_PORT` | `8766` | HTTP 端口（WS 自动 = HTTP + 1） |
| `DSH_DB` | `.agentscope/agentscope.db` | SQLite 文件（会话/事件持久化） |
| `DSH_WORKSPACE` | `.agentscope/workspace` | 工作区目录（skill/记忆/session） |
| `DSH_SYS_PROMPT` | 默认提示词 | 系统提示词注入 |
| `DSH_SKILLS_DIR` | `src/main/resources/skills` | 技能目录 |

## 4. 架构

```
┌───────────────────────────────────────────────┐
│                 AgentscopeApp (main)           │
│  ┌─────────────────────────────────────────┐  │
│  │ AgentModels (多模型池, profile→Agent 惰性)│  │
│  │ ModelConfig(~/.dsh/model-config.json)    │  │
│  └─────────────────────────────────────────┘  │
│  ┌─────────────────────────────────────────┐  │
│  │ ChatRunner (run 归后端所有)              │  │
│  │  streamEvents → 原始事件按 seq 入库+广播 │  │
│  │  浏览器断开仍继续产出；可 attach/detach   │  │
│  └─────────────────────────────────────────┘  │
│  ┌───────────────┐   ┌─────────────────────┐  │
│  │ WebServer     │   │ WsServer (始终启动)  │  │
│  │ /api/models   │   │ /ws/agent            │  │
│  │ /api/agent/send│  │ prompt / subscribe /  │  │
│  │ /api/sessions │   │ cancel（基于 cookie） │  │
│  │ /api/messages │   └─────────────────────┘  │
│  │ 静态 UI(no-cache)│                         │
│  └───────────────┘                            │
│        ┌──────────────────────────────────┐   │
│        │ MessageStore → SQLite (db/sqlite) │   │
│        │ session / event(seq, 原始 JSON)    │   │
│        └──────────────────────────────────┘   │
│           token 鉴权（DSH_TOKEN env）          │
└───────────────────────────────────────────────┘
```

数据流：一次 prompt 产生的每个事件（thinking/delta/tool_*/done）都由 `ChatRunner.emit` **先入库拿到 seq，再广播**给在线监听器；前端只有**一条** `applyEvent(seq 归约)→renderTurn` 渲染路径，实时、刷新重放、缓存渲染三者共用 ⇒ 顺序永远一致。

## 5. 前端界面

- **Token 验证页**：输入 token 或 URL 带 `?token=`（先握手换 cookie，再健康检查校验，失败留在验证页）
- **布局**：玻璃态科技风，左侧对话列表 + 右侧聊天区 + 输入框（模型下拉 + 连接状态灯）
- **think / 工具**：思考与工具调用为默认折叠的披露行（单行摘要 + running 微光 + 展开看输入/输出），按到达顺序穿插在正文之间
- **正文 Markdown**：标题/列表/代码块/行内码/链接渲染，先转义再变换防 XSS
- **顺序一致**：事件按 `seq` 归约，实时输出与刷新重放顺序完全一致
- **缓存策略**：会话完成后才写 localStorage(done)；未完成会话仅按 `lastSeq` 增量拉取 `/api/messages`，避免每次全量；重连后对未完成且后端仍在跑的会话 `subscribe` 续播

## 6. 测试

```bash
# 确保 web/ws 服务已启动（scripts/start.sh 8766）
export DSH_TOKEN="my-secret-token"
# 事件流专项（英文输出）：顺序/持久化/重放==实时/断线续播
node testcase/events.mjs
# 历史 e2e（health/send/记忆/401/WS）
DSH_TOKEN=xxx DSH_PORT=8766 node testcase/e2e.ts
```

`events.mjs` 覆盖：无 cookie 拒绝；live 事件 seq 单调；think/tool/delta/done 齐全；`/api/messages` 与实时序列逐条相等（原始 payload 未改）；中途断开后 `subscribe` 续播至 done，最终 seq 连续 1..N 无空洞；`/api/sessions` 标记 done。

## 7. MCP 扩展

在 `AgentscopeApp.java` 中添加 MCP client（需额外 MCP SDK 依赖）：

```java
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;

Toolkit toolkit = new Toolkit();
toolkit.registerMcpClient(
    McpClientBuilder.stdio().name("filesystem")
        .command("npx").args("-y", "@anthropic/mcp-filesystem").build()
).block();

HarnessAgent agent = HarnessAgent.builder()
    .name("agentscope-bot").sysPrompt(...).model(...)
    .workspace(...).toolkit(toolkit).build();
```

## 8. Session 持久化

agentscope 原生 `JsonFileAgentStateStore` 自动持久化到 `~/.agentscope/state/<agentId>/<userId>/<sessionId>/agent_state.json`。重启后同 `(userId, sessionId)` 自动恢复对话。生产环境可换 `RedisAgentStateStore` 或 `MySQLAgentStateStore`。
