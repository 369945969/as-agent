package com.agentscope.agent;

import com.agentscope.config.ModelConfig;
import com.agentscope.store.MessageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Chat pipeline with server-side persistence.
 *
 * <p>Every event the agent produces is (1) stored RAW in SQLite
 * (session/event tables, keyed by session_id, ordered by arrival seq) and
 * (2) broadcast to currently attached live listeners. The run is owned by the
 * backend, NOT by a browser tab: a page refresh / disconnect never interrupts
 * generation; the client resumes with {@code GET /api/messages?after=seq} +
 * {@code WS subscribe} and replays the exact same frames.
 */
public class ChatRunner {
    private static final ObjectMapper M = new ObjectMapper();

    /** One event frame. dataJson is the RAW JSON value of the payload. */
    public record Frame(int turn, int seq, String event, String dataJson) {}

    /** Aggregated result of one run (used by the blocking HTTP endpoint). */
    public static final class RunResult {
        public String sessionId, model, status = "ok";
        public String error;
        public final StringBuilder reply = new StringBuilder();
        public final StringBuilder thinking = new StringBuilder();
        public final List<Map<String, Object>> toolCalls = new java.util.ArrayList<>();
    }

    private final class Run {
        final String sessionId;
        final int turn;
        final HarnessAgent agent;
        final String userId;
        final RuntimeContext ctx;
        final CopyOnWriteArrayList<Consumer<Frame>> listeners = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        final RunResult result = new RunResult();
        volatile boolean finished;

        Run(String sessionId, int turn, HarnessAgent agent, String userId, RuntimeContext ctx) {
            this.sessionId = sessionId; this.turn = turn; this.agent = agent; this.userId = userId; this.ctx = ctx;
            this.result.sessionId = sessionId;
        }
    }

    private final AgentModels models;
    private final MessageStore store;
    private final AgentManager agentManager;
    private final Map<String, Run> active = new ConcurrentHashMap<>();

    public ChatRunner(AgentModels models, MessageStore store, AgentManager agentManager) {
        this.models = models;
        this.store = store;
        this.agentManager = agentManager;
    }

    public boolean isRunning(String sessionId) {
        Run r = active.get(sessionId);
        return r != null && !r.finished;
    }

    /**
     * Start a turn, streaming frames to {@code listener} (may be null). The run
     * persists independently of the listener, so a browser disconnect never
     * interrupts generation. Used by the WebSocket path.
     */
    public void startAsync(String sessionId, String userId, String message, String requestedModel,
                           String systemPrompt, String skillPrompt, String agentId,
                           Consumer<Frame> listener) throws Exception {
        start0(sessionId, userId, message, requestedModel, systemPrompt, skillPrompt, agentId, listener);
    }

    private Run start0(String sessionId, String userId, String message, String requestedModel,
                       String systemPrompt, String skillPrompt, String agentId,
                       Consumer<Frame> listener) throws Exception {
        // 路由：agentId 非空 → AgentManager 构建专用 agent + config；否则用全局默认
        AgentManager.AgentHolder holder = (agentManager != null && agentId != null && !agentId.isBlank())
                ? agentManager.resolve(agentId) : null;
        ModelConfig.Profile profile;
        HarnessAgent agent;
        AgentConfig agentConfig = null;

        if (holder != null) {
            agent = holder.agent;
            agentConfig = holder.config;
            profile = holder.profile;
        } else {
            profile = models.resolveProfile(sessionId, requestedModel);
            agent = models.agentFor(profile);
        }

        RuntimeContext.Builder cb = RuntimeContext.builder().sessionId(sessionId).userId(userId);
        if (systemPrompt != null && !systemPrompt.isBlank()) cb.put("customSysPrompt", systemPrompt);
        if (skillPrompt != null && !skillPrompt.isBlank()) cb.put("skillPrompt", skillPrompt);
        RuntimeContext ctx = cb.build();

        Run run;
        synchronized (this) {
            Run existing = active.get(sessionId);
            if (existing != null && !existing.finished)
                throw new IllegalStateException("session already has a running turn");
            try {
                int turn = store.nextTurn(sessionId);
                run = new Run(sessionId, turn, agent, userId, ctx);
                run.result.model = profile.label();
                active.put(sessionId, run); // reserve atomically before emitting
            } catch (Exception e) {
                throw new RuntimeException("failed to start run: " + e.getMessage(), e);
            }
        }
        if (listener != null) run.listeners.add(listener);

        try {
            store.upsertSession(sessionId, userId, profile.profileId(),
                    message.length() > 20 ? message.substring(0, 20) : message, "running");
        } catch (Exception ignored) {}

        emit(run, "session", sessionId);
        emit(run, "model", profile.label());

        // 上下文注入事件（在 user_message 之前）
        if (agentConfig != null && agentConfig.hasInjections() && agentManager != null) {
            for (AgentManager.InjectionEvent ie : agentManager.buildInjectionEvents(agentConfig)) {
                emit(run, ie.eventType, ie.data);
            }
        }

        emit(run, "user_message", Map.of("text", message));

        try {
            agent.streamEvents(new UserMessage(message), ctx)
                .doOnNext(ev -> mapAndEmit(run, ev))
                .doOnError(e -> { finish(run, "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); })
                .doOnComplete(() -> { if (!run.finished) finish(run, "done", "[DONE]"); })
                .subscribe();
        } catch (Exception e) {
            // streamEvents 构造失败 → 清理 active，避免卡死
            finish(run, "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        }

        return run;
    }

    /** Await run completion (HTTP path). */
    private RunResult await(Run run, long timeoutMs) {
        try {
            if (!run.done.await(timeoutMs, TimeUnit.MILLISECONDS)) run.result.status = "timeout";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.result.status = "interrupted";
        }
        return run.result;
    }

    /** Start a turn with no live listener and block until it finishes (HTTP fallback). */
    public RunResult submitAndAwait(String sessionId, String userId, String message, String requestedModel,
                                    String systemPrompt, String skillPrompt, String agentId,
                                    long timeoutMs) throws Exception {
        Run run = start0(sessionId, userId, message, requestedModel, systemPrompt, skillPrompt, agentId, null);
        return await(run, timeoutMs);
    }

    /** Attach a live listener to the current run (WS resume). Returns false if none active. */
    public boolean attach(String sessionId, Consumer<Frame> listener) {
        Run r = active.get(sessionId);
        if (r == null || r.finished) return false;
        r.listeners.add(listener);
        return true;
    }

    public void detach(String sessionId, Consumer<Frame> listener) {
        Run r = active.get(sessionId);
        if (r != null) r.listeners.remove(listener);
    }

    /** Interrupt the agent loop for a session (best effort; HarnessAgent exposes a no-arg interrupt). */
    public void cancel(String sessionId) {
        Run r = active.get(sessionId);
        if (r == null || r.finished) return;
        try { r.agent.interrupt(); } catch (Exception ignored) {}
    }

    // ---------- internals ----------

    private void mapAndEmit(Run run, io.agentscope.core.event.AgentEvent ev) {
        try {
            AgentEventType t = ev.getType();
            if (t == AgentEventType.TEXT_BLOCK_DELTA) {
                String d = ((TextBlockDeltaEvent) ev).getDelta();
                if (d != null && !d.isEmpty()) { run.result.reply.append(d); emit(run, "delta", d); }
            } else if (t == AgentEventType.THINKING_BLOCK_START) {
                emit(run, "thinking_start", Map.of());
            } else if (t == AgentEventType.THINKING_BLOCK_DELTA) {
                String d = ((ThinkingBlockDeltaEvent) ev).getDelta();
                if (d != null && !d.isEmpty()) { run.result.thinking.append(d); emit(run, "thinking", d); }
            } else if (t == AgentEventType.TOOL_CALL_START) {
                ToolCallStartEvent e = (ToolCallStartEvent) ev;
                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("callId", nz(e.getToolCallId())); tc.put("name", nz(e.getToolCallName()));
                tc.put("args", ""); tc.put("output", ""); tc.put("state", "RUNNING");
                run.result.toolCalls.add(tc);
                emit(run, "tool_call_start", Map.of("callId", nz(e.getToolCallId()), "name", nz(e.getToolCallName())));
            } else if (t == AgentEventType.TOOL_CALL_DELTA) {
                ToolCallDeltaEvent e = (ToolCallDeltaEvent) ev;
                appendToolArg(run, e.getToolCallId(), e.getDelta());
                emit(run, "tool_call_delta", Map.of("callId", nz(e.getToolCallId()), "argsDelta", nz(e.getDelta())));
            } else if (t == AgentEventType.TOOL_RESULT_START) {
                ToolResultStartEvent e = (ToolResultStartEvent) ev;
                emit(run, "tool_result_start", Map.of("callId", nz(e.getToolCallId()), "name", nz(e.getToolCallName())));
            } else if (t == AgentEventType.TOOL_RESULT_TEXT_DELTA) {
                ToolResultTextDeltaEvent e = (ToolResultTextDeltaEvent) ev;
                String d = e.getDelta();
                if (d != null && !d.isEmpty()) {
                    appendToolOut(run, e.getToolCallId(), d);
                    emit(run, "tool_result_delta", Map.of("callId", nz(e.getToolCallId()), "outputDelta", d));
                }
            } else if (t == AgentEventType.TOOL_RESULT_END) {
                ToolResultEndEvent e = (ToolResultEndEvent) ev;
                String st = e.getState() == null ? "SUCCESS" : e.getState().name();
                setToolState(run, e.getToolCallId(), st);
                emit(run, "tool_result_end", Map.of("callId", nz(e.getToolCallId()), "state", st));
            }
        } catch (Exception ignored) {}
    }

    private void appendToolArg(Run run, String callId, String delta) {
        for (Map<String, Object> tc : run.result.toolCalls)
            if (nz(callId).equals(tc.get("callId"))) { tc.put("args", (String) tc.get("args") + nz(delta)); return; }
    }
    private void appendToolOut(Run run, String callId, String delta) {
        for (Map<String, Object> tc : run.result.toolCalls)
            if (nz(callId).equals(tc.get("callId"))) { tc.put("output", (String) tc.get("output") + delta); return; }
    }
    private void setToolState(Run run, String callId, String st) {
        for (Map<String, Object> tc : run.result.toolCalls)
            if (nz(callId).equals(tc.get("callId"))) { tc.put("state", st); return; }
    }

    /** Persist the RAW event first, then broadcast with the assigned seq. */
    private void emit(Run run, String event, Object dataValue) {
        try {
            String dataJson = M.writeValueAsString(dataValue);
            int seq;
            synchronized (this) {
                seq = store.append(run.sessionId, run.turn, event, dataJson);
            }
            Frame f = new Frame(run.turn, seq, event, dataJson);
            for (Consumer<Frame> l : run.listeners) {
                try { l.accept(f); } catch (Exception ignored) {} // dead conn: drop listener
            }
        } catch (Exception e) {
            System.err.println("[runner] emit failed: " + e.getMessage());
        }
    }

    private void finish(Run run, String event, String dataValue) {
        if (run.finished) return;
        run.finished = true;
        if ("error".equals(event)) { run.result.status = "error"; run.result.error = dataValue; }
        emit(run, event, dataValue);
        try { store.setStatus(run.sessionId, "error".equals(event) ? "error" : "done"); } catch (Exception ignored) {}
        active.remove(run.sessionId, run);
        run.done.countDown();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Best-effort interrupt of any active runs (on shutdown). */
    public void close() {
        for (Run r : active.values()) { try { r.agent.interrupt(); } catch (Exception ignored) {} }
        active.clear();
    }
}