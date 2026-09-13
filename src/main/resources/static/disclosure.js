/**
 * disclosure.js — 折叠披露行（思考/工具/注入事件）
 */

function makeDisclosure(kind, titleText) {
    const root = el('div', 'disc ' + kind);
    root.dataset.state = 'running';
    const head = el('button', 'disc-head'); head.type = 'button';
    const icon = el('span', 'disc-icon');
    icon.innerHTML = kind === 'think' ? ICON.think
        : kind === 'injection' ? ICON.inject : ICON.tool;
    const st = el('span', 'st'); st.dataset.s = 'running';
    const title = el('span', 'disc-title', titleText);
    const sep = el('span', 'disc-sep');
    const summary = el('span', 'disc-summary');
    const chev = el('span', 'disc-chev'); chev.innerHTML = ICON.chev;
    head.append(icon, st, title, sep, summary, chev);
    head.setAttribute('aria-expanded', 'false');
    const body = el('div', 'disc-body');
    head.onclick = () => { const open = root.classList.toggle('open'); head.setAttribute('aria-expanded', open ? 'true' : 'false'); };
    root.append(head, body);
    return {
        root, body,
        setSummary(t) { summary.textContent = t || ''; summary.classList.remove('err'); },
        setError(t) { summary.textContent = t || ''; summary.classList.add('err'); },
        setState(s) { root.dataset.state = s; st.dataset.s = s === 'running' ? 'running' : (s === 'error' ? 'error' : 'ok'); },
        insertName(name) { const n = el('span', 'tool-name', ' ' + name); head.insertBefore(n, sep); }
    };
}

function pickArg(args) {
    if (!args) return '';
    try { const o = typeof args === 'string' ? JSON.parse(args) : args; return o.command || o.path || o.file_path || o.url || o.pattern || o.query || o.prompt || ''; }
    catch (e) { return typeof args === 'string' ? args.slice(0, 60) : ''; }
}

/** 渲染注入事件披露行 */
function renderInjectionPart(p) {
    const labelMap = { context: '上下文注入', skill: '技能注入', mcp: 'MCP注入', api: 'API注入' };
    const v = makeDisclosure('injection', labelMap[p.injectType] || '注入');
    v.setState('ok');
    v.setSummary(p.summary || '');
    if (p.detail) v.body.textContent = p.detail;
    if (p.items) {
        const list = el('div');
        p.items.forEach(item => {
            const row = el('div', '', '• ' + (item.name || item.id || ''));
            if (item.description) row.textContent += ' — ' + item.description;
            list.appendChild(row);
        });
        v.body.appendChild(list);
    }
    return v.root;
}
