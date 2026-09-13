/**
 * AgentScope Agent Management e2e test.
 *
 * Tests the new agent/skill/api/mcp CRUD + agentId routing + context injection:
 *   1. Agent CRUD: create → get → update → list → delete
 *   2. Skill CRUD: create → list → update → delete
 *   3. Data API CRUD: create → list → update → delete
 *   4. MCP CRUD: create → list → update → delete
 *   5. Agent bindings: create skill + api + mcp → bind to agent → verify getAgent returns bindings
 *   6. AgentId routing via WS: prompt with agentId → injection events emitted before user_message
 *   7. AI prompt generation: POST /api/agents/generate-prompt → non-empty prompt
 *
 * Usage:  node testcase/agents.mjs
 * Env:    DSH_TOKEN (required), DSH_PORT (8766), TEST_MODEL (qwen3.7-max)
 */

const PORT = process.env.DSH_PORT || '8766'
const BASE = `http://localhost:${PORT}`
const WSPORT = parseInt(PORT) + 1
const TOKEN = process.env.DSH_TOKEN || ''
const MODEL = process.env.TEST_MODEL || 'qwen3.7-max'

let COOKIE = ''
const results = []
const ok = (name, cond, detail = '') => {
    results.push({ name, pass: !!cond })
    console.log(`  ${cond ? '[PASS]' : '[FAIL]'} ${name}${detail ? ' -- ' + detail : ''}`)
}

async function handshake() {
    const res = await fetch(`${BASE}/?token=${TOKEN}`, { redirect: 'manual' })
    const sc = res.headers.get('set-cookie')
    if (!sc) throw new Error('no set-cookie from handshake')
    COOKIE = sc.split(';')[0]
    console.log('[agents-e2e] cookie:', COOKIE.slice(0, 22) + '...')
}

function newSock() { return new WebSocket(`ws://localhost:${WSPORT}/ws/agent`, { headers: { cookie: COOKIE } }) }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }

async function api(method, path, body) {
    const opts = { method, headers: { cookie: COOKIE, 'Content-Type': 'application/json' } }
    if (body) opts.body = JSON.stringify(body)
    const r = await fetch(`${BASE}${path}`, opts)
    return { status: r.status, data: await r.json() }
}

// ---- 1. Agent CRUD ----
async function testAgentCRUD() {
    console.log('\n== 1. Agent CRUD ==')

    // Create
    const { data: created } = await api('POST', '/api/agents', {
        title: 'Test Agent',
        description: 'A test agent for e2e',
        systemPrompt: 'You are a test agent.',
        modelProfileId: '',
        icon: '🧪'
    })
    ok('agent: create returns id', !!created.id, `id=${created.id}`)
    const agentId = created.id

    // Get
    const { data: agent } = await api('GET', `/api/agents/${agentId}`)
    ok('agent: get title matches', agent.title === 'Test Agent', `title=${agent.title}`)
    ok('agent: get systemPrompt matches', agent.systemPrompt === 'You are a test agent.', '')
    ok('agent: get icon matches', agent.icon === '🧪', `icon=${agent.icon}`)

    // Update
    await api('PUT', `/api/agents/${agentId}`, {
        title: 'Updated Agent',
        description: 'Updated description',
        systemPrompt: 'You are an updated test agent.',
        modelProfileId: '',
        icon: '🔧'
    })
    const { data: updated } = await api('GET', `/api/agents/${agentId}`)
    ok('agent: update title', updated.title === 'Updated Agent', `title=${updated.title}`)
    ok('agent: update icon', updated.icon === '🔧', `icon=${updated.icon}`)

    // List
    const { data: list } = await api('GET', '/api/agents')
    ok('agent: list contains created agent', (list.items || []).some(a => a.id === agentId), `count=${list.items?.length}`)

    // Delete
    await api('DELETE', `/api/agents/${agentId}`)
    const { data: deleted } = await api('GET', `/api/agents/${agentId}`)
    ok('agent: delete → get returns not found', !deleted.id && (deleted.error || !deleted.title), '')

    // Cleanup: verify list no longer contains
    const { data: listAfter } = await api('GET', '/api/agents')
    ok('agent: list excludes deleted', !(listAfter.items || []).some(a => a.id === agentId), '')
}

// ---- 2. Skill CRUD ----
async function testSkillCRUD() {
    console.log('\n== 2. Skill CRUD ==')

    const { data: created } = await api('POST', '/api/skills', {
        name: 'test-skill-e2e',
        description: 'A test skill',
        source: 'manual',
        content: '---\nname: test-skill-e2e\n---\nThis is a test skill.',
        semanticBinding: 'Use when the user asks for test operations'
    })
    ok('skill: create returns id', !!created.id, `id=${created.id}`)
    const skillId = created.id

    const { data: list } = await api('GET', '/api/skills')
    ok('skill: list contains created', (list.items || []).some(s => s.id === skillId), '')

    await api('PUT', `/api/skills/${skillId}`, {
        name: 'test-skill-updated',
        description: 'Updated skill desc',
        content: '---\nname: test-skill-updated\n---\nUpdated content.',
        semanticBinding: 'Use when testing updates',
        status: 'active'
    })
    const { data: listAfter } = await api('GET', '/api/skills')
    const updated = (listAfter.items || []).find(s => s.id === skillId)
    ok('skill: update name', updated && updated.name === 'test-skill-updated', `name=${updated?.name}`)

    await api('DELETE', `/api/skills/${skillId}`)
    const { data: listFinal } = await api('GET', '/api/skills')
    ok('skill: delete → list excludes', !(listFinal.items || []).some(s => s.id === skillId), '')

    return skillId
}

// ---- 3. Data API CRUD ----
async function testDataApiCRUD() {
    console.log('\n== 3. Data API CRUD ==')

    const { data: created } = await api('POST', '/api/data-apis', {
        name: 'Test Weather API',
        method: 'GET',
        url: 'https://httpbin.org/get',
        headers: '{"X-Test":"true"}',
        inputs: '[{"name":"city","type":"string","required":true,"description":"City name"}]',
        outputs: '[{"name":"temperature","type":"number","description":"Temperature in C"}]',
        semanticBinding: '{"summary":"Get weather for a city","inputSemantics":{"city":"The city to query"},"outputSemantics":{"temperature":"Current temp in Celsius"}}'
    })
    ok('data-api: create returns id', !!created.id, `id=${created.id}`)
    const apiId = created.id

    const { data: list } = await api('GET', '/api/data-apis')
    ok('data-api: list contains created', (list.items || []).some(a => a.id === apiId), '')

    await api('PUT', `/api/data-apis/${apiId}`, {
        name: 'Updated Weather API',
        method: 'GET',
        url: 'https://httpbin.org/get',
        headers: '{}',
        inputs: '[]',
        outputs: '[]',
        semanticBinding: '{}',
        status: 'active'
    })
    const { data: listAfter } = await api('GET', '/api/data-apis')
    const updated = (listAfter.items || []).find(a => a.id === apiId)
    ok('data-api: update name', updated && updated.name === 'Updated Weather API', `name=${updated?.name}`)

    await api('DELETE', `/api/data-apis/${apiId}`)
    const { data: listFinal } = await api('GET', '/api/data-apis')
    ok('data-api: delete → list excludes', !(listFinal.items || []).some(a => a.id === apiId), '')

    return apiId
}

// ---- 4. MCP CRUD ----
async function testMcpCRUD() {
    console.log('\n== 4. MCP CRUD ==')

    const { data: created } = await api('POST', '/api/mcps', {
        name: 'test-mcp-filesystem',
        transport: 'stdio',
        command: 'npx',
        args: '["-y","@anthropic/mcp-filesystem","/tmp"]',
        env: '{}',
        url: '',
        semanticBinding: '{"summary":"File system access","toolSemantics":{"read_file":"Read file content"}}'
    })
    ok('mcp: create returns id', !!created.id, `id=${created.id}`)
    const mcpId = created.id

    const { data: list } = await api('GET', '/api/mcps')
    ok('mcp: list contains created', (list.items || []).some(m => m.id === mcpId), '')

    await api('PUT', `/api/mcps/${mcpId}`, {
        name: 'test-mcp-updated',
        transport: 'stdio',
        command: 'npx',
        args: '["-y","@anthropic/mcp-filesystem","/tmp"]',
        env: '{}',
        url: '',
        semanticBinding: '{}',
        status: 'inactive'
    })
    const { data: listAfter } = await api('GET', '/api/mcps')
    const updated = (listAfter.items || []).find(m => m.id === mcpId)
    ok('mcp: update name', updated && updated.name === 'test-mcp-updated', `name=${updated?.name}`)

    await api('DELETE', `/api/mcps/${mcpId}`)
    const { data: listFinal } = await api('GET', '/api/mcps')
    ok('mcp: delete → list excludes', !(listFinal.items || []).some(m => m.id === mcpId), '')

    return mcpId
}

// ---- 5. Agent bindings: create skill + api + mcp → bind to agent → verify ----
async function testAgentBindings() {
    console.log('\n== 5. Agent bindings ==')

    // Create skill
    const { data: skill } = await api('POST', '/api/skills', {
        name: 'bind-test-skill',
        description: 'Skill for binding test',
        source: 'manual',
        content: '---\nname: bind-test-skill\n---\nTest skill content.',
        semanticBinding: 'Use when binding test is needed'
    })

    // Create data API
    const { data: dataApi } = await api('POST', '/api/data-apis', {
        name: 'bind-test-api',
        method: 'GET',
        url: 'https://httpbin.org/get',
        headers: '{}',
        inputs: '[]',
        outputs: '[]',
        semanticBinding: '{"summary":"Test API for binding","inputSemantics":{},"outputSemantics":{}}'
    })

    // Create MCP
    const { data: mcp } = await api('POST', '/api/mcps', {
        name: 'bind-test-mcp',
        transport: 'stdio',
        command: 'echo',
        args: '["hello"]',
        env: '{}',
        url: '',
        semanticBinding: '{"summary":"Test MCP for binding","toolSemantics":{}}'
    })

    // Create agent
    const { data: agent } = await api('POST', '/api/agents', {
        title: 'Binding Test Agent',
        description: 'Agent with bindings',
        systemPrompt: 'You are a binding test agent.',
        modelProfileId: '',
        icon: '🔗'
    })
    const agentId = agent.id

    // Bind skill + api + mcp to agent
    const { data: bindResult } = await api('POST', `/api/agents/${agentId}/bindings`, {
        skillIds: [skill.id],
        apiIds: [dataApi.id],
        mcpIds: [mcp.id]
    })
    ok('bindings: update returns ok', !!bindResult.ok, '')

    // Verify agent get returns bindings
    const { data: agentWithBindings } = await api('GET', `/api/agents/${agentId}`)
    ok('bindings: skillIds contains bound skill', (agentWithBindings.skillIds || []).includes(skill.id), `ids=${JSON.stringify(agentWithBindings.skillIds)}`)
    ok('bindings: apiIds contains bound api', (agentWithBindings.apiIds || []).includes(dataApi.id), `ids=${JSON.stringify(agentWithBindings.apiIds)}`)
    ok('bindings: mcpIds contains bound mcp', (agentWithBindings.mcpIds || []).includes(mcp.id), `ids=${JSON.stringify(agentWithBindings.mcpIds)}`)

    // Cleanup
    await api('DELETE', `/api/agents/${agentId}`)
    await api('DELETE', `/api/skills/${skill.id}`)
    await api('DELETE', `/api/data-apis/${dataApi.id}`)
    await api('DELETE', `/api/mcps/${mcp.id}`)

    return { agentId, skillId: skill.id, apiId: dataApi.id, mcpId: mcp.id }
}

// ---- 6. AgentId routing via WS: injection events emitted before user_message ----
async function testAgentIdRouting() {
    console.log('\n== 6. AgentId routing: injection events ==')

    // Create a skill
    const { data: skill } = await api('POST', '/api/skills', {
        name: 'route-test-skill',
        description: 'Skill for routing test',
        source: 'manual',
        content: '---\nname: route-test-skill\n---\nThis skill helps with routing tests.',
        semanticBinding: 'Use when the user mentions routing'
    })

    // Create an agent with the skill bound
    const { data: agent } = await api('POST', '/api/agents', {
        title: 'Routing Test Agent',
        description: 'Agent for routing test',
        systemPrompt: 'You are a routing test agent. Be brief.',
        modelProfileId: '',
        icon: '🧭'
    })
    const agentId = agent.id

    // Bind the skill
    await api('POST', `/api/agents/${agentId}/bindings`, {
        skillIds: [skill.id],
        apiIds: [],
        mcpIds: []
    })

    // Send a WS prompt with agentId
    const sid = 'agent-route-' + Math.random().toString(36).slice(2, 8)
    const frames = []
    await new Promise((resolve) => {
        const ws = newSock()
        const t = setTimeout(() => { try { ws.close() } catch {}; resolve() }, 180000)
        ws.onopen = () => ws.send(JSON.stringify({
            action: 'prompt',
            sessionId: sid,
            message: 'Hello, what can you do?',
            userId: 'agent-test',
            model: MODEL,
            agentId: agentId
        }))
        ws.onmessage = (e) => {
            const f = JSON.parse(String(e.data))
            if (f.sessionId !== sid) return
            frames.push(f)
            if (f.event === 'done' || f.event === 'error') { clearTimeout(t); try { ws.close() } catch {}; resolve() }
        }
        ws.onerror = () => {}
        ws.onclose = () => { clearTimeout(t); resolve() }
    })

    const eventTypes = frames.map(f => f.event)
    const counts = frames.reduce((a, f) => (a[f.event] = (a[f.event] || 0) + 1, a), {})
    console.log('[agents-e2e] agent routing event counts:', JSON.stringify(counts))

    // Verify injection events are present and ordered BEFORE user_message
    const userMsgIdx = eventTypes.indexOf('user_message')
    ok('agentId routing: user_message present', userMsgIdx >= 0, '')

    const skillInjectionIdx = eventTypes.indexOf('skill_injection')
    ok('agentId routing: skill_injection present', skillInjectionIdx >= 0, `events=${eventTypes.join(',')}`)

    if (userMsgIdx >= 0 && skillInjectionIdx >= 0) {
        ok('agentId routing: skill_injection BEFORE user_message', skillInjectionIdx < userMsgIdx, `skill_inj=${skillInjectionIdx} user_msg=${userMsgIdx}`)
    }

    // Verify context_injection present (system prompt)
    ok('agentId routing: context_injection present', eventTypes.includes('context_injection'), `events=${eventTypes.join(',')}`)

    // Verify model + session + done present
    ok('agentId routing: model present', eventTypes.includes('model'), '')
    ok('agentId routing: session present', eventTypes.includes('session'), '')
    ok('agentId routing: done present', eventTypes.includes('done'), '')

    // Verify injection event data structure
    const skillInj = frames.find(f => f.event === 'skill_injection')
    if (skillInj) {
        ok('agentId routing: skill_injection has skills array', Array.isArray(skillInj.data?.skills), `data=${JSON.stringify(skillInj.data).slice(0, 100)}`)
        ok('agentId routing: skill_injection summary present', !!skillInj.data?.summary, '')
    }

    const ctxInj = frames.find(f => f.event === 'context_injection')
    if (ctxInj) {
        ok('agentId routing: context_injection has detail', !!ctxInj.data?.detail, `type=${ctxInj.data?.type}`)
    }

    // Verify persisted events match live
    const r = await fetch(`${BASE}/api/messages?sessionId=${sid}&after=0`, { headers: { cookie: COOKIE } })
    const j = await r.json()
    const persistedEvents = (j.items || []).map(x => x.event)
    ok('agentId routing: persisted events include injection events',
        persistedEvents.includes('context_injection') && persistedEvents.includes('skill_injection'),
        `events=${persistedEvents.join(',')}`)

    // Cleanup
    await api('DELETE', `/api/agents/${agentId}`)
    await api('DELETE', `/api/skills/${skill.id}`)
}

// ---- 7. AI prompt generation ----
async function testGeneratePrompt() {
    console.log('\n== 7. AI prompt generation ==')

    const { data: result } = await api('POST', '/api/agents/generate-prompt', {
        title: 'Code Review Assistant',
        description: 'Reviews Java code for security, performance, and best practices'
    })

    ok('generate-prompt: returns non-empty prompt', !!result.prompt && result.prompt.length > 10, `len=${result.prompt?.length}`)
    if (result.prompt) {
        console.log('[agents-e2e] generated prompt preview:', result.prompt.slice(0, 120).replace(/\n/g, ' ') + '...')
    }
}

// ---- 8. Agent without agentId → no injection events (default path) ----
async function testNoAgentId() {
    console.log('\n== 8. No agentId → no injection events ==')

    const sid = 'no-agent-' + Math.random().toString(36).slice(2, 8)
    const frames = []
    await new Promise((resolve) => {
        const ws = newSock()
        const t = setTimeout(() => { try { ws.close() } catch {}; resolve() }, 180000)
        ws.onopen = () => ws.send(JSON.stringify({
            action: 'prompt',
            sessionId: sid,
            message: 'Say hello in one sentence.',
            userId: 'no-agent-test',
            model: MODEL
        }))
        ws.onmessage = (e) => {
            const f = JSON.parse(String(e.data))
            if (f.sessionId !== sid) return
            frames.push(f)
            if (f.event === 'done' || f.event === 'error') { clearTimeout(t); try { ws.close() } catch {}; resolve() }
        }
        ws.onerror = () => {}
        ws.onclose = () => { clearTimeout(t); resolve() }
    })

    const eventTypes = frames.map(f => f.event)
    ok('no agentId: no context_injection', !eventTypes.includes('context_injection'), '')
    ok('no agentId: no skill_injection', !eventTypes.includes('skill_injection'), '')
    ok('no agentId: user_message present', eventTypes.includes('user_message'), '')
    ok('no agentId: done present', eventTypes.includes('done'), '')
}

async function main() {
    if (!TOKEN) { console.error('DSH_TOKEN required'); process.exit(1) }
    console.log(`[agents-e2e] target=${BASE} ws=:${WSPORT} model=${MODEL}\n`)
    await handshake()

    await testAgentCRUD()
    await testSkillCRUD()
    await testDataApiCRUD()
    await testMcpCRUD()
    await testAgentBindings()
    await testGeneratePrompt()
    await testAgentIdRouting()
    await testNoAgentId()

    const passed = results.filter(r => r.pass).length
    const failed = results.length - passed
    console.log(`\n=== agents-e2e result: ${passed}/${results.length} passed${failed ? `, ${failed} failed` : ''} ===`)
    process.exit(passed === results.length ? 0 : 1)
}

main().catch(e => { console.error(e); process.exit(1) })
