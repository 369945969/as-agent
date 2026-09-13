#!/usr/bin/env bash
# 运行 agentscope 端到端测试（TypeScript + Node 22+）
# 前置：agentscope web/ws 服务已启动（scripts/start.sh）
# 用法：DSH_TOKEN=xxx bash testcase/run-e2e.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

[ -n "${DSH_TOKEN:-}" ] || { echo "[e2e] 请设 DSH_TOKEN"; exit 1; }

echo "[e2e] 确保服务就绪..."
for i in $(seq 1 30); do
  curl -s -o /dev/null -m 2 "http://localhost:${DSH_PORT:-8766}/?token=$DSH_TOKEN" && break
  sleep 1
done

echo "[e2e] 运行事件流测试 (events.mjs)..."
node testcase/events.mjs
EVENTS_EXIT=$?

echo "[e2e] 运行智能体管理测试 (agents.mjs)..."
node testcase/agents.mjs
AGENTS_EXIT=$?

if [ $EVENTS_EXIT -eq 0 ] && [ $AGENTS_EXIT -eq 0 ]; then
  echo "[e2e] ✓ 全部通过"
  exit 0
else
  echo "[e2e] ✗ 有失败 (events=$EVENTS_EXIT, agents=$AGENTS_EXIT)"
  exit 1
fi
