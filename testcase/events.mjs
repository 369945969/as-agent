/**
 * AgentScope event-stream end-to-end test (English output to avoid CRLF/mojibake).
 *
 * Verifies the server-side persistence + replay architecture:
 *   1. WS without cookie is rejected (error frame + close 1008).
 *   2. Authed WS "prompt" emits ordered events incl. thinking + tool calls.
 *      - every frame has a monotonic, per-session seq (arrival order).
 *   3. RAW events are persisted to SQLite: GET /api/messages returns the SAME
 *      (seq,event) sequence and order as the live WS stream (replay == live).
 *   4. Resume after disconnect: close the socket mid-turn, reconnect with
 *      "subscribe after=<lastSeq>"; generation continues server-side and we
 *      receive the remaining frames through "done". Final seq set is contiguous
 *      1..N with no gaps/dupes, and /api/sessions marks it done.
 *
 * Usage:  node testcase/events.mjs
 * Env:    DSH_TOKEN (required), DSH_PORT (8766), TEST_MODEL (qwen3.8-max)
 */

const PORT = process.env.DSH_PORT || '8766'
const BASE = `http://localhost:${PORT}`
const WSPORT = parseInt(PORT) + 1
const TOKEN = process.env.DSH_TOKEN || ''
const MODEL = process.env.TEST_MODEL || 'qwen3.8-max'
const PROMPT = 'Think about it briefly, then use your tools to list the files under src/main/java/com/agentscope and tell me how many .java files there are.'

let COOKIE = ''
const results = []
const ok = (name, cond, detail = '') => { results.push({ name, pass: !!cond }); console.log(`  ${cond ? '[PASS]' : '[FAIL]'} ${name}${detail ? ' -- ' + detail : ''}`) }

function newSock() { return new WebSocket(`ws://localhost:${WSPORT}/ws/agent`, { headers: { cookie: COOKIE } }) }
function sleep(ms) { return new Promise(r => setTimeout(r, ms)) }

async function handshake() {
    const res = await fetch(`${BASE}/?token=${TOKEN}`, { redirect: 'manual' })
    const sc = res.headers.get('set-cookie')
    if (!sc) throw new Error('no set-cookie from handshake')
    COOKIE = sc.split(';')[0]
    console.log('[events] cookie:', COOKIE.slice(0, 22) + '...')
}

// 1) unauthorized WS
function testUnauthorized() {
    return new Promise((resolve) => {
        const ws = new WebSocket(`ws://localhost:${WSPORT}/ws/agent`)
        let gotErr = false
        const t = setTimeout(() => { ok('ws unauthorized (no cookie)', false, 'timeout'); try { ws.close() } catch {} ; resolve() }, 8000)
        ws.onmessage = (e) => { const f = JSON.parse(String(e.data)); if (f.event === 'error') gotErr = true }
        ws.onclose = (e) => { clearTimeout(t); ok('ws unauthorized (no cookie)', gotErr && e.code === 1008, `code=${e.code}`); resolve() }
        ws.onerror = () => {}
    })
}

// 2+3) one full live run, capture frames; then compare with persisted /api/messages
async function testLiveThenReplay() {
    const sid = 'live-' + Math.random().toString(36).slice(2, 8)
    const frames = []
    await new Promise((resolve) => {
        const ws = newSock()
        const t = setTimeout(() => { try { ws.close() } catch {}; resolve() }, 180000)
        ws.onopen = () => ws.send(JSON.stringify({ action: 'prompt', sessionId: sid, message: PROMPT, userId: 'ev-test', model: MODEL }))
        ws.onmessage = (e) => {
            const f = JSON.parse(String(e.data)); if (f.sessionId !== sid) return
            frames.push(f)
            if (f.event === 'done' || f.event === 'error') { clearTimeout(t); try { ws.close() } catch {}; resolve() }
        }
        ws.onerror = () => {}
        ws.onclose = () => { clearTimeout(t); resolve() }
    })

    const types = new Set(frames.map(f => f.event))
    const counts = frames.reduce((a, f) => (a[f.event] = (a[f.event] || 0) + 1, a), {})
    console.log('[events] live event counts:', JSON.stringify(counts))

    // monotonic seq within session
    let mono = true
    for (let i = 1; i < frames.length; i++) if (frames[i].seq <= frames[i - 1].seq) mono = false
    ok('live: seq is strictly increasing (arrival order)', mono)

    ok('live: thinking event present', (counts.thinking || 0) >= 1, `n=${counts.thinking || 0}`)
    ok('live: tool_call_start present', (counts.tool_call_start || 0) >= 1, samplesOf(frames, 'tool_call_start'))
    ok('live: tool_call_delta present', (counts.tool_call_delta || 0) >= 1)
    ok('live: tool_result_end present', (counts.tool_result_end || 0) >= 1, samplesOf(frames, 'tool_result_end'))
    ok('live: answer delta present', (counts.delta || 0) >= 1)
    ok('live: model + session + done present', types.has('session') && types.has('model') && types.has('done'))

    // persisted raw events must equal live (seq,event) order
    const r = await fetch(`${BASE}/api/messages?sessionId=${sid}&after=0`, { headers: { cookie: COOKIE } })
    const j = await r.json()
    const persisted = (j.items || []).map(x => `${x.seq}:${x.event}`)
    const live = frames.map(f => `${f.seq}:${f.event}`)
    const sameLen = persisted.length === live.length
    const sameOrder = sameLen && persisted.every((v, i) => v === live[i])
    ok('persistence: /api/messages equals live stream (same order)', sameOrder, sameOrder ? `${live.length} events` : `live=${live.length} db=${persisted.length}`)

    // data is stored RAW: compare a sample payload
    const thinkLive = frames.find(f => f.event === 'thinking')
    const thinkDb = (j.items || []).find(x => x.event === 'thinking')
    if (thinkLive && thinkDb) ok('persistence: raw thinking payload unmodified', JSON.stringify(thinkDb.data) === JSON.stringify(thinkLive.data))
    return sid
}

// 4) resume after disconnect mid-turn
async function testResume() {
    const sid = 'resume-' + Math.random().toString(36).slice(2, 8)
    let lastSeq = 0
    // first connection: send prompt, read a few frames, then disconnect hard
    await new Promise((resolve) => {
        const ws = newSock()
        let n = 0
        const t = setTimeout(() => { try { ws.close() } catch {}; resolve() }, 15000)
        ws.onopen = () => ws.send(JSON.stringify({ action: 'prompt', sessionId: sid, message: PROMPT, userId: 'ev-test', model: MODEL }))
        ws.onmessage = (e) => {
            const f = JSON.parse(String(e.data)); if (f.sessionId !== sid) return
            lastSeq = Math.max(lastSeq, f.seq); n++
            // disconnect partway (after some thinking/tool frames but before done)
            if (n >= 8 && (f.event === 'tool_call_start' || f.event === 'thinking')) {
                clearTimeout(t); try { ws.close() } catch {}; resolve()
            }
        }
        ws.onerror = () => {}
        ws.onclose = () => { clearTimeout(t); resolve() }
    })

    // give the backend a moment while generation continues server-side
    await sleep(1500)
    // reconnect + subscribe from cursor
    const got = [{ from: 'resume' }]
    await new Promise((resolve) => {
        const ws = newSock()
        const t = setTimeout(() => { try { ws.close() } catch {}; resolve() }, 180000)
        ws.onopen = () => ws.send(JSON.stringify({ action: 'subscribe', sessionId: sid, after: lastSeq }))
        ws.onmessage = (e) => {
            const f = JSON.parse(String(e.data)); if (f.sessionId !== sid) return
            got.push(f)
            if (f.seq > lastSeq) lastSeq = f.seq
            if (f.event === 'done' || f.event === 'error') { clearTimeout(t); try { ws.close() } catch {}; resolve() }
        }
        ws.onerror = () => {}
        ws.onclose = () => { clearTimeout(t); resolve() }
    })

    // authoritative: read full persisted stream, assert contiguous 1..N and ended done
    const r = await fetch(`${BASE}/api/messages?sessionId=${sid}&after=0`, { headers: { cookie: COOKIE } })
    const j = await r.json()
    const items = j.items || []
    ok('resume: server finished after client disconnected (status done)', j.status === 'done', `status=${j.status}`)
    const seqs = items.map(x => x.seq)
    const contiguous = seqs.every((v, i) => v === i + 1)
    ok('resume: persisted seq contiguous 1..N (no gap/dup across disconnect)', contiguous, `count=${seqs.length}`)
    ok('resume: final event is done', items.length && items[items.length - 1].event === 'done')

    // sessions list marks it done
    const sr = await fetch(`${BASE}/api/sessions?userId=ev-test`, { headers: { cookie: COOKIE } })
    const sj = await sr.json()
    const row = (sj.items || []).find(x => x.sessionId === sid)
    ok('sessions: session listed with done status', row && row.status === 'done', row ? row.status : 'missing')
}

function samplesOf(frames, ev) { const f = frames.find(x => x.event === ev); return f ? JSON.stringify(f.data).slice(0, 90) : '' }

async function main() {
    if (!TOKEN) { console.error('DSH_TOKEN required'); process.exit(1) }
    console.log(`\n[events-e2e] target=${BASE} ws=:${WSPORT} model=${MODEL}\n`)
    await handshake()
    console.log('== 1. auth: WS without cookie is rejected ==')
    await testUnauthorized()
    console.log('\n== 2+3. live event ordering + raw persistence (replay == live) ==')
    await testLiveThenReplay()
    console.log('\n== 4. resume after mid-turn disconnect (backend keeps producing) ==')
    await testResume()

    const passed = results.filter(r => r.pass).length
    console.log(`\n=== events-e2e result: ${passed}/${results.length} passed ===`)
    process.exit(passed === results.length ? 0 : 1)
}
main().catch(e => { console.error(e); process.exit(1) })