#!/usr/bin/env bash
# Start agentscope (Web HTTP + WebSocket streaming, both opened together)
# Usage: scripts/start.sh [port]
# Environment variables:
#   DSH_TOKEN=...                        required, auth key
#   DSH_PORT=8766                        port
#   DSH_MODEL_CONFIG=~/.dsh/model-config.json  model config file
# Models (apiKey/baseUrl/model) are loaded from the model config file,
# a request may carry a "model" id to pick a specific model.
# On startup both the Web (port) and WebSocket (port+1) servers are opened
# and their ports are printed to the log.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PORT="${1:-${DSH_PORT:-8766}}"
JAR="$ROOT/target/agentscope-1.0.0.jar"

[ -f "$JAR" ] || { echo "[start] Jar not found, run scripts/build.sh first"; exit 1; }
[ -n "${DSH_TOKEN:-}" ] || { echo "[start] Please set DSH_TOKEN environment variable"; exit 1; }

# Kill any previous agentscope instance so the ports are free
echo "[start] killing previous agentscope process (if any)..."
pkill -f "agentscope-1.0.0.jar" 2>/dev/null && echo "[start] killed previous instance" || true
sleep 1

echo "[start] launching agentscope (port=$PORT, ws=$((PORT + 1)))..."
java -jar "$JAR" "$PORT"