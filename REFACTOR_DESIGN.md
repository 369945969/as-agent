# AgentScope Web 界面重构设计

> 基于：现有 `app.js` / `index.html` / `WebServer.java` / `WsServer.java` / `ChatRunner.java` / `MessageStore.java`
> HarnessAgent API（agentscope-harness 2.0.1）已确认：Toolkit / skillRepository / MCP / middleware / streamEvents

---

## 一、整体布局重构

```
┌────────────────────────────────────────────────────────────────────────┐
│  顶栏：Logo · WS状态灯 · 折叠按钮                                       │
├──────────────┬─────────────────────────────────────────────────────────┤
│              │                                                         │
│  左侧功能菜单  │                    右侧聊天区                            │
│  (可收拢)     │                                                         │
│              │  ┌─────────────────────────────────────────────────┐    │
│  ▸ 智能体管理  │  │                                                 │    │
│    ▸ 智能体列表 │  │            聊天消息区（滚动）                      │    │
│    ▸ Skill管理  │  │   用户气泡 / 助手气泡 / 思考 / 工具 / 注入事件      │    │
│    ▸ API管理   │  │                                                 │    │
│    ▸ MCP管理   │  │                                                 │    │
│  ▸ 对话历史    │  └─────────────────────────────────────────────────┘    │
│              │  ┌─────────────────────────────────────────────────┐    │
│              │  │  ┌──────────────────────────────────┐  ┌──┐  │    │
│              │  │  │  输入框（加高 120px）                │  │发│  │    │
│              │  │  │  模型选择器在输入框内左下角           │  │送│  │    │
│              │  │  │  智能体标签在输入框内右下角           │  └──┘  │    │
│              │  │  └──────────────────────────────────┘         │    │
│              │  └─────────────────────────────────────────────────┘    │
├──────────────┴─────────────────────────────────────────────────────────┤
│  底栏：当前智能体 · 当前模型 · token                                    │
└────────────────────────────────────────────────────────────────────────┘
```

### 布局规则
- 左侧菜单默认 280px 宽，可折叠至 0（仅留图标条 48px）
- 聊天区 flex:1 自适应
- 输入框紧贴聊天区底部，模型选择器 + 智能体标签嵌入输入框内部底栏

---

## 二、左侧功能菜单

### 2.1 菜单结构

```
左侧功能菜单
├── 智能体管理
│   ├── 智能体列表（卡片式，每张卡：图标+标题+模型标签）
│   ├── [+ 新建智能体] 按钮
│   └── 智能体编辑面板（抽屉/模态框）
│       ├── 标题
│       ├── 描述
│       ├── 系统提示词（带 ✨AI生成 按钮）
│       ├── 绑定模型（下拉，来自 /api/models）
│       ├── 绑定 Skill（多选，来自 Skill 管理）
│       ├── 绑定 数据服务API（多选，来自 API 管理）
│       ├── 绑定 MCP（多选，来自 MCP 管理）
│       └── [保存] [取消]
│
├── Skill 管理
│   ├── Skill 列表（表格：名称/来源/状态）
│   ├── [+ 导入 Skill] 按钮（从目录加载 或 手动输入）
│   ├── Skill 编辑（名称、描述、内容 .md frontmatter）
│   └── 语义绑定：Skill 的调用触发条件（自然语言描述何时调用）
│
├── 数据服务 API 管理
│   ├── API 列表（表格：名称/Method/URL/状态）
│   ├── [+ 新建 API] 按钮
│   ├── API 编辑面板
│   │   ├── 基本信息名称、Method、URL、Headers
│   │   ├── 输入参数定义（name/type/required/description）
│   │   ├── 输出参数定义（name/type/description）
│   │   └── 语义绑定
│   │       ├── 语义描述（这个API做什么，自然语言）
│   │       ├── 输入语义（每个参数用自然语言描述语义）
│   │       └── 输出语义（返回值的语义解释，供 Agent 理解）
│   └── [测试] 按钮（发送实际请求验证）
│
├── MCP 管理
│   ├── MCP Server 列表（表格：名称/传输方式/状态）
│   ├── [+ 新建 MCP] 按钮
│   ├── MCP 编辑面板
│   │   ├── 基本信息名称、传输方式(stdio/sse)、command/url、args、env
│   │   └── 语义绑定
│   │       ├── 语义描述（这个MCP server提供什么能力）
│   │       └── 工具语义映射（自动拉取 tools/list → 每个工具自然语言描述）
│   └── [连接测试] 按钮
│
└── 对话历史
    └── 会话列表（现有逻辑，迁移）
```

### 2.2 智能体数据模型

```json
{
  "id": "agent-001",
  "title": "代码审查助手",
  "description": "专注 Java 代码审查，提供安全/性能/规范建议",
  "systemPrompt": "你是一个专业的代码审查助手...",
  "modelProfileId": "glm-5.2",
  "skills": ["skill-001", "skill-002"],
  "dataApis": ["api-001", "api-002"],
  "mcps": ["mcp-001"],
  "icon": "🔍",
  "createdAt": 1234567890,
  "updatedAt": 1234567890
}
```

### 2.3 Skill 数据模型

```json
{
  "id": "skill-001",
  "name": "code-review",
  "description": "审查代码并给出改进建议",
  "source": "file",          // file | manual
  "filePath": "skills/code-review.md",
  "content": "---\nname: code-review\n---\n...",  // .md frontmatter + 正文
  "semanticBinding": "当用户请求审查代码、检查代码质量时调用此技能",
  "status": "active"
}
```

### 2.4 数据服务 API 数据模型

```json
{
  "id": "api-001",
  "name": "查询天气",
  "method": "GET",
  "url": "https://api.weather.com/v1/current",
  "headers": { "Authorization": "Bearer xxx" },
  "inputs": [
    { "name": "city", "type": "string", "required": true, "description": "城市名称" }
  ],
  "outputs": [
    { "name": "temperature", "type": "number", "description": "当前温度（摄氏度）" },
    { "name": "humidity", "type": "number", "description": "湿度百分比" }
  ],
  "semanticBinding": {
    "summary": "查询指定城市的实时天气数据",
    "inputSemantics": {
      "city": "用户想查询天气的城市，支持中英文"
    },
    "outputSemantics": {
      "temperature": "当前气温，单位摄氏度，可用于建议穿衣",
      "humidity": "空气湿度百分比，高于80%表示潮湿"
    }
  }
}
```

### 2.5 MCP 数据模型

```json
{
  "id": "mcp-001",
  "name": "filesystem",
  "transport": "stdio",     // stdio | sse
  "command": "npx",
  "args": ["-y", "@anthropic/mcp-filesystem"],
  "env": {},
  "semanticBinding": {
    "summary": "提供文件系统读写能力",
    "toolSemantics": {
      "read_file": "读取指定路径文件内容",
      "write_file": "向指定路径写入内容"
    }
  },
  "status": "connected"
}
```

---

## 三、后端扩展

### 3.1 新增 SQLite 表

```sql
-- 智能体表
CREATE TABLE IF NOT EXISTS agent (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    system_prompt TEXT NOT NULL DEFAULT '',
    model_profile_id TEXT NOT NULL DEFAULT '',
    icon        TEXT NOT NULL DEFAULT '🤖',
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

-- 智能体绑定关系表（skill / api / mcp）
CREATE TABLE IF NOT EXISTS agent_binding (
    agent_id    TEXT NOT NULL,
    binding_type TEXT NOT NULL,  -- skill | api | mcp
    target_id   TEXT NOT NULL,
    PRIMARY KEY (agent_id, binding_type, target_id)
);

-- Skill 表
CREATE TABLE IF NOT EXISTS skill (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL DEFAULT '',
    source      TEXT NOT NULL DEFAULT 'manual',
    file_path   TEXT,
    content     TEXT NOT NULL DEFAULT '',
    semantic_binding TEXT NOT NULL DEFAULT '',
    status      TEXT NOT NULL DEFAULT 'active',
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

-- 数据服务 API 表
CREATE TABLE IF NOT EXISTS data_api (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    method      TEXT NOT NULL DEFAULT 'GET',
    url         TEXT NOT NULL,
    headers     TEXT NOT NULL DEFAULT '{}',     -- JSON
    inputs      TEXT NOT NULL DEFAULT '[]',     -- JSON array
    outputs     TEXT NOT NULL DEFAULT '[]',     -- JSON array
    semantic_binding TEXT NOT NULL DEFAULT '{}', -- JSON
    status      TEXT NOT NULL DEFAULT 'active',
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

-- MCP 表
CREATE TABLE IF NOT EXISTS mcp_server (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    transport   TEXT NOT NULL DEFAULT 'stdio',  -- stdio | sse
    command     TEXT,
    args        TEXT NOT NULL DEFAULT '[]',     -- JSON array
    env         TEXT NOT NULL DEFAULT '{}',     -- JSON
    url         TEXT,                            -- for sse
    semantic_binding TEXT NOT NULL DEFAULT '{}', -- JSON
    status      TEXT NOT NULL DEFAULT 'inactive',
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);
```

### 3.2 新增 REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| **智能体** | | |
| GET | `/api/agents` | 智能体列表 |
| GET | `/api/agents/{id}` | 智能体详情（含绑定） |
| POST | `/api/agents` | 创建智能体 |
| PUT | `/api/agents/{id}` | 更新智能体 |
| DELETE | `/api/agents/{id}` | 删除智能体 |
| POST | `/api/agents/{id}/bindings` | 更新绑定（skill/api/mcp） |
| POST | `/api/agents/generate-prompt` | AI 生成系统提示词 |
| **Skill** | | |
| GET | `/api/skills` | Skill 列表 |
| POST | `/api/skills` | 创建 Skill |
| PUT | `/api/skills/{id}` | 更新 Skill |
| DELETE | `/api/skills/{id}` | 删除 Skill |
| POST | `/api/skills/import` | 从目录导入 |
| **数据服务 API** | | |
| GET | `/api/data-apis` | API 列表 |
| POST | `/api/data-apis` | 创建 API |
| PUT | `/api/data-apis/{id}` | 更新 API |
| DELETE | `/api/data-apis/{id}` | 删除 API |
| POST | `/api/data-apis/{id}/test` | 测试 API 调用 |
| **MCP** | | |
| GET | `/api/mcps` | MCP 列表 |
| POST | `/api/mcps` | 创建 MCP |
| PUT | `/api/mcps/{id}` | 更新 MCP |
| DELETE | `/api/mcps/{id}` | 删除 MCP |
| POST | `/api/mcps/{id}/test` | 测试 MCP 连接 |

### 3.3 新增 Java 类

```
src/main/java/com/agentscope/
├── store/
│   └── AgentStore.java          # 智能体/Skill/API/MCP 的 CRUD
├── agent/
│   ├── AgentManager.java        # 智能体管理：根据 Agent 配置动态构建 HarnessAgent
│   └── ContextInjectionMiddleware.java  # 上下文注入中间件
├── api/
│   ├── DataApiTool.java         # 将数据服务API注册为 Agent 工具
│   └── DataApiExecutor.java     # 执行 HTTP 调用
├── mcp/
│   └── McpManager.java          # MCP server 连接管理
└── web/
    └── AgentApiHandler.java     # 智能体 CRUD HTTP handler
```

### 3.4 智能体 → HarnessAgent 动态构建

```java
// AgentManager.java
public HarnessAgent buildAgent(AgentConfig agentConfig) {
    // 1) 解析模型
    ModelConfig.Profile profile = models.resolveProfile(null, agentConfig.modelProfileId);
    OpenAIChatModel model = OpenAIChatModel.builder()
        .apiKey(profile.apiKey).baseUrl(profile.baseUrl)
        .modelName(profile.model).stream(true).build();

    // 2) 构建 Toolkit
    Toolkit toolkit = new Toolkit();
    // 2a) 注册绑定的 Skill
    for (SkillConfig skill : agentConfig.skills) {
        // 写入 workspace/skills/ 目录，agent 原生自动发现
    }
    // 2b) 注册绑定的数据服务 API 为工具
    for (DataApiConfig api : agentConfig.dataApis) {
        toolkit.registerTool(new DataApiTool(api));  // 自定义 AgentTool
    }
    // 2c) 注册绑定的 MCP
    for (McpConfig mcp : agentConfig.mcps) {
        McpClientWrapper client = McpClientBuilder.stdio()
            .command(mcp.command).args(mcp.args).build();
        toolkit.registerMcpClient(client).block();
    }

    // 3) 构建 Agent
    return HarnessAgent.builder()
        .name(agentConfig.title)
        .description(agentConfig.description)
        .sysPrompt(agentConfig.systemPrompt)
        .model(model)
        .workspace(workspaceDir)
        .toolkit(toolkit)
        .middleware(new ContextInjectionMiddleware(agentConfig))  // 注入上下文事件
        .build();
}
```

---

## 四、上下文注入事件（聊天框内展示）

### 4.1 注入时机

用户在聊天中绑定了一个智能体后，**第一次发送消息**时，后端在 `streamEvents` 之前依次注入：

```
1. context_injection  — 系统提示词注入
2. skill_injection    — 绑定的 Skill 注入
3. mcp_injection      — 绑定的 MCP 工具注入
4. api_injection      — 绑定的数据服务 API 注入
5. user_message       — 用户消息（正常流程）
6. thinking / delta / tool_call / done — 正常 agent 事件流
```

### 4.2 注入事件帧格式

```json
// context_injection
{"event":"context_injection","sessionId":"...","turn":1,"seq":1,
 "data":{"type":"system_prompt","summary":"系统提示词已注入","detail":"你是一个..."}}

// skill_injection
{"event":"skill_injection","sessionId":"...","turn":1,"seq":2,
 "data":{"skills":["code-review","security-check"],"summary":"已注入2个技能"}}

// mcp_injection
{"event":"mcp_injection","sessionId":"...","turn":1,"seq":3,
 "data":{"mcps":[{"name":"filesystem","tools":["read_file","write_file"]}],"summary":"已连接1个MCP服务"}}

// api_injection
{"event":"api_injection","sessionId":"...","turn":1,"seq":4,
 "data":{"apis":[{"name":"查询天气","method":"GET","url":"https://..."}],"summary":"已注入1个数据服务API"}}
```

### 4.3 前端渲染

注入事件在聊天框中渲染为**折叠披露行**（与思考/工具调用类似）：

```
┌─ 📋 上下文注入 ──────────── 系统提示词已注入 ────────── ▸ ─┐
│  你是一个专业的代码审查助手...                                │
└────────────────────────────────────────────────────────────┘
┌─ 🔧 技能注入 ──────── code-review, security-check ──── ▸ ─┐
│  • code-review: 审查代码并给出改进建议                         │
│  • security-check: 安全漏洞检测                              │
└────────────────────────────────────────────────────────────┘
┌─ 🔌 MCP 注入 ─────── filesystem (2个工具) ─────────── ▸ ─┐
│  • read_file: 读取指定路径文件内容                            │
│  • write_file: 向指定路径写入内容                             │
└────────────────────────────────────────────────────────────┘
┌─ 🌐 API 注入 ─────── 查询天气 (GET) ─────────────── ▸ ─┐
│  • 输入: city (string, required)                            │
│  • 输出: temperature (number), humidity (number)             │
└────────────────────────────────────────────────────────────┘
```

### 4.4 ChatRunner 改造

```java
// ChatRunner.start0() — 在 agent.streamEvents 之前注入上下文
if (agentConfig != null) {
    // 注入系统提示词
    emit(run, "context_injection", Map.of(
        "type", "system_prompt",
        "summary", "系统提示词已注入",
        "detail", agentConfig.systemPrompt));

    // 注入 Skill
    if (!agentConfig.skills.isEmpty())
        emit(run, "skill_injection", Map.of(
            "skills", agentConfig.skillNames,
            "summary", "已注入" + agentConfig.skills.size() + "个技能"));

    // 注入 MCP
    if (!agentConfig.mcps.isEmpty())
        emit(run, "mcp_injection", Map.of(
            "mcps", agentConfig.mcpSummaries,
            "summary", "已连接" + agentConfig.mcps.size() + "个MCP服务"));

    // 注入 API
    if (!agentConfig.dataApis.isEmpty())
        emit(run, "api_injection", Map.of(
            "apis", agentConfig.apiSummaries,
            "summary", "已注入" + agentConfig.dataApis.size() + "个数据服务API"));
}
// 然后正常流程
emit(run, "user_message", Map.of("text", message));
agent.streamEvents(new UserMessage(message), ctx)...
```

### 4.5 前端 applyEvent 扩展

```javascript
case 'context_injection': {
    turn.parts.push({ type: 'injection', injectType: 'context', ...d });
    break;
}
case 'skill_injection': {
    turn.parts.push({ type: 'injection', injectType: 'skill', ...d });
    break;
}
case 'mcp_injection': {
    turn.parts.push({ type: 'injection', injectType: 'mcp', ...d });
    break;
}
case 'api_injection': {
    turn.parts.push({ type: 'injection', injectType: 'api', ...d });
    break;
}
```

---

## 五、聊天区 + 输入框重构

### 5.1 输入框设计（参考主流 LLM 对话产品）

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  [textarea — 高度 120px，可自动扩展至 240px]                    │
│  Shift+Enter 换行，Enter 发送                                  │
│                                                             │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  [🤖 代码审查助手 ▾]  [qwen3.7-max ▾]              [发送 →]    │
│   智能体标签           模型选择器               发送按钮         │
└─────────────────────────────────────────────────────────────┘
```

- 输入框最小高度 120px（当前 46px），最大 240px
- 模型选择器和智能体标签在输入框**内部底栏**
- 发送按钮也在输入框**内部底栏**右侧
- 整体一个玻璃态圆角容器包裹

### 5.2 智能体选择

- 输入框内底栏左侧显示当前智能体标签（可点击切换）
- 下拉显示所有智能体 + "默认助手" 选项
- 选择智能体后，发送消息时携带 `agentId` 参数

### 5.3 WS prompt 协议扩展

```json
{
  "action": "prompt",
  "sessionId": "...",
  "message": "...",
  "userId": "web-user",
  "model": "qwen3.7-max",
  "agentId": "agent-001"
}
```

后端 `WsServer.onMessage` 和 `WebServer.SendHandler` 读取 `agentId`，
`ChatRunner.start0()` 根据 `agentId` 从 `AgentManager` 获取智能体配置，
构建（或从缓存取）对应的 `HarnessAgent`，并在 `streamEvents` 前注入上下文事件。

---

## 六、AI 提示词生成

### 6.1 交互

智能体编辑面板中，系统提示词输入框旁有 ✨ 按钮：
1. 用户填写标题 + 描述
2. 点击 ✨
3. 后端调用当前 active 模型，生成专业提示词
4. 填入输入框（用户可继续编辑）

### 6.2 后端

```
POST /api/agents/generate-prompt
Body: { "title": "代码审查助手", "description": "专注Java代码审查" }
Response: { "prompt": "你是一个专业的代码审查助手..." }
```

后端用 `AgentModels.active()` 获取模型，调用 `agent.call()` 生成。
Prompt 模板：

```
你是一个提示词工程师。根据以下信息生成一个专业的系统提示词：

标题：{title}
描述：{description}

要求：
1. 提示词应明确角色定位、能力边界、输出格式
2. 使用中文
3. 直接输出提示词内容，不要解释

生成的系统提示词：
```

---

## 七、语义绑定设计

### 7.1 数据服务 API 语义绑定

将 API 注册为 Agent 工具时，语义绑定转换为 **Tool Schema description**：

```java
// DataApiTool.java
public ToolSchema toSchema() {
    String description = semanticBinding.summary
        + "\n\n输入参数："
        + inputs.stream().map(i -> "- " + i.name + " (" + i.type + "): " + i.description
            + " 语义: " + semanticBinding.inputSemantics.get(i.name)).collect(joining("\n"))
        + "\n\n输出："
        + outputs.stream().map(o -> "- " + o.name + " (" + o.type + "): "
            + semanticBinding.outputSemantics.get(o.name)).collect(joining("\n"));

    return ToolSchema.builder()
        .name("data_api_" + api.name)
        .description(description)
        .inputSchema(api.toJsonSchema())  // 从 inputs 生成 JSON Schema
        .build();
}
```

### 7.2 MCP 语义绑定

MCP 连接后自动拉取 `tools/list`，语义绑定补充到每个工具的 description：

```java
// McpManager.java
public Mono<Void> connect(McpConfig mcp) {
    McpClientWrapper client = buildClient(mcp);
    return toolkit.registerMcpClient(client)
        .then(Mono.fromRunnable(() -> {
            // 拉取工具列表，应用语义绑定
            for (ToolSchema tool : toolkit.getToolSchemas()) {
                String semantic = mcp.semanticBinding.toolSemantics.get(tool.getName());
                if (semantic != null) {
                    tool.enhanceDescription(semantic);  // 追加语义描述
                }
            }
        }));
}
```

---

## 八、实施计划

### Phase 1：后端基础设施（预计 3-4 小时）

| # | 任务 | 涉及文件 |
|---|---|---|
| 1.1 | SQLite 新表 DDL | `db/sqlite/schema.sql` |
| 1.2 | `AgentStore.java` — 智能体/Skill/API/MCP 的 CRUD | 新建 |
| 1.3 | `AgentManager.java` — 根据智能体配置动态构建 HarnessAgent | 新建 |
| 1.4 | `ContextInjectionMiddleware.java` — 上下文注入中间件 | 新建 |
| 1.5 | `DataApiTool.java` / `DataApiExecutor.java` — API→工具 | 新建 |
| 1.6 | `McpManager.java` — MCP 连接管理 | 新建 |
| 1.7 | `ChatRunner.java` — 改造支持 agentId + 注入事件 | 修改 |
| 1.8 | `WebServer.java` — 新增 REST API 路由 | 修改 |
| 1.9 | `WsServer.java` — prompt 读取 agentId | 修改 |

### Phase 2：前端布局重构（预计 4-5 小时）

| # | 任务 | 涉及文件 |
|---|---|---|
| 2.1 | 整体布局重构（三栏 → 顶栏+左侧菜单+右侧聊天） | `index.html` `app.js` |
| 2.2 | 左侧功能菜单组件（可折叠、树形导航） | `app.js` |
| 2.3 | 智能体管理 UI（列表+编辑面板） | `app.js` |
| 2.4 | Skill 管理 UI | `app.js` |
| 2.5 | 数据服务 API 管理 UI（含语义绑定） | `app.js` |
| 2.6 | MCP 管理 UI（含语义绑定） | `app.js` |
| 2.7 | 聊天区 + 输入框重构（模型选择器/智能体标签嵌入） | `app.js` |
| 2.8 | 注入事件渲染（context/skill/mcp/api injection 折叠行） | `app.js` |
| 2.9 | AI 提示词生成按钮 | `app.js` |

### Phase 3：联调与测试（预计 1-2 小时）

| # | 任务 |
|---|---|
| 3.1 | 智能体 CRUD 端到端 |
| 3.2 | 绑定 Skill/API/MCP 后首次发送消息，注入事件正确展示 |
| 3.3 | 数据服务 API 工具调用（Agent 调用 API 工具，返回结果） |
| 3.4 | MCP 工具调用 |
| 3.5 | AI 提示词生成 |
| 3.6 | 页面刷新后注入事件重放一致 |

---

## 九、文件清单

### 新建文件
```
src/main/java/com/agentscope/
├── store/AgentStore.java
├── agent/AgentManager.java
├── agent/AgentConfig.java              # 智能体配置数据类
├── agent/ContextInjectionMiddleware.java
├── api/DataApiTool.java
├── api/DataApiExecutor.java
├── mcp/McpManager.java
└── web/AgentApiHandler.java             # 智能体/Skill/API/MCP REST handler
```

### 修改文件
```
db/sqlite/schema.sql                     # +5 新表
src/main/java/com/agentscope/
├── store/MessageStore.java              # 引用新 DDL（或拆分到 AgentStore）
├── agent/ChatRunner.java                # agentId 支持 + 注入事件
├── agent/AgentModels.java               # 按 agentId 构建（委托 AgentManager）
├── web/WebServer.java                   # 新增 REST 路由
├── ws/WsServer.java                     # prompt 读取 agentId
└── AgentscopeApp.java                   # 装配 AgentStore/AgentManager/McpManager
src/main/resources/static/
├── index.html                           # 布局重构
└── app.js                               # 前端全面重构
```
