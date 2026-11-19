#!/usr/bin/env bash
set -euo pipefail

API="${1:-${API:-http://localhost:18080}}"
API="${API%/}"
RESPONSE=''
HTTP_STATUS=''
DEADLINE=0
EXECUTION_ID=''
WINDOW_COUNT=0
CHECKPOINT_FLOOR=''
CHECKPOINT=0

fail() {
  printf 'DEMO NOT READY: %s\n' "$*" >&2
  exit 1
}

trap 'fail "Unexpected failure at line $LINENO"' ERR
for tool in curl jq; do
  command -v "$tool" >/dev/null || fail "Required command missing: $tool"
done
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

request() {
  local method="$1" path="$2" timeout=15 remaining
  local -a args=(-sS --connect-timeout 3 -X "$method")
  if [[ "$path" == /api/compare ]]; then timeout=60; fi
  if (( DEADLINE > 0 )); then
    remaining=$((DEADLINE - SECONDS))
    (( remaining > 0 )) || return 1
    if (( remaining < timeout )); then timeout=$remaining; fi
  fi
  if (( $# == 3 )); then
    args+=(-H 'Content-Type: application/json' --data "$3")
  fi
  : > "$BODY_FILE"
  if ! HTTP_STATUS="$(curl "${args[@]}" --max-time "$timeout" \
      -o "$BODY_FILE" -w '%{http_code}' "$API$path")"; then
    RESPONSE="$(<"$BODY_FILE")"
    return 1
  fi
  RESPONSE="$(<"$BODY_FILE")"
  [[ "$HTTP_STATUS" == 2[0-9][0-9] ]]
}

require_request() {
  request "$@" || fail "$1 $2 failed (HTTP $HTTP_STATUS): $RESPONSE"
}

wait_for() {
  local seconds="$1"
  shift
  DEADLINE=$((SECONDS + seconds))
  while (( SECONDS < DEADLINE )); do
    if "$@"; then
      DEADLINE=0
      return 0
    fi
    if (( SECONDS < DEADLINE )); then sleep 1; fi
  done
  DEADLINE=0
  return 1
}

healthy() {
  request GET /api/health || return 1
  jq -e '.status == "UP"' <<< "$RESPONSE" >/dev/null || return 1
  request GET /api/ready || return 1
  jq -e '.status == "UP"' <<< "$RESPONSE" >/dev/null
}

read_window_count() {
  request GET /api/results/summary || return 1
  WINDOW_COUNT="$(jq -er --arg id "$EXECUTION_ID" '
    select(.executionId == $id) | .windowsWritten |
    select(type == "number" and . >= 0 and . == floor)
  ' <<< "$RESPONSE")"
}

fresh_results() {
  request GET /api/executions/current || return 1
  # A stopped/failed execution cannot keep the live panels moving.
  if ! jq -e --arg id "$EXECUTION_ID" '
    .id == $id and (.state == "RUNNING" or .state == "RECOVERING")
  ' <<< "$RESPONSE" >/dev/null; then
    RESTART=true
    return 0
  fi
  read_window_count || return 1
  (( WINDOW_COUNT > WINDOW_BASELINE ))
}

results_and_checkpoint() {
  request GET /api/executions/current || return 1
  jq -e --arg id "$EXECUTION_ID" '.id == $id' <<< "$RESPONSE" >/dev/null \
    || fail 'Active execution changed while waiting for results'
  [[ "$(jq -r '.state' <<< "$RESPONSE")" != FAILED ]] \
    || fail "Execution $EXECUTION_ID FAILED while waiting for results"
  jq -e '.state == "RUNNING"' <<< "$RESPONSE" >/dev/null || return 1
  read_window_count || return 1
  (( WINDOW_COUNT > 0 )) || return 1
  request GET /api/results/windows || return 1
  jq -e 'type == "array" and length > 0' <<< "$RESPONSE" >/dev/null || return 1
  # A checkpoint preceding the first observed windows can compare zero rows.
  # Wait for a subsequent completed barrier so the reference includes live output.
  request GET "/api/executions/$EXECUTION_PATH/checkpoints?limit=20" || return 1
  if [[ -z "$CHECKPOINT_FLOOR" ]]; then
    CHECKPOINT_FLOOR="$(jq -er '
      [.[] | select(.state == "COMPLETED") | .checkpointId] | max // 0
    ' <<< "$RESPONSE")" || return 1
    return 1
  fi
  CHECKPOINT="$(jq -er --argjson floor "$CHECKPOINT_FLOOR" '
    [.[] | select(.state == "COMPLETED" and .checkpointId > $floor and .checkpointId > 0)
      | .checkpointId] | max // empty
  ' <<< "$RESPONSE")"
}

wait_for 180 healthy || fail 'Health/readiness did not become UP within 180s'
require_request GET /api/version
printf '%s\n' "$RESPONSE"
COMMIT="$(jq -er '.commit | select(type == "string" and length > 0)' <<< "$RESPONSE")" \
  || fail 'Version response has no commit'
if [[ -n "${EXPECT_SHA:-}" && "$COMMIT" != "$EXPECT_SHA" ]]; then
  fail "Version commit does not equal EXPECT_SHA=$EXPECT_SHA"
fi

RESTART=false
require_request GET /api/executions/current
if [[ "$HTTP_STATUS" == 204 ]]; then
  RESTART=true
else
  EXECUTION_ID="$(jq -er '.id | select(type == "string" and length > 0)' <<< "$RESPONSE")" \
    || fail 'Current execution response has no id'
  STATE="$(jq -r '.state' <<< "$RESPONSE")"
  if [[ "$STATE" != RUNNING && "$STATE" != RECOVERING ]]; then
    RESTART=true
  else
    read_window_count || fail 'Could not read current execution window count'
    WINDOW_BASELINE="$WINDOW_COUNT"
    # windowEnd is simulation time, not wall-clock write time (contract 01).
    # The API exposes no writtenAt, so observe count growth for up to 120s.
    if (( WINDOW_BASELINE == 0 )) || ! wait_for 120 fresh_results; then
      RESTART=true
    fi
  fi
fi

if [[ "$RESTART" == true ]]; then
  printf 'Starting demo pipeline (no active execution or no window progress within 120s)\n'
  require_request GET /api/pipelines/demo
  PIPELINE="$RESPONSE"
  require_request POST /api/pipelines/validate "$PIPELINE"
  jq -e '.valid == true' <<< "$RESPONSE" >/dev/null || fail 'Demo pipeline validation failed'
  require_request POST /api/pipelines "$PIPELINE"
  PIPELINE_ID="$(jq -er '.id | select(type == "string" and length > 0) | @uri' <<< "$RESPONSE")" \
    || fail 'Saved demo pipeline response has no id'
  IDEMPOTENCY_KEY="$(cat /proc/sys/kernel/random/uuid)"
  require_request POST "/api/pipelines/$PIPELINE_ID/executions" \
    "$(jq -nc --arg key "$IDEMPOTENCY_KEY" '{idempotencyKey: $key}')"
  jq -e '.state == "RUNNING"' <<< "$RESPONSE" >/dev/null || fail 'Demo execution did not start RUNNING'
  EXECUTION_ID="$(jq -er '.id | select(type == "string" and length > 0)' <<< "$RESPONSE")" \
    || fail 'Started execution response has no id'
fi
EXECUTION_PATH="$(jq -nr --arg id "$EXECUTION_ID" '$id | @uri')"

wait_for 180 results_and_checkpoint \
  || fail "Execution $EXECUTION_ID did not produce windows and a COMPLETED checkpoint within 180s"
require_request POST /api/compare '{}'
MATCHED="$(jq -er '.matched | select(type == "number" and . >= 0 and . == floor)' <<< "$RESPONSE")" \
  || fail 'Comparison response has no valid matched count'
TOTAL="$(jq -er '.referenceResults | select(type == "number" and . >= 0 and . == floor)' <<< "$RESPONSE")" \
  || fail 'Comparison response has no valid total'
printf '%s / %s\n' "$MATCHED" "$TOTAL"
jq -e --argjson checkpoint "$CHECKPOINT" '
  .match == true and .referenceResults > 0 and .matched == .referenceResults and
  (.mismatches | type == "array" and length == 0) and
  (.checkpointId | type == "number" and . >= $checkpoint)
' <<< "$RESPONSE" >/dev/null || fail 'Comparison is empty, has mismatches, or used a stale checkpoint'
CHECKPOINT="$(jq -r '.checkpointId' <<< "$RESPONSE")"
require_request GET /api/executions/current
jq -e --arg id "$EXECUTION_ID" '.id == $id and .state == "RUNNING"' \
  <<< "$RESPONSE" >/dev/null || fail 'Execution changed or stopped during comparison'
printf 'DEMO READY %s execution=%s checkpoint=#%s compare=%s/%s\n' \
  "$COMMIT" "$EXECUTION_ID" "$CHECKPOINT" "$MATCHED" "$TOTAL"
