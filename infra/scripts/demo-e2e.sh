#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
API="${API:-http://localhost:18080}"
API="${API%/}"
STEP=1
RESPONSE=''
HTTP_STATUS=''
DEADLINE=0
EXECUTION_ID=''
declare -A SNAPSHOTS=()

fail() {
  printf '%d FAIL %s\n' "$STEP" "$*" >&2
  local path
  for path in "${!SNAPSHOTS[@]}"; do
    printf '\n%s\n' "$path" >&2
    if ! jq . <<< "${SNAPSHOTS[$path]}" >&2; then
      printf '%s\n' "${SNAPSHOTS[$path]}" >&2
    fi
  done
  exit 1
}

trap 'fail "Unexpected failure at line $LINENO"' ERR
for tool in curl jq docker; do
  command -v "$tool" >/dev/null || fail "Required command missing: $tool"
done
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

pass() { printf '%d PASS %s\n' "$STEP" "$*"; }

# Preserve the last response per endpoint, including non-JSON HTTP errors.
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
    SNAPSHOTS["$method $path"]="curl failed (HTTP $HTTP_STATUS): $RESPONSE"
    return 1
  fi
  RESPONSE="$(<"$BODY_FILE")"
  SNAPSHOTS["$method $path (HTTP $HTTP_STATUS)"]="$RESPONSE"
  [[ "$HTTP_STATUS" == 2[0-9][0-9] ]]
}

require_request() {
  request "$@" || fail "$1 $2 failed (HTTP $HTTP_STATUS)"
}

wait_for() {
  local seconds="$1" description="$2"
  shift 2
  DEADLINE=$((SECONDS + seconds))
  while (( SECONDS < DEADLINE )); do
    if "$@"; then
      DEADLINE=0
      return 0
    fi
    if (( SECONDS < DEADLINE )); then sleep 1; fi
  done
  fail "$description (timeout ${seconds}s)"
}

ready() {
  request GET /api/ready || return 1
  jq -e '.status == "UP"' <<< "$RESPONSE" >/dev/null
}

results_and_checkpoints() {
  request GET /api/results/windows || return 1
  jq -e 'type == "array" and length >= 8' <<< "$RESPONSE" >/dev/null || return 1
  request GET /api/executions/current || return 1
  jq -e --arg id "$EXECUTION_ID" '
    .id == $id and (.lastCompletedCheckpoint | type == "number" and . >= 2)
  ' <<< "$RESPONSE" >/dev/null
}

late_results() {
  request GET '/api/results/dead-letters?kind=LATE' || return 1
  jq -e 'type == "array" and any(.[]; .kind == "LATE")' <<< "$RESPONSE" >/dev/null
}

recovery_started() {
  request GET /api/executions/current || return 1
  jq -e --arg id "$EXECUTION_ID" --argjson generation "$GENERATION" '
    .id == $id and (.state == "RECOVERING" or
      (.generation | type == "number" and . > $generation))
  ' <<< "$RESPONSE" >/dev/null
}

recovery_finished() {
  request GET /api/executions/current || return 1
  jq -e --arg id "$EXECUTION_ID" --argjson generation "$GENERATION" \
    --argjson checkpoint "$CHECKPOINT" '
    .id == $id and .state == "RUNNING" and
    (.generation | type == "number" and . > $generation) and
    (.restoredFromCheckpoint | type == "number" and . >= $checkpoint)
  ' <<< "$RESPONSE" >/dev/null
}

workers_backpressured() {
  request GET /api/workers || return 1
  jq -e 'type == "array" and any(.[]; .backpressured == true)' <<< "$RESPONSE" >/dev/null
}

workers_drained() {
  request GET /api/workers || return 1
  # An empty or malformed worker list must not count as drained.
  jq -e 'type == "array" and length > 0 and all(.[]; .backpressured == false)' \
    <<< "$RESPONSE" >/dev/null
}

wait_for 180 'Control API did not become ready' ready
pass 'Control API ready'

STEP=2
require_request GET /api/pipelines/demo
PIPELINE="$RESPONSE"
require_request POST /api/pipelines/validate "$PIPELINE"
jq -e '.valid == true' <<< "$RESPONSE" >/dev/null || fail 'Demo pipeline validation failed'
require_request POST /api/pipelines "$PIPELINE"
PIPELINE_ID="$(jq -er '.id | select(type == "string" and length > 0) | @uri' <<< "$RESPONSE")"
# Kernel-generated random UUID; available on the Linux Compose/CI host.
IDEMPOTENCY_KEY="$(cat /proc/sys/kernel/random/uuid)"
require_request POST "/api/pipelines/$PIPELINE_ID/executions" \
  "$(jq -nc --arg key "$IDEMPOTENCY_KEY" '{idempotencyKey: $key}')"
jq -e '.state == "RUNNING"' <<< "$RESPONSE" >/dev/null || fail 'Execution did not start RUNNING'
EXECUTION_ID="$(jq -er '.id | select(type == "string" and length > 0)' <<< "$RESPONSE")"
pass "Demo pipeline validated, saved and RUNNING ($EXECUTION_ID)"

STEP=3
wait_for 120 'Expected at least 8 windows and checkpoint >= 2' results_and_checkpoints
pass 'At least 8 window results and 2 completed checkpoints'

STEP=4
require_request POST /api/chaos/late-events '{"count":20,"lateByMs":180000}'
wait_for 30 'Injected events did not produce LATE dead letters' late_results
pass 'Late events reached the dead-letter sink'

STEP=5
require_request GET /api/workers
WORKER_ID="$(jq -er '
  [ .[] | select(.alive == true and (.partitions | length) > 0) ] |
  sort_by(.partitions | length) | last | .workerId |
  select(type == "string" and test("^worker-[1-3]$"))
' <<< "$RESPONSE")" || fail 'No live partition-owning worker maps to a Compose worker-N service'
require_request GET /api/executions/current
jq -e --arg id "$EXECUTION_ID" '.id == $id and .state == "RUNNING"' \
  <<< "$RESPONSE" >/dev/null || fail 'Execution changed before worker kill'
GENERATION="$(jq -er '.generation | select(type == "number" and . >= 1)' <<< "$RESPONSE")"
CHECKPOINT="$(jq -er '.lastCompletedCheckpoint | select(type == "number" and . >= 2)' <<< "$RESPONSE")"
KILLED_AT=$SECONDS
# Compose WORKER_ID equals its service name; kill the actual container, not a mock/API command.
docker compose kill "$WORKER_ID" || fail "Could not kill Compose service $WORKER_ID"
REMAINING=$((20 - (SECONDS - KILLED_AT)))
(( REMAINING > 0 )) || fail 'Worker kill exceeded the 20s detection deadline'
wait_for "$REMAINING" 'Worker loss did not trigger recovery or a new generation' recovery_started
# Both recovery deadlines are measured from the kill, not from the preceding poll.
REMAINING=$((60 - (SECONDS - KILLED_AT)))
(( REMAINING > 0 )) || fail 'Recovery exceeded 60s'
wait_for "$REMAINING" 'Execution did not restore the checkpoint and resume RUNNING' recovery_finished
pass "$WORKER_ID killed; recovered in $((SECONDS - KILLED_AT))s from checkpoint >= $CHECKPOINT, generation > $GENERATION"

STEP=6
sleep 20
DEADLINE=$((SECONDS + 60))
while :; do
  if request POST /api/compare '{}'; then
    break
  fi
  if [[ "$HTTP_STATUS" == 404 ]]; then
    break
  fi
  [[ "$HTTP_STATUS" == 409 ]] || fail "Reference comparison failed (HTTP $HTTP_STATUS)"
  (( SECONDS < DEADLINE )) || fail 'Reference comparison stayed HTTP 409 for 60s'
  sleep 1
done
DEADLINE=0
if [[ "$HTTP_STATUS" == 404 ]]; then
  printf '%d SKIP: compare endpoint not available (404)\n' "$STEP"
else
  jq -e '.match == true and
    (.referenceResults | type == "number" and . > 0) and
    .matched == .referenceResults' <<< "$RESPONSE" >/dev/null || fail 'Engine/reference results differ or comparison is empty'
  MATCHED="$(jq -r '.matched' <<< "$RESPONSE")"
  pass "$MATCHED / $MATCHED results match"
fi

STEP=7
require_request POST /api/chaos/slow-sink '{"delayMs":200}'
# At 40 records/s shared by two survivors, a 1000-item queue needs about 60s
# to reach 90% while a 200ms sink delay drains only 5 items/s.
wait_for 120 'Slow sink did not produce backpressure' workers_backpressured
require_request POST /api/chaos/slow-sink '{"delayMs":0}'
wait_for 60 'Workers remained backpressured after clearing the sink delay' workers_drained
pass 'Slow sink caused backpressure; clearing delay drained it'

STEP=8
require_request GET /api/version
if [[ -n "${EXPECT_SHA:-}" ]]; then
  jq -e --arg sha "$EXPECT_SHA" '.commit == $sha' <<< "$RESPONSE" >/dev/null \
    || fail "Version commit does not equal EXPECT_SHA=$EXPECT_SHA"
  pass "Version commit equals $EXPECT_SHA"
else
  pass 'Version retrieved; commit assertion skipped (EXPECT_SHA unset)'
fi
