/**
 * util.js — 通用工具函数、图标、HTTP API helper
 */
const ICON = {
    think: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3a6 6 0 0 0-3.5 10.9c.6.5.9 1.1.9 1.8V17a1 1 0 0 0 1 1h3.2a1 1 0 0 0 1-1v-1.3c0-.7.3-1.3.9-1.8A6 6 0 0 0 12 3Z"/><path d="M9.5 21h5"/></svg>',
    tool:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4 2.6-2.6Z"/></svg>',
    chev:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>',
    inject:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M5 8v8a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2Z"/><path d="m9 12 2 2 4-4"/></svg>'
};

function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
}

function getTokenFromUrl() {
    return new URLSearchParams(location.search).get('token') || '';
}

function setWsStatus(online) {
    const dot = document.getElementById('wsDot');
    if (!dot) return;
    dot.classList.toggle('off', !online);
    const label = document.getElementById('wsLabel');
    if (label) label.textContent = online ? 'WS 已连接' : 'HTTP 模式';
}

function loadConversations() {
    try { conversations = JSON.parse(localStorage.getItem(LS_KEY)) || []; }
    catch (e) { conversations = []; }
}

function saveConversations() {
    localStorage.setItem(LS_KEY, JSON.stringify(conversations));
}

/** REST API helper — returns {status, data} */
async function api(method, path, body) {
    const opts = { method, headers: { 'Content-Type': 'application/json' } };
    if (body) opts.body = JSON.stringify(body);
    const r = await fetch(path, opts);
    const data = await r.json().catch(() => ({}));
    return { status: r.status, data };
}

function autoResize(e) {
    e.style.height = 'auto';
    e.style.height = Math.min(e.scrollHeight, 240) + 'px';
}
