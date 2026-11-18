#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
API="${API:-http://localhost:18080}"
API="${API%/}"
# Steps 6 and 9 require nonempty reference parity; steps 9-11 exercise
# durable restore, controlled partition reassignment, and simulator scenario reset.
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
  if [[ "$path" == /api/executions/*/savepoints ]]; then timeout=30; fi
  if [[ "$path" == /api/executions/*/restore ]]; then timeout=35; fi
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

compare_results() {
  local allow_missing="${1:-false}"
  DEADLINE=$((SECONDS + 60))
  while :; do
    if request POST /api/compare '{}'; then
      break
    fi
    if [[ "$HTTP_STATUS" == 404 && "$allow_missing" == true ]]; then
      printf 'WARNING: step %d comparison NOT VERIFIED: /api/compare returned 404\n' "$STEP" >&2
      printf '%d SKIP: compare endpoint not available (404)\n' "$STEP"
      DEADLINE=0
      return 0
    fi
    [[ "$HTTP_STATUS" == 409 ]] || fail "Reference comparison failed (HTTP $HTTP_STATUS)"
    (( SECONDS < DEADLINE )) || fail 'Reference comparison stayed HTTP 409 for 60s'
    sleep 1
  done
  DEADLINE=0
  jq -e '.match == true and
    (.referenceResults | type == "number" and . > 0) and
    .matched == .referenceResults' <<< "$RESPONSE" >/dev/null \
    || fail 'Engine/reference results differ or comparison is empty'
  MATCHED="$(jq -r '.matched' <<< "$RESPONSE")"
}

restored_windows_arriving() {
  local count
  request GET /api/executions/current || return 1
  jq -e --arg id "$EXECUTION_ID" '.id == $id and .state == "RUNNING"' \
    <<< "$RESPONSE" >/dev/null || return 1
  request GET /api/results/summary || return 1
  count="$(jq -er --arg id "$EXECUTION_ID" '
    select(.executionId == $id) | .windowsWritten | select(type == "number" and . > 0)
  ' <<< "$RESPONSE")" || return 1
  if [[ -z "$WINDOW_BASELINE" ]]; then
    WINDOW_BASELINE="$count"
    return 1
  fi
  jq -en --argjson count "$count" --argjson baseline "$WINDOW_BASELINE" \
    '$count > $baseline' >/dev/null
}

worker_rejoined() {
  request GET /api/workers || return 1
  jq -e --arg id "$WORKER_ID" '
    type == "array" and any(.[]; .workerId == $id and .alive == true)
  ' <<< "$RESPONSE" >/dev/null
}

rescale_finished() {
  request GET /api/executions/current || return 1
  jq -e --arg id "$EXECUTION_ID" --argjson generation "$GENERATION" '
    .id == $id and .state == "RUNNING" and
    (.generation | type == "number" and . > $generation)
  ' <<< "$RESPONSE" >/dev/null || return 1
  request GET /api/workers || return 1
  # Exact multiset equality detects missing, duplicated and out-of-range partitions.
  jq -e '
    type == "array" and
    ([.[] | select(.alive == true)] |
      length == 3 and all(.[]; .state == "RUNNING" and
        (.partitions | type == "array" and length > 0)) and
      ([.[].partitions[]] | sort) == [0, 1, 2, 3, 4, 5])
  ' <<< "$RESPONSE" >/dev/null
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
compare_results true
if [[ "$HTTP_STATUS" != 404 ]]; then
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

STEP=9
require_request POST "/api/executions/$EXECUTION_ID/savepoints"
SAVEPOINT_ID="$(jq -er '.savepointId | select(type == "string" and length > 0)' <<< "$RESPONSE")"
require_request GET /api/savepoints
jq -e --arg id "$SAVEPOINT_ID" 'type == "array" and any(.[]; .savepointId == $id)' \
  <<< "$RESPONSE" >/dev/null || fail 'Created savepoint is missing from the savepoint list'
PREVIOUS_EXECUTION_ID="$EXECUTION_ID"
IDEMPOTENCY_KEY="$(cat /proc/sys/kernel/random/uuid)"
RESTORE_STARTED=$SECONDS
DEADLINE=$((RESTORE_STARTED + 60))
require_request POST "/api/executions/$EXECUTION_ID/restore" \
  "$(jq -nc --arg id "$SAVEPOINT_ID" --arg key "$IDEMPOTENCY_KEY" \
    '{savepointId: $id, idempotencyKey: $key}')"
EXECUTION_ID="$(jq -er --arg previous "$PREVIOUS_EXECUTION_ID" '
  .id | select(type == "string" and length > 0 and . != $previous)
' <<< "$RESPONSE")" || fail 'Savepoint restore did not create a new execution'
WINDOW_BASELINE=''
REMAINING=$((60 - (SECONDS - RESTORE_STARTED)))
(( REMAINING > 0 )) || fail 'Savepoint restore exceeded 60s'
wait_for "$REMAINING" 'Restored execution did not become RUNNING with windows continuing to arrive' restored_windows_arriving
wait_for 60 'Restored execution did not complete checkpoints with window results' results_and_checkpoints
compare_results
pass "Savepoint $SAVEPOINT_ID restored as $EXECUTION_ID; windows resumed; $MATCHED / $MATCHED results match"

STEP=10
require_request GET /api/executions/current
jq -e --arg id "$EXECUTION_ID" '.id == $id and .state == "RUNNING"' \
  <<< "$RESPONSE" >/dev/null || fail 'Execution changed before rescale'
GENERATION="$(jq -er '.generation | select(type == "number" and . >= 1)' <<< "$RESPONSE")"
# Return the worker killed in step 5 so rescale has a new member to assign.
# Request immediately on rejoin, before the coordinator's 5s auto-rebalance grace expires.
docker compose start "$WORKER_ID" || fail "Could not restart Compose service $WORKER_ID"
wait_for 60 'Killed worker did not rejoin for rescale' worker_rejoined
RESCALE_STARTED=$SECONDS
DEADLINE=$((RESCALE_STARTED + 60))
require_request POST "/api/executions/$EXECUTION_ID/rescale"
REMAINING=$((60 - (SECONDS - RESCALE_STARTED)))
(( REMAINING > 0 )) || fail 'Rescale exceeded 60s'
wait_for "$REMAINING" 'Rescale did not increase generation and uniquely assign all 6 partitions to live workers' rescale_finished
pass "Rescaled to 3 live workers with all 6 partitions assigned exactly once; generation > $GENERATION"

STEP=11
require_request POST /api/simulator/scenario '{"name":"rush-hour"}'
[[ "$HTTP_STATUS" == 200 ]] || fail "Rush-hour scenario returned HTTP $HTTP_STATUS, expected 200"
require_request GET /api/simulator/state
jq -e '.scenario == "rush-hour"' <<< "$RESPONSE" >/dev/null || fail 'Simulator did not enter rush-hour'
require_request POST /api/simulator/scenario '{"name":"reset"}'
[[ "$HTTP_STATUS" == 200 ]] || fail "Scenario reset returned HTTP $HTTP_STATUS, expected 200"
require_request GET /api/simulator/state
jq -e '.scenario == "none"' <<< "$RESPONSE" >/dev/null || fail 'Simulator scenario did not reset'
pass 'Rush-hour scenario applied and verified; reset restored scenario none'
