#!/bin/sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RUN_DIR="$SCRIPT_DIR/.run"
mkdir -p "$RUN_DIR"
: "${AI_BIND:=127.0.0.1}"
: "${AI_PORT:=18090}"
export AI_BIND AI_PORT
nohup python3 "$SCRIPT_DIR/ai_bridge.py" </dev/null >>"$RUN_DIR/bridge.log" 2>&1 &
printf '%s\n' "$!" >"$RUN_DIR/bridge.pid"
printf 'AI bridge started (PID %s); log: %s\n' "$(cat "$RUN_DIR/bridge.pid")" "$RUN_DIR/bridge.log"
