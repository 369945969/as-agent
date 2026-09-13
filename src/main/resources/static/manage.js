/**
 * manage.js — 视图切换 + 管理列表(中间栏) + 内联编辑表单(右侧替换聊天框)
 * 布局：左=功能图标菜单，中=当前功能列表(默认对话)，右=聊天框(编辑时被表单替换)
 */

// 当前打开的编辑表单（null=右侧显示聊天框）
let activeForm = null;

// ===== 视图切换 =====

function switchView(view) {
    currentView = view;
    document.querySelectorAll('.ib-item').forEach(n => n.classList.toggle('active', n.dataset.view === view));
    activeForm = null;
    renderSidebarContent();
    renderMain();
}

/** 渲染中间栏列表（按当前视图：对话/智能体/Skill/API/MCP） */
function renderSidebarContent() {
    const pc = document.getElementById('panelContent');
    if (!pc) return;
    pc.innerHTML = '';
    const view = currentView || 'chat';
    const ph = document.getElementById('panelHeader');
    if (ph) {
        ph.innerHTML = '';
        const titleMap = { chat:'对话', agents:'智能体', skills:'Skill', apis:'数据服务API', mcps:'MCP Server' };
        ph.appendChild(el('span', 'panel-title', titleMap[view] || ''));
        const addBtn = el('button', 'icon-btn', '+ 新建');
        addBtn.onclick = () => {
            if (view === 'agents') openAgentForm();
            else if (view === 'skills') openSkillForm();
            else if (view === 'apis') openApiForm();
            else if (view === 'mcps') openMcpForm();
            else newConv();
        };
        ph.appendChild(addBtn);
    }

    if (view === 'chat') renderConvList(pc);
    else if (view === 'agents') renderAgentList(pc);
    else if (view === 'skills') renderSkillList(pc);
    else if (view === 'apis') renderApiList(pc);
    else if (view === 'mcps') renderMcpList(pc);

    if (pc.childElementCount === 0) {
        const hintMap = { agents:'暂无智能体，点右上角「+ 新建」', skills:'暂无 Skill，点右上角「+ 新建」', apis:'暂无数据服务 API，点右上角「+ 新建」', mcps:'暂无 MCP Server，点右上角「+ 新建」' };
        if (hintMap[view]) pc.appendChild(el('div', 'empty-panel', hintMap[view]));
    }
}

/** 渲染右侧：无编辑表单则显示聊天框，否则显示对应管理表单 */
function renderMain() {
    const mv = document.getElementById('manageView');
    const chatArea = document.getElementById('chatArea');
    const inputBox = document.querySelector('.input-box');
    if (!mv || !chatArea || !inputBox) return;
    if (activeForm) {
        chatArea.style.display = 'none';
        inputBox.style.display = 'none';
        mv.style.display = 'flex';
        mv.innerHTML = '';
        const view = activeForm.view;
        const titles = { agents:'智能体', skills:'Skill', apis:'数据服务API', mcps:'MCP Server' };
        const header = el('div', 'mng-header');
        header.appendChild(el('h2', '', (activeForm.id ? '编辑' : '新建') + (titles[view] || '')));
        mv.appendChild(header);
        if (view === 'agents') renderAgentForm(mv, activeForm.id);
        else if (view === 'skills') renderSkillForm(mv, activeForm.id);
        else if (view === 'apis') renderApiForm(mv, activeForm.id);
        else if (view === 'mcps') renderMcpForm(mv, activeForm.id);
    } else {
        mv.style.display = 'none';
        chatArea.style.display = '';
        inputBox.style.display = '';
        renderChat();
    }
}

function closeForm() {
    activeForm = null;
    renderMain();
}

// ===== 会话列表（💬 对话视图）=====

function renderConvList(container) {
    if (!conversations.length) { container.appendChild(el('div', 'empty-panel', '暂无对话，点击「+ 新对话」')); return; }
    conversations.forEach(c => {
        const item = el('div', 'list-item' + (c.id === currentConvId ? ' active' : ''));
        item.onclick = () => selectConv(c.id);
        item.appendChild(el('span', 'item-icon', '💬'));
        item.appendChild(el('span', 'item-title', c.title));
        if (!c.done) item.appendChild(el('span', 'live-dot'));
        const del = el('span', 'del', '✕'); del.title = '删除';
        del.onclick = (e) => { e.stopPropagation(); deleteConv(c.id); };
        item.appendChild(del);
        container.appendChild(item);
    });
}

// ===== 智能体管理 =====

async function loadAgents() {
    const { data } = await api('GET', '/api/agents');
    agents = data.items || [];
}

function renderAgentList(container) {
    if (!agents.length) return;
    agents.forEach(a => {
        const item = el('div', 'list-item' + (a.id === selectedAgentId ? ' active' : ''));
        item.onclick = () => openAgentForm(a.id);
        item.appendChild(el('span', 'item-icon', a.icon || '🤖'));
        item.appendChild(el('span', 'item-title', a.title));
        item.appendChild(el('span', 'item-meta', a.modelProfileId || '默认'));
        const act = el('span', 'item-act' + (a.id === selectedAgentId ? ' active' : ''));
        act.textContent = a.id === selectedAgentId ? '已绑定' : '绑定';
        act.onclick = (e) => { e.stopPropagation(); selectAgent(a.id); };
        item.appendChild(act);
        const del = el('span', 'del', '✕');
        del.onclick = (e) => { e.stopPropagation(); deleteAgent(a.id); };
        item.appendChild(del);
        container.appendChild(item);
    });
}

function selectAgent(id) {
    selectedAgentId = (selectedAgentId === id) ? null : id;
    updateAgentTag();
    if (currentView === 'agents') renderSidebarContent();
}

function updateAgentTag() {
    const tag = document.getElementById('agentTag');
    if (!tag) return;
    if (selectedAgentId) {
        const a = agents.find(x => x.id === selectedAgentId);
        if (a) {
            tag.classList.remove('default');
            document.getElementById('agentEmoji').textContent = a.icon || '🤖';
            document.getElementById('agentName').textContent = a.title;
            return;
        }
    }
    tag.classList.add('default');
    document.getElementById('agentEmoji').textContent = '🤖';
    document.getElementById('agentName').textContent = '默认助手';
}

async function deleteAgent(id) {
    if (!confirm('确认删除此智能体？')) return;
    await api('DELETE', `/api/agents/${id}`);
    if (selectedAgentId === id) selectedAgentId = null;
    await loadAgents();
    if (currentView === 'agents') renderSidebarContent();
    updateAgentTag();
}

function openAgentPicker() { switchView('agents'); }

function openAgentForm(id) {
    activeForm = { view: 'agents', id: id || null };
    renderMain();
}

function renderAgentForm(container, id) {
    const a = id ? agents.find(x => x.id === id) : null;
    const isEdit = !!a;
    const form = el('div', 'mng-form');
    form.appendChild(el('h3', '', isEdit ? '编辑智能体' : '新建智能体'));
    const fields = document.createElement('div');
    fields.innerHTML = `
        <div class="field"><label>标题 *</label><input id="f_title" value="${a?.title || ''}" placeholder="如：代码审查助手"></div>
        <div class="field"><label>描述</label><input id="f_desc" value="${a?.description || ''}" placeholder="智能体的角色描述"></div>
        <div class="field">
            <label>系统提示词</label>
            <textarea id="f_prompt" rows="4" placeholder="系统提示词内容">${a?.systemPrompt || ''}</textarea>
            <button class="ai-btn" id="aiGenBtn">✨ AI 生成提示词</button>
        </div>
        <div class="field"><label>绑定模型</label><select id="f_model"><option value="">默认（active）</option></select></div>
        <div class="field"><label>图标</label><input id="f_icon" value="${a?.icon || '🤖'}" maxlength="4" style="width:60px"></div>
        <div class="field"><label>绑定 Skill</label><div class="checkbox-group" id="f_skills"></div></div>
        <div class="field"><label>绑定数据服务 API</label><div class="checkbox-group" id="f_apis"></div></div>
        <div class="field"><label>绑定 MCP Server</label><div class="checkbox-group" id="f_mcps"></div></div>`;
    form.appendChild(fields);

    document.getElementById('aiGenBtn')?.addEventListener('click', generatePrompt);

    const actions = el('div', 'mng-actions');
    const cancel = el('button', 'btn-cancel', '返回');
    cancel.onclick = closeForm;
    const save = el('button', 'btn-save', '保存');
    actions.append(cancel, save);
    form.appendChild(actions);
    container.appendChild(form);

    const modelSel = document.getElementById('f_model');
    fetch('/api/models').then(r => r.json()).then(d => {
        (d.items || []).forEach(p => { const o = document.createElement('option'); o.value = p.id; o.textContent = p.displayName; modelSel.appendChild(o); });
        if (a?.modelProfileId) modelSel.value = a.modelProfileId;
    }).catch(() => {});

    renderChips('f_skills', skills.map(s => ({ id: s.id, name: s.name, checked: a && (a.skillIds || []).includes(s.id) })));
    renderChips('f_apis', apis.map(x => ({ id: x.id, name: x.name, checked: a && (a.apiIds || []).includes(x.id) })));
    renderChips('f_mcps', mcps.map(m => ({ id: m.id, name: m.name, checked: a && (a.mcpIds || []).includes(m.id) })));

    save.onclick = async () => {
        const body = {
            title: val('f_title'), description: val('f_desc'), systemPrompt: val('f_prompt'),
            modelProfileId: val('f_model'), icon: val('f_icon') || '🤖',
            skillIds: checkedIds('f_skills'), apiIds: checkedIds('f_apis'), mcpIds: checkedIds('f_mcps')
        };
        if (!body.title) { alert('请填写标题'); return; }
        try {
            if (isEdit) {
                await api('PUT', `/api/agents/${id}`, body);
                await api('POST', `/api/agents/${id}/bindings`, { skillIds: body.skillIds, apiIds: body.apiIds, mcpIds: body.mcpIds });
            } else {
                const { data } = await api('POST', '/api/agents', body);
                if (data.id) await api('POST', `/api/agents/${data.id}/bindings`, { skillIds: body.skillIds, apiIds: body.apiIds, mcpIds: body.mcpIds });
            }
            await loadAgents();
            closeForm();
            if (currentView === 'agents') renderSidebarContent();
            updateAgentTag();
        } catch (e) { alert('保存失败: ' + (e.message || e)); }
    };
}

async function generatePrompt() {
    const btn = document.getElementById('aiGenBtn');
    if (!btn) return;
    btn.disabled = true; btn.textContent = '✨ 生成中…';
    try {
        const { data } = await api('POST', '/api/agents/generate-prompt', { title: val('f_title'), description: val('f_desc') });
        if (data.prompt) document.getElementById('f_prompt').value = data.prompt;
        else alert('生成失败，请检查模型是否可用');
    } catch (e) { alert('生成失败'); }
    btn.disabled = false; btn.textContent = '✨ AI 生成提示词';
}

// ===== Skill 管理 =====

async function loadSkills() {
    const { data } = await api('GET', '/api/skills');
    skills = data.items || [];
}

function renderSkillList(container) {
    if (!skills.length) return;
    skills.forEach(s => {
        const item = el('div', 'list-item');
        item.onclick = () => openSkillForm(s.id);
        item.appendChild(el('span', 'item-icon', '⚡'));
        item.appendChild(el('span', 'item-title', s.name));
        item.appendChild(el('span', 'item-meta', s.status || ''));
        const del = el('span', 'del', '✕');
        del.onclick = (e) => { e.stopPropagation(); deleteSkill(s.id); };
        item.appendChild(del);
        container.appendChild(item);
    });
}

async function deleteSkill(id) {
    if (!confirm('确认删除此 Skill？')) return;
    await api('DELETE', `/api/skills/${id}`);
    await loadSkills();
    if (currentView === 'skills') renderSidebarContent();
}

function openSkillForm(id) {
    activeForm = { view: 'skills', id: id || null };
    renderMain();
}

function renderSkillForm(container, id) {
    const s = id ? skills.find(x => x.id === id) : null;
    const isEdit = !!s;
    const form = el('div', 'mng-form');
    form.appendChild(el('h3', '', isEdit ? '编辑 Skill' : '新建 Skill'));
    const fields = document.createElement('div');
    fields.innerHTML = `
        <div class="field"><label>名称 *</label><input id="f_name" value="${s?.name || ''}" placeholder="如：code-review"></div>
        <div class="field"><label>描述</label><input id="f_desc" value="${s?.description || ''}"></div>
        <div class="field"><label>Skill 内容（.md frontmatter + 正文）</label><textarea id="f_content" rows="6" placeholder="---\nname: my-skill\n---\nSkill 正文内容">${s?.content || ''}</textarea></div>
        <div class="field"><label>语义绑定</label><textarea id="f_semantic" rows="2" placeholder="自然语言描述何时调用此技能">${s?.semanticBinding || ''}</textarea><div class="hint">Agent 会根据此描述决定何时调用</div></div>`;
    form.appendChild(fields);
    const actions = el('div', 'mng-actions');
    const cancel = el('button', 'btn-cancel', '返回');
    cancel.onclick = closeForm;
    const save = el('button', 'btn-save', '保存');
    save.onclick = async () => {
        const body = { name: val('f_name'), description: val('f_desc'), content: val('f_content'), semanticBinding: val('f_semantic') };
        if (!body.name) { alert('请填写名称'); return; }
        try {
            if (isEdit) await api('PUT', `/api/skills/${id}`, { ...body, status: 'active' });
            else await api('POST', '/api/skills', { ...body, source: 'manual' });
            await loadSkills();
            closeForm();
            if (currentView === 'skills') renderSidebarContent();
        } catch (e) { alert('保存失败: ' + (e.message || e)); }
    };
    actions.append(cancel, save);
    form.appendChild(actions);
    container.appendChild(form);
}

// ===== 数据服务 API 管理 =====

async function loadApis() {
    const { data } = await api('GET', '/api/data-apis');
    apis = data.items || [];
}

function renderApiList(container) {
    if (!apis.length) return;
    apis.forEach(a => {
        const item = el('div', 'list-item');
        item.onclick = () => openApiForm(a.id);
        item.appendChild(el('span', 'item-icon', '🌐'));
        item.appendChild(el('span', 'item-title', a.name));
        item.appendChild(el('span', 'item-meta', a.method || ''));
        const del = el('span', 'del', '✕');
        del.onclick = (e) => { e.stopPropagation(); deleteApi(a.id); };
        item.appendChild(del);
        container.appendChild(item);
    });
}

async function deleteApi(id) {
    if (!confirm('确认删除此 API？')) return;
    await api('DELETE', `/api/data-apis/${id}`);
    await loadApis();
    if (currentView === 'apis') renderSidebarContent();
}

function openApiForm(id) {
    activeForm = { view: 'apis', id: id || null };
    renderMain();
}

function renderApiForm(container, id) {
    const a = id ? apis.find(x => x.id === id) : null;
    const isEdit = !!a;
    const form = el('div', 'mng-form');
    form.appendChild(el('h3', '', isEdit ? '编辑数据服务 API' : '新建数据服务 API'));
    const fields = document.createElement('div');
    fields.innerHTML = `
        <div class="field"><label>名称 *</label><input id="f_name" value="${a?.name || ''}"></div>
        <div class="field"><label>Method</label><select id="f_method"><option>GET</option><option>POST</option><option>PUT</option><option>DELETE</option></select></div>
        <div class="field"><label>URL *</label><input id="f_url" value="${a?.url || ''}" placeholder="https://api.example.com/v1/..."></div>
        <div class="field"><label>请求头 (JSON)</label><textarea id="f_headers" rows="2" placeholder='{"Authorization":"Bearer xxx"}'>${a?.headers || '{}'}</textarea></div>
        <div class="field"><label>输入参数 (JSON)</label><textarea id="f_inputs" rows="3" placeholder='[{"name":"city","type":"string","required":true,"description":"城市名"}]'>${a?.inputs || '[]'}</textarea></div>
        <div class="field"><label>输出参数 (JSON)</label><textarea id="f_outputs" rows="3" placeholder='[{"name":"temperature","type":"number","description":"温度"}]'>${a?.outputs || '[]'}</textarea></div>
        <div class="field"><label>语义绑定 (JSON)</label><textarea id="f_semantic" rows="3" placeholder='{"summary":"查询天气","inputSemantics":{"city":"城市名"},"outputSemantics":{"temperature":"当前温度"}}'>${a?.semanticBinding || '{}'}</textarea></div>`;
    form.appendChild(fields);
    if (a?.method) setTimeout(() => { const s = document.getElementById('f_method'); if (s) s.value = a.method; }, 0);
    const actions = el('div', 'mng-actions');
    const cancel = el('button', 'btn-cancel', '返回');
    cancel.onclick = closeForm;
    const save = el('button', 'btn-save', '保存');
    save.onclick = async () => {
        const body = { name: val('f_name'), method: val('f_method'), url: val('f_url'),
            headers: val('f_headers') || '{}', inputs: val('f_inputs') || '[]', outputs: val('f_outputs') || '[]',
            semanticBinding: val('f_semantic') || '{}' };
        if (!body.name || !body.url) { alert('请填写名称和URL'); return; }
        try {
            if (isEdit) await api('PUT', `/api/data-apis/${id}`, { ...body, status: 'active' });
            else await api('POST', '/api/data-apis', body);
            await loadApis();
            closeForm();
            if (currentView === 'apis') renderSidebarContent();
        } catch (e) { alert('保存失败: ' + (e.message || e)); }
    };
    actions.append(cancel, save);
    form.appendChild(actions);
    container.appendChild(form);
}

// ===== MCP 管理 =====

async function loadMcps() {
    const { data } = await api('GET', '/api/mcps');
    mcps = data.items || [];
}

function renderMcpList(container) {
    if (!mcps.length) return;
    mcps.forEach(m => {
        const item = el('div', 'list-item');
        item.onclick = () => openMcpForm(m.id);
        item.appendChild(el('span', 'item-icon', '🔌'));
        item.appendChild(el('span', 'item-title', m.name));
        item.appendChild(el('span', 'item-meta', m.transport || ''));
        const del = el('span', 'del', '✕');
        del.onclick = (e) => { e.stopPropagation(); deleteMcp(m.id); };
        item.appendChild(del);
        container.appendChild(item);
    });
}

async function deleteMcp(id) {
    if (!confirm('确认删除此 MCP Server？')) return;
    await api('DELETE', `/api/mcps/${id}`);
    await loadMcps();
    if (currentView === 'mcps') renderSidebarContent();
}

function openMcpForm(id) {
    activeForm = { view: 'mcps', id: id || null };
    renderMain();
}

function renderMcpForm(container, id) {
    const m = id ? mcps.find(x => x.id === id) : null;
    const isEdit = !!m;
    const form = el('div', 'mng-form');
    form.appendChild(el('h3', '', isEdit ? '编辑 MCP Server' : '新建 MCP Server'));
    const fields = document.createElement('div');
    fields.innerHTML = `
        <div class="field"><label>名称 *</label><input id="f_name" value="${m?.name || ''}"></div>
        <div class="field"><label>传输方式</label><select id="f_transport"><option value="stdio">stdio</option><option value="sse">sse</option></select></div>
        <div class="field"><label>命令 (stdio)</label><input id="f_command" value="${m?.command || ''}" placeholder="npx"></div>
        <div class="field"><label>参数 (JSON)</label><textarea id="f_args" rows="2" placeholder='["-y","@anthropic/mcp-filesystem"]'>${m?.args || '[]'}</textarea></div>
        <div class="field"><label>环境变量 (JSON)</label><textarea id="f_env" rows="2" placeholder='{}'>${m?.env || '{}'}</textarea></div>
        <div class="field"><label>URL (sse)</label><input id="f_url" value="${m?.url || ''}" placeholder="https://..."></div>
        <div class="field"><label>语义绑定 (JSON)</label><textarea id="f_semantic" rows="3" placeholder='{"summary":"文件系统访问","toolSemantics":{"read_file":"读取文件"}}'>${m?.semanticBinding || '{}'}</textarea></div>`;
    form.appendChild(fields);
    if (m?.transport) setTimeout(() => { const s = document.getElementById('f_transport'); if (s) s.value = m.transport; }, 0);
    const actions = el('div', 'mng-actions');
    const cancel = el('button', 'btn-cancel', '返回');
    cancel.onclick = closeForm;
    const save = el('button', 'btn-save', '保存');
    save.onclick = async () => {
        const body = { name: val('f_name'), transport: val('f_transport'), command: val('f_command'),
            args: val('f_args') || '[]', env: val('f_env') || '{}', url: val('f_url'),
            semanticBinding: val('f_semantic') || '{}' };
        if (!body.name) { alert('请填写名称'); return; }
        try {
            if (isEdit) await api('PUT', `/api/mcps/${id}`, { ...body, status: 'inactive' });
            else await api('POST', '/api/mcps', body);
            await loadMcps();
            closeForm();
            if (currentView === 'mcps') renderSidebarContent();
        } catch (e) { alert('保存失败: ' + (e.message || e)); }
    };
    actions.append(cancel, save);
    form.appendChild(actions);
    container.appendChild(form);
}

// ===== 通用工具 =====

function val(id) { const e = document.getElementById(id); return e ? e.value : ''; }

function renderChips(containerId, items) {
    const c = document.getElementById(containerId);
    if (!c) return;
    c.innerHTML = '';
    items.forEach(item => {
        const chip = el('div', 'checkbox-item' + (item.checked ? ' checked' : ''));
        chip.dataset.id = item.id;
        chip.textContent = item.name || item.id;
        chip.onclick = () => chip.classList.toggle('checked');
        c.appendChild(chip);
    });
}

function checkedIds(containerId) {
    return [...document.querySelectorAll(`#${containerId} .checkbox-item.checked`)].map(e => e.dataset.id);
}
