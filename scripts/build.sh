#!/usr/bin/env bash
# Build the agentscope project: mvn clean package generates a fat jar
# Usage: scripts/build.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Kill any running agentscope instance first, otherwise the jar stays locked and clean fails
echo "[build] killing running agentscope process (if any)..."
pkill -f "agentscope-1.0.0.jar" 2>/dev/null && echo "[build] killed previous instance" || true
sleep 1

echo "[build] mvn clean package..."
if ! mvn -q clean package -DskipTests; then
    echo "[build] FAILED"
    exit 1
fi
echo "[build] Done: target/agentscope-1.0.0.jar"