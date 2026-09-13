/**
 * md.js — Markdown 渲染（先转义再变换，防 XSS）
 */
function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function inlineMd(s) {
    s = s.replace(/`([^`]+)`/g, (m, c) => `<code>${c}</code>`);
    s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
    s = s.replace(/~~([^~]+)~~/g, '<del>$1</del>');
    s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    return s;
}

function renderMarkdown(src) {
    if (src == null) return '';
    let s = escapeHtml(String(src));
    const codeBlocks = [];
    s = s.replace(/```([\w+-]*)\r?\n?([\s\S]*?)```/g, (m, lang, code) => {
        const i = codeBlocks.length;
        codeBlocks.push(`<pre class="md-code"><code>${code.replace(/\r?\n$/, '')}</code></pre>`);
        return `\u0000CODE${i}\u0000`;
    });
    const lines = s.split(/\r?\n/);
    const out = []; let para = [], list = null, inQuote = false;
    const flushPara = () => { if (para.length) { out.push(`<p>${inlineMd(para.join('<br>'))}</p>`); para = []; } };
    const flushList = () => { if (list) { out.push(`</${list}>`); list = null; } };
    const closeQuote = () => { if (inQuote) { out.push('</blockquote>'); inQuote = false; } };
    for (const raw of lines) {
        const line = raw.trimEnd();
        let m;
        const cm = /^\u0000CODE(\d+)\u0000$/.exec(line.trim());
        if (cm) { flushPara(); flushList(); closeQuote(); out.push(codeBlocks[+cm[1]]); continue; }
        if ((m = /^(#{1,4})\s+(.*)$/.exec(line))) { flushPara(); flushList(); closeQuote(); out.push(`<h${m[1].length}>${inlineMd(m[2])}</h${m[1].length}>`); }
        else if (/^(-{3,}|\*{3,}|_{3,})$/.test(line.trim())) { flushPara(); flushList(); closeQuote(); out.push('<hr>'); }
        else if ((m = /^&gt;\s?(.*)$/.exec(line))) { flushPara(); flushList(); if (!inQuote) { out.push('<blockquote>'); inQuote = true; } out.push(inlineMd(m[1]) + '<br>'); }
        else if ((m = /^\s*[-*+]\s+(.*)$/.exec(line))) { flushPara(); closeQuote(); if (list !== 'ul') { flushList(); out.push('<ul>'); list = 'ul'; } out.push(`<li>${inlineMd(m[1])}</li>`); }
        else if ((m = /^\s*\d+\.\s+(.*)$/.exec(line))) { flushPara(); closeQuote(); if (list !== 'ol') { flushList(); out.push('<ol>'); list = 'ol'; } out.push(`<li>${inlineMd(m[1])}</li>`); }
        else if (line.trim() === '') { flushPara(); flushList(); closeQuote(); }
        else { flushList(); closeQuote(); para.push(line); }
    }
    flushPara(); flushList(); closeQuote();
    return out.join('');
}
