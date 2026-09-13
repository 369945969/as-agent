# AgentScope 独立工程

基于阿里 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java)（`io.agentscope:agentscope-harness:2.0.1`）的独立 Web/WS 智能体服务。不依赖 dsh-java 任何包。

## 快速开始

```bash
# 1. 编译
cd agentscope
bash scripts/build.sh

# 2. 设置环境变量（模型/apiKey 从配置读取，这里只需授权 token）
export DSH_TOKEN="your-token"

# 3. 启动（同时开放 Web HTTP + WebSocket 流式两个端口，日志打印端口）
bash scripts/start.sh 8766

# 4. 打开浏览器
# Web:  http://localhost:8766/?token=your-token
# WS:   ws://localhost:8767/ws/agent

# 5. 运行测试（英文输出，验证 think/工具/顺序/持久化/断线重放）
DSH_TOKEN=your-token DSH_PORT=8766 node testcase/events.mjs
```

## 能力

| 能力 | 实现 |
|---|---|
| Web HTTP REST | JDK HttpServer（/api/agent/send, /api/agent/health, /api/models, /api/sessions, /api/messages） |
| WebSocket 流式 | java-websocket（/ws/agent, prompt/subscribe/cancel；think + tool + 正文分段实时下发） |
| 多模型 | 读取 `~/.dsh/model-config.json`（activeId + profiles），请求可带 `model` id：首次发送记住、后续沿用、切换指定新模型；配置热加载 |
| 事件持久化 | SQLite（`db/sqlite/schema.sql`）按 `session_id` 关联，原始事件不加工入库；`seq` 记录到达顺序 |
| 刷新不中断 | 后端拥有 run，浏览器断开仍继续产出；刷新用 `/api/messages?after=seq` 重放 + WS `subscribe` 续播，顺序与实时一致 |
| 前端渲染 | 单一按 seq 归约的 reducer（实时/重放/缓存同一路径）；think/工具折叠披露行；正文 Markdown 渲染 |
| 浏览器缓存 | 会话完成后写 localStorage；未完成会话仅按 lastSeq 增量拉取，避免每次全量拖库 |
| Session | agentscope 原生 RuntimeContext + AgentStateStore（对话记忆持久化/恢复） |
| 系统提示词 | HarnessAgent.sysPrompt()；中间件支持按请求覆盖 |
| 用户 Skill | workspace/skills/*.md（agentscope 自动加载） |
| Token 鉴权 | env DSH_TOKEN, ?token=→cookie；WS 基于登录 cookie 握手 |
| LLM | OpenAI 兼容模式（agentscope-extensions-model-openai），apiKey/baseUrl/model 均来自配置文件 |
| 前端 UI | 玻璃态科技风（token 验证页 + 模型选择 + 左对话列表 + 右聊天框） |

## 文档

- [DESIGN.md](DESIGN.md) — 设计文档
- [DEPLOY.md](DEPLOY.md) — 部署文档
