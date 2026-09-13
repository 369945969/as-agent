-- =============================================================================
-- agentscope 独立工程 · SQLite 表结构
-- 用途：把聊天事件流（think / tool / 正文 / done 等）以「原始未修改」的事件 JSON
--       持久化到 DB，供前端按到达顺序重放渲染；页面刷新不中断后端输出。
-- 文件：db/sqlite/schema.sql（构建时经 maven-resources 复制到 classpath: db/）
-- 扩展：后续如需 MySQL，新建 db/mysql/schema.sql 并在 MessageStore 增加方言支持。
-- 关联：所有表以 session_id 关联（会话记忆/历史查询的 join key）。
-- 顺序：events.id 为全局单调自增 = 事件到达顺序（重放按 id ASC 即得严格时间线）；
--       events.seq 为会话内单调序号（客户端增量拉取用 after=<lastSeq>）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. session —— 会话元数据（sessionId 关联主表）
--    title：首条用户消息前 32 字符自动生成；model：当前选用模型 profileId；
--    status：running=后端仍在产出（刷新后需续订），done/error=已结束。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS session (
    session_id   TEXT    PRIMARY KEY,                -- 会话 ID（前端 localStorage 对话 id 一致）
    user_id      TEXT    NOT NULL DEFAULT '',        -- 用户标识（多用户隔离）
    model        TEXT    NOT NULL DEFAULT '',        -- 会话当前模型 profile id
    title        TEXT    NOT NULL DEFAULT '',        -- 会话标题（首消息生成）
    status       TEXT    NOT NULL DEFAULT 'running', -- running | done | error
    agent_id     TEXT    NOT NULL DEFAULT '',        -- 关联的智能体 ID（空=默认助手）
    created_at   INTEGER NOT NULL,                   -- 毫秒时间戳
    updated_at   INTEGER NOT NULL                    -- 毫秒时间戳
);

CREATE INDEX IF NOT EXISTS idx_session_user ON session(user_id, updated_at DESC);

-- -----------------------------------------------------------------------------
-- 2. event —— 原始事件流（一行一个事件；payload 为后端下发的原始 JSON，不加工）
--    event_type：user_message | session | model | thinking_start | thinking |
--                delta | tool_call_start | tool_call_delta | tool_result_start |
--                tool_result_delta | tool_result_end | done | error | cancelled |
--                context_injection | skill_injection | mcp_injection | api_injection
--    data：原始事件负载 JSON 字符串（服务端怎么发、库里就怎么存，前端按同格式渲染）。
--    (session_id, seq) 唯一：保证可重放；seq 由服务端按到达顺序分配。
--    FK → session.session_id：级联删除会话即清事件。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS event (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,    -- 全局到达顺序（重放排序键）
    session_id TEXT    NOT NULL,                     -- 关联会话
    turn       INTEGER NOT NULL,                     -- 会话内第几轮（1 起，一问一答为一轮）
    seq        INTEGER NOT NULL,                     -- 会话内事件序号（增量拉取游标）
    event_type TEXT    NOT NULL,                     -- 事件类型（见上注释）
    data       TEXT    NOT NULL,                     -- 原始事件 JSON（未修改）
    created_at INTEGER NOT NULL,                     -- 毫秒时间戳
    UNIQUE (session_id, seq),
    FOREIGN KEY (session_id) REFERENCES session(session_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_event_session_id ON event(session_id, id);
CREATE INDEX IF NOT EXISTS idx_event_session_turn ON event(session_id, turn, seq);

-- =============================================================================
-- 智能体管理相关表（Phase 2 新增）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 3. agent —— 智能体配置（一个智能体 = 标题+描述+系统提示词+模型绑定+工具绑定）
--    system_prompt：AgentScope HarnessAgent 的 sysPrompt；
--    model_profile_id：绑定 ~/.dsh/model-config.json 中的 profile id；
--    智能体创建后复用现有 ChatRunner/WsServer/WebServer，仅在 prompt 时携带
--    agentId 做路由：ChatRunner 据 agentId 查配置 → 构建/缓存 HarnessAgent。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent (
    id               TEXT    PRIMARY KEY,           -- 智能体 ID（UUID）
    title            TEXT    NOT NULL,               -- 标题（显示名）
    description      TEXT    NOT NULL DEFAULT '',     -- 描述
    system_prompt    TEXT    NOT NULL DEFAULT '',     -- 系统提示词
    model_profile_id TEXT    NOT NULL DEFAULT '',     -- 绑定模型 profile id（空=用 active）
    icon             TEXT    NOT NULL DEFAULT '🤖',  -- 图标 emoji
    created_at       INTEGER NOT NULL,               -- 毫秒时间戳
    updated_at       INTEGER NOT NULL                 -- 毫秒时间戳
);

CREATE INDEX IF NOT EXISTS idx_agent_updated ON agent(updated_at DESC);

-- -----------------------------------------------------------------------------
-- 4. agent_binding —— 智能体绑定关系（skill / data_api / mcp）
--    binding_type: skill | api | mcp
--    target_id:    对应 skill.id / data_api.id / mcp_server.id
--    复合主键 (agent_id, binding_type, target_id) 防重复绑定。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_binding (
    agent_id     TEXT    NOT NULL,
    binding_type TEXT    NOT NULL,                    -- skill | api | mcp
    target_id    TEXT    NOT NULL,
    PRIMARY KEY (agent_id, binding_type, target_id),
    FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE
);

-- -----------------------------------------------------------------------------
-- 5. skill —— 技能管理（.md frontmatter + 语义绑定）
--    source: file（从目录导入）| manual（手动创建）
--    content: 完整 .md 内容（frontmatter + 正文）
--    semantic_binding: 自然语言描述何时调用此技能（转 Tool Schema description）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS skill (
    id               TEXT    PRIMARY KEY,
    name             TEXT    NOT NULL UNIQUE,          -- 唯一技能名
    description      TEXT    NOT NULL DEFAULT '',
    source           TEXT    NOT NULL DEFAULT 'manual',-- file | manual
    file_path        TEXT,                             -- 导入时文件路径
    content          TEXT    NOT NULL DEFAULT '',      -- .md 完整内容
    semantic_binding TEXT    NOT NULL DEFAULT '',      -- 语义绑定（自然语言）
    status           TEXT    NOT NULL DEFAULT 'active',-- active | inactive
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL
);

-- -----------------------------------------------------------------------------
-- 6. data_api —— 数据服务 API 管理（HTTP API → 注册为 Agent 工具）
--    inputs/outputs: JSON 数组 [{name,type,required,description}]
--    semantic_binding: JSON {summary, inputSemantics:{}, outputSemantics:{}}
--    后端 DataApiTool 据此生成 ToolSchema，Agent 可发现并调用。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_api (
    id               TEXT    PRIMARY KEY,
    name             TEXT    NOT NULL,                 -- API 显示名
    method           TEXT    NOT NULL DEFAULT 'GET',   -- GET | POST | PUT | DELETE
    url              TEXT    NOT NULL,                  -- 完整 URL
    headers          TEXT    NOT NULL DEFAULT '{}',    -- JSON 请求头
    inputs           TEXT    NOT NULL DEFAULT '[]',    -- JSON 输入参数定义
    outputs          TEXT    NOT NULL DEFAULT '[]',     -- JSON 输出参数定义
    semantic_binding TEXT    NOT NULL DEFAULT '{}',    -- JSON 语义绑定
    status           TEXT    NOT NULL DEFAULT 'active',
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL
);

-- -----------------------------------------------------------------------------
-- 7. mcp_server —— MCP Server 管理（stdio/sse → 注册为 Agent 工具集）
--    transport: stdio | sse
--    command/args/env: stdio 模式参数
--    url: sse 模式端点
--    semantic_binding: JSON {summary, toolSemantics:{toolName:desc}}
--    连接后自动拉取 tools/list，语义绑定补充到每个工具 description。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mcp_server (
    id               TEXT    PRIMARY KEY,
    name             TEXT    NOT NULL,
    transport        TEXT    NOT NULL DEFAULT 'stdio', -- stdio | sse
    command          TEXT,                            -- stdio: 可执行命令
    args             TEXT    NOT NULL DEFAULT '[]',   -- JSON 参数数组
    env              TEXT    NOT NULL DEFAULT '{}',   -- JSON 环境变量
    url              TEXT,                            -- sse: 端点 URL
    semantic_binding TEXT    NOT NULL DEFAULT '{}',   -- JSON 语义绑定
    status           TEXT    NOT NULL DEFAULT 'inactive',-- inactive | connected | error
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL
);