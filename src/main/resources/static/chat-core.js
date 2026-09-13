/**
 * chat-core.js — 事件归约 + 渲染 + 会话管理
 */

function newTurn() {
    return { model: null, parts: [], curText: null, curThink: null, byCall: {}, container: null, raf: 0 };
}

function applyEvent(turn, ev) {
    const d = ev.data;
    switch (ev.event) {
        case 'model': turn.model = (typeof d === 'string') ? d : (d && d.name) || turn.model; break;
        case 'thinking_start': turn.curThink = null; break;
        case 'thinking':
            if (!turn.curThink) { turn.curThink = { type: 'thinking', text: '' }; turn.parts.push(turn.curThink); turn.curText = null; }
            turn.curThink.text += (typeof d === 'string' ? d : (d && d.text) || '');
            break;
        case 'delta':
            if (!turn.curText) { turn.curText = { type: 'text', text: '' }; turn.parts.push(turn.curText); turn.curThink = null; }
            turn.curText.text += (typeof d === 'string' ? d : (d && d.text) || '');
            break;
        case 'tool_call_start':
            turn.curText = null; turn.curThink = null;
            { const part = { type: 'tool', callId: d.callId, name: d.name, args: '', output: '', state: 'running' };
              turn.parts.push(part); turn.byCall[d.callId] = part; }
            break;
        case 'tool_call_delta': { const p = turn.byCall[d.callId]; if (p) p.args += d.argsDelta || ''; break; }
        case 'tool_result_delta': { const p = turn.byCall[d.callId]; if (p) p.output += d.outputDelta || ''; break; }
        case 'tool_result_end': { const p = turn.byCall[d.callId]; if (p) p.state = (d.state === 'SUCCESS' || d.state == null) ? 'ok' : 'error'; break; }
        case 'context_injection':
            turn.parts.push({ type: 'injection', injectType: 'context', summary: d?.summary || '', detail: d?.detail || '' });
            break;
        case 'skill_injection':
            turn.parts.push({ type: 'injection', injectType: 'skill', summary: d?.summary || '', items: d?.skills || [] });
            break;
        case 'mcp_injection':
            turn.parts.push({ type: 'injection', injectType: 'mcp', summary: d?.summary || '', items: d?.mcps || [] });
            break;
        case 'api_injection':
            turn.parts.push({ type: 'injection', injectType: 'api', summary: d?.summary || '', items: d?.apis || [] });
            break;
        case 'error': turn.error = (typeof d === 'string' ? d : '') || '请求失败'; turn.curText = null; turn.curThink = null; break;
    }
}

function renderTurn(turn) {
    if (!turn.container) return;
    const c = turn.container;
    c.innerHTML = '';
    for (const p of turn.parts) {
        if (p.type === 'injection') {
            c.appendChild(renderInjectionPart(p));
        } else if (p.type === 'thinking') {
            const v = makeDisclosure('think', '思考'); v.setState('ok');
            v.setSummary((p.text || '').split('\n').filter(l => l.trim()).pop() || '推理中…');
            v.body.textContent = p.text || '';
            c.appendChild(v.root);
        } else if (p.type === 'tool') {
            const v = makeDisclosure('tool', '工具调用'); v.insertName(p.name || '');
            v.setState(p.state === 'ok' ? 'ok' : (p.state === 'error' ? 'error' : 'running'));
            if (p.state === 'error') v.setError('失败 · ' + (p.name || ''));
            else v.setSummary(pickArg(p.args) || p.name || '运行中…');
            const io = el('div', 'io-card');
            const inSec = el('div', 'io'); inSec.appendChild(el('span', 'io-label', '输入'));
            inSec.appendChild(el('pre', 'io-text', p.args || ''));
            io.appendChild(inSec); io.appendChild(el('div', 'io-divider'));
            const outSec = el('div', 'io'); outSec.appendChild(el('span', 'io-label', '输出'));
            const outPre = el('pre', 'io-text', p.output || ''); if (p.state === 'error') outPre.dataset.error = '1';
            outSec.appendChild(outPre); io.appendChild(outSec);
            v.body.appendChild(io); c.appendChild(v.root);
        } else if (p.type === 'text') {
            const b = el('div', 'bubble md'); b.innerHTML = renderMarkdown(p.text || '');
            c.appendChild(b);
        }
    }
    if (turn.error) { const b = el('div', 'bubble md err'); b.innerHTML = renderMarkdown('错误: ' + turn.error); c.appendChild(b); }
    if (turn.model) { const meta = el('div', 'meta'); meta.appendChild(el('span', 'tag', turn.model)); c.appendChild(meta); }
}

function scheduleRender(turn) {
    if (turn.raf) return;
    turn.raf = requestAnimationFrame(() => { turn.raf = 0; renderTurn(turn); });
}

function turnToMessage(turn) {
    return { role: 'assistant', model: turn.model || null, parts: turn.parts.map(p => ({ ...p })) };
}

function userBubbleDom(text) { const w = el('div', 'msg user'); w.appendChild(el('div', 'bubble', text)); return w; }

function assistantMsgDom(m) {
    const wrap = el('div', 'msg assistant');
    (m.parts || []).forEach(p => {
        if (p.type === 'injection') { wrap.appendChild(renderInjectionPart(p)); }
        else if (p.type === 'thinking') {
            const v = makeDisclosure('think', '思考'); v.setState('ok');
            v.setSummary((p.text || '').split('\n').filter(l => l.trim()).pop() || '已完成推理');
            v.body.textContent = p.text || ''; wrap.appendChild(v.root);
        } else if (p.type === 'tool') {
            const v = makeDisclosure('tool', '工具调用'); v.insertName(p.name || '');
            v.setState(p.state === 'error' ? 'error' : 'ok');
            if (p.state === 'error') v.setError('失败 · ' + (p.name || '')); else v.setSummary(pickArg(p.args) || p.name || '');
            const io = el('div', 'io-card');
            const inSec = el('div', 'io'); inSec.appendChild(el('span', 'io-label', '输入')); inSec.appendChild(el('pre', 'io-text', typeof p.args === 'string' ? p.args : JSON.stringify(p.args, null, 2)));
            io.appendChild(inSec); io.appendChild(el('div', 'io-divider'));
            const outSec = el('div', 'io'); outSec.appendChild(el('span', 'io-label', '输出'));
            const outPre = el('pre', 'io-text', p.output || ''); if (p.state === 'error') outPre.dataset.error = '1';
            outSec.appendChild(outPre); io.appendChild(outSec); v.body.appendChild(io);
            wrap.appendChild(v.root);
        } else { const b = el('div', 'bubble md'); b.innerHTML = renderMarkdown(p.text || ''); wrap.appendChild(b); }
    });
    if (m.model) { const meta = el('div', 'meta'); meta.appendChild(el('span', 'tag', m.model)); wrap.appendChild(meta); }
    return wrap;
}

// ===== 会话管理 =====

function newConv() {
    const conv = { id: 'conv-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8), title: '新对话', messages: [], done: true, lastSeq: 0 };
    conversations.unshift(conv);
    saveConversations();
    renderSidebarContent();
    selectConv(conv.id);
}

function selectConv(id) {
    currentConvId = id;
    if (currentView !== 'chat') switchView('chat');
    renderSidebarContent();
    renderChat();
}

function deleteConv(id) {
    conversations = conversations.filter(c => c.id !== id);
    saveConversations();
    if (currentConvId === id) currentConvId = conversations.length ? conversations[0].id : null;
    renderSidebarContent();
    if (currentView === 'chat') renderChat();
}

function renderChat() {
    const area = document.getElementById('chatArea'); area.innerHTML = '';
    const conv = conversations.find(c => c.id === currentConvId);
    if (!conv) { area.innerHTML = '<div class="empty-tip"><div class="ic">✦</div><div>选择或新建一个对话开始聊天</div></div>'; return; }
    if ((!conv.messages || conv.messages.length === 0) && !conv.turn) {
        area.innerHTML = '<div class="empty-tip"><div class="ic">✦</div><div>发送第一条消息，模型会记住本会话选择</div></div>'; return;
    }
    conv.messages.forEach(m => area.appendChild(m.role === 'user' ? userBubbleDom(m.content) : assistantMsgDom(m)));
    if (conv.turn) { conv.turn.container = null; attachTurnContainer(conv); renderTurn(conv.turn); }
    area.scrollTop = area.scrollHeight;
}

function attachTurnContainer(conv) {
    if (conv.turn && !conv.turn.container) {
        const area = document.getElementById('chatArea');
        const tip = area.querySelector('.empty-tip'); if (tip) tip.remove();
        conv.turn.container = el('div', 'msg assistant');
        area.appendChild(conv.turn.container);
    }
}

function finalizeTurn(conv, cache) {
    if (conv.turn) {
        conv.messages.push(turnToMessage(conv.turn));
        if (conv.id === currentConvId && conv.turn.container) renderTurn(conv.turn);
        conv.turn = null;
    }
    if (cache) { conv.done = true; saveConversations(); }
}

async function syncFromServer() {
    let items = [];
    try { const r = await fetch('/api/sessions?userId=web-user'); if (r.ok) items = (await r.json()).items || []; } catch (e) {}
    const byId = new Map(conversations.map(c => [c.id, c]));
    for (const s of items) {
        let c = byId.get(s.sessionId);
        if (!c) { c = { id: s.sessionId, title: s.title || '新对话', model: s.model, messages: [], done: false, lastSeq: 0 }; conversations.push(c); byId.set(c.id, c); }
        else { if (!c.title && s.title) c.title = s.title; }
        c.updatedAt = s.updatedAt || c.updatedAt || 0;
    }
    conversations.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    for (const c of conversations) {
        if (c.done) continue;
        const changed = await replayConversation(c);
        if (changed) saveConversations();
    }
}

async function replayConversation(conv) {
    let resp;
    try { const r = await fetch(`/api/messages?sessionId=${encodeURIComponent(conv.id)}&after=${conv.lastSeq || 0}`); if (!r.ok) return false; resp = await r.json(); } catch (e) { return false; }
    const items = resp.items || [];
    if (items.length === 0) { if (resp.status === 'done' || resp.status === 'error') conv.done = true; return true; }
    for (const ev of items) {
        conv.lastSeq = Math.max(conv.lastSeq || 0, ev.seq);
        if (ev.turn !== undefined) conv.activeTurnNo = ev.turn;
        if (ev.event === 'user_message') {
            conv.messages.push({ role: 'user', content: (ev.data && ev.data.text) || '' });
            conv.turn = newTurn();
            if (conv.id === currentConvId) {
                const area = document.getElementById('chatArea');
                const tip = area.querySelector('.empty-tip'); if (tip) tip.remove();
                area.appendChild(userBubbleDom((ev.data && ev.data.text) || ''));
                attachTurnContainer(conv);
            }
            continue;
        }
        if (ev.event === 'done') { finalizeTurn(conv, true); continue; }
        if (!conv.turn) { conv.turn = newTurn(); if (conv.id === currentConvId) attachTurnContainer(conv); }
        applyEvent(conv.turn, ev);
        if (conv.id === currentConvId) scheduleRender(conv.turn);
    }
    if (resp.status === 'done' || resp.status === 'error') conv.done = true;
    return true;
}
