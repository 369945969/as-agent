/**
 * app.js — 主入口：全局状态 + Token + WebSocket + 发送
 * 依赖：util.js, md.js, disclosure.js, chat-core.js, manage.js
 */

// ===== 全局状态 =====
let token = '';
let conversations = [];
let currentConvId = null;
let ws = null;
let wsWanted = false;
let currentView = 'chat';
let agents = [];
let skills = [];
let apis = [];
let mcps = [];
let selectedAgentId = null;
const LS_KEY = 'agentscope_conversations_v2';

// ===== Token 入口 =====
window.addEventListener('DOMContentLoaded', () => {
    loadConversations();
    const urlToken = getTokenFromUrl();
    if (urlToken) {
        token = urlToken;
        fetch(`/?token=${encodeURIComponent(token)}`, { redirect: 'manual' })
            .then(() => fetch('/api/agent/health'))
            .then(r => r.ok ? enterApp() : alert('Token 无效'))
            .catch(() => alert('连接失败，请检查服务是否启动'));
    }
    const input = document.getElementById('tokenInput');
    input.focus();
    input.addEventListener('keydown', e => { if (e.key === 'Enter') login(); });
});

function login() {
    token = document.getElementById('tokenInput').value.trim();
    if (!token) { alert('请输入 Token'); return; }
    fetch(`/?token=${encodeURIComponent(token)}`, { redirect: 'manual' })
        .then(() => fetch('/api/agent/health'))
        .then(r => r.ok ? enterApp() : alert('Token 无效'))
        .catch(() => alert('连接失败，请检查服务是否启动'));
}

async function enterApp() {
    document.getElementById('gate').style.display = 'none';
    document.getElementById('app').style.display = 'flex';
    loadModels();
    wsWanted = true; initWs();
    // 并行加载：会话 + 智能体 + Skill + API + MCP
    await Promise.all([syncFromServer(), loadAgents(), loadSkills(), loadApis(), loadMcps()]);
    renderSidebarContent();
    updateAgentTag();
    if (!currentConvId || !conversations.find(c => c.id === currentConvId)) {
        if (conversations.length) selectConv(conversations[0].id);
        else newConv();
    } else renderChat();
}

// ===== 模型下拉框 =====
function loadModels() {
    const sel = document.getElementById('modelSelect');
    fetch('/api/models').then(r => r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status)))
        .then(d => {
            sel.innerHTML = '';
            (d.items || []).forEach(it => { const o = document.createElement('option'); o.value = it.id; o.textContent = it.displayName || it.model; sel.appendChild(o); });
            if (d.active) sel.value = d.active;
        })
        .catch(() => { sel.innerHTML = '<option value="">模型加载失败</option>'; });
}

function selectedModel() {
    const sel = document.getElementById('modelSelect');
    return sel && sel.value ? sel.value : undefined;
}

// ===== 发送 =====
function send() {
    const input = document.getElementById('msgInput');
    const msg = input.value.trim(); if (!msg) return;
    input.value = ''; autoResize(input);
    const conv = conversations.find(c => c.id === currentConvId); if (!conv) return;
    if (conv.title === '新对话') { conv.title = msg.slice(0, 20); }
    conv.done = false; saveConversations();
    if (currentView === 'chat') renderSidebarContent();
    document.getElementById('sendBtn').disabled = true;
    if (ws && ws.readyState === WebSocket.OPEN) sendViaWs(conv, msg);
    else sendViaHttp(conv, msg);
}

function sendViaWs(conv, msg) {
    ws.send(JSON.stringify({
        action: 'prompt', sessionId: conv.id, message: msg,
        userId: 'web-user', model: selectedModel(),
        agentId: selectedAgentId || undefined
    }));
}

async function sendViaHttp(conv, msg) {
    try {
        const r = await fetch('/api/agent/send', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: conv.id, message: msg, userId: 'web-user', model: selectedModel(), agentId: selectedAgentId || undefined })
        });
        const j = await r.json();
        if (j.error) { alert(j.error); }
        await replayConversation(conv);
    } catch (e) { alert('请求失败: ' + e.message); }
    finally { document.getElementById('sendBtn').disabled = false; }
}

// ===== WebSocket =====
function initWs() {
    if (!token) return;
    try {
        ws = new WebSocket(`ws://${location.hostname}:${parseInt(location.port) + 1}/ws/agent`);
        ws.onopen = () => { setWsStatus(true); resubscribeIncomplete(); };
        ws.onmessage = (e) => onWsFrame(JSON.parse(String(e.data)));
        ws.onclose = () => { ws = null; setWsStatus(false); if (wsWanted) setTimeout(() => { if (wsWanted && !ws) initWs(); }, 1500); };
        ws.onerror = () => {};
    } catch (e) { ws = null; setWsStatus(false); }
}

function resubscribeIncomplete() {
    conversations.forEach(c => { if (!c.done) ws.send(JSON.stringify({ action: 'subscribe', sessionId: c.id, after: c.lastSeq || 0 })); });
}

function onWsFrame(f) {
    const conv = conversations.find(c => c.id === f.sessionId);
    if (!conv) return;
    if (f.seq && conv.lastSeq && f.seq <= conv.lastSeq) return;
    if (f.seq) conv.lastSeq = f.seq;
    const ev = { event: f.event, data: f.data, turn: f.turn };
    if (ev.event === 'user_message') {
        conv.messages.push({ role: 'user', content: (ev.data && ev.data.text) || '' });
        conv.turn = newTurn();
        if (conv.id === currentConvId) {
            const area = document.getElementById('chatArea');
            const tip = area.querySelector('.empty-tip'); if (tip) tip.remove();
            area.appendChild(userBubbleDom((ev.data && ev.data.text) || ''));
            attachTurnContainer(conv);
        }
    } else if (ev.event === 'done' || ev.event === 'cancelled') {
        finalizeTurn(conv, true);
        if (conv.id === currentConvId) document.getElementById('sendBtn').disabled = false;
    } else if (ev.event === 'error') {
        if (!conv.turn) { conv.turn = newTurn(); attachTurnContainer(conv); }
        applyEvent(conv.turn, ev); scheduleRender(conv.turn);
        finalizeTurn(conv, true);
        if (conv.id === currentConvId) document.getElementById('sendBtn').disabled = false;
    } else {
        if (!conv.turn) { conv.turn = newTurn(); if (conv.id === currentConvId) attachTurnContainer(conv); }
        applyEvent(conv.turn, ev);
        if (conv.id === currentConvId) scheduleRender(conv.turn);
    }
}

// 输入框自动高度
document.addEventListener('input', e => { if (e.target && e.target.id === 'msgInput') autoResize(e.target); });
