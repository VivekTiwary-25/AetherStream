#!/usr/bin/env bash
set -euo pipefail

API="${1:-http://localhost:18080}"
API="${API%/}"
KILL_COUNT=20
FAILOVER_LIMIT=15
REJOIN_LIMIT=60
RESPONSE=''
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

fail() { printf 'FAIL %s\n' "$*" >&2; exit 1; }

for tool in curl jq awk sort; do
  command -v "$tool" >/dev/null || fail "Required command missing: $tool"
done

request() {
  local method="$1" path="$2" body="${3:-}" timeout="${4:-5}" status
  local -a args=(-sS --connect-timeout 2 --max-time "$timeout" -X "$method")
  if [[ -n "$body" ]]; then args+=(-H 'Content-Type: application/json' --data "$body"); fi
  : > "$BODY_FILE"
  if ! status="$(curl "${args[@]}" -o "$BODY_FILE" -w '%{http_code}' "$API$path")"; then
    RESPONSE="$(<"$BODY_FILE")"
    return 1
  fi
  RESPONSE="$(<"$BODY_FILE")"
  [[ "$status" == 2[0-9][0-9] ]]
}

require_request() {
  request "$@" || fail "$1 $2 failed: $RESPONSE"
}

wait_for_failover() {
  local timeout="$1"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if request GET /api/executions/current '' 3 &&
      jq -e --arg id "$EXECUTION_ID" --argjson generation "$OLD_GENERATION" \
        '.id == $id and .state == "RUNNING" and .generation > $generation' \
        <<< "$RESPONSE" >/dev/null; then
      FAILOVER_GENERATION="$(jq -r '.generation' <<< "$RESPONSE")"
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_rejoin() {
  local timeout="$1"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if request GET /api/workers '' 3 &&
      jq -e --arg worker "$WORKER" 'any(.[]; .workerId == $worker and .alive == true and
        .state == "RUNNING" and (.partitions | length) > 0)' <<< "$RESPONSE" >/dev/null; then
      if request GET /api/executions/current '' 3 &&
        jq -e --arg id "$EXECUTION_ID" --argjson generation "$FAILOVER_GENERATION" \
          '.id == $id and .state == "RUNNING" and .generation > $generation' \
          <<< "$RESPONSE" >/dev/null; then
        return 0
      fi
    fi
    sleep 1
  done
  return 1
}

wait_for_checkpoint() {
  local timeout="$1"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if request GET "/api/executions/$EXECUTION_PATH/checkpoints?limit=20" '' 3; then
      CHECKPOINT="$(jq -er --argjson previous "$PREVIOUS_CHECKPOINT" '
        [.[] | select(.state == "COMPLETED" and .checkpointId > $previous) | .checkpointId] | max // empty
      ' <<< "$RESPONSE" 2>/dev/null || true)"
      if [[ -n "$CHECKPOINT" ]]; then return 0; fi
    fi
    sleep 1
  done
  return 1
}

percentile() {
  local values="$1" percentile="$2"
  sort -n <<< "$values" | awk -v p="$percentile" 'NR == int((p * 20 + 99) / 100) { print; exit }'
}

require_request GET /api/ready
jq -e '.status == "UP"' <<< "$RESPONSE" >/dev/null || fail 'Control API is not ready'
require_request GET /api/executions/current
jq -e '.state == "RUNNING" and (.generation | type == "number")' <<< "$RESPONSE" >/dev/null \
  || fail 'No RUNNING execution'
EXECUTION_ID="$(jq -er '.id | select(type == "string" and length > 0)' <<< "$RESPONSE")"
EXECUTION_PATH="$(jq -nr --arg id "$EXECUTION_ID" '$id | @uri')"
PREVIOUS_CHECKPOINT="$(jq -er '.lastCompletedCheckpoint | select(type == "number")' <<< "$RESPONSE")"

FAILOVER_TIMES=()
REJOIN_TIMES=()
WORKER_HISTORY=()
for ((kill = 1; kill <= KILL_COUNT; kill++)); do
  require_request GET /api/workers
  TARGETS="$(jq -ce '[.[] | select(.alive == true and .state == "RUNNING" and
    (.partitions | length) > 0) | .workerId] | sort | select(length > 0)' <<< "$RESPONSE")" \
    || fail 'No live partition-owning workers'
  TARGET_COUNT="$(jq 'length' <<< "$TARGETS")"
  WORKER="$(jq -r --argjson index "$(((kill - 1) % TARGET_COUNT))" '.[$index]' <<< "$TARGETS")"

  require_request GET /api/executions/current
  jq -e --arg id "$EXECUTION_ID" '.id == $id and .state == "RUNNING" and
    (.generation | type == "number")' <<< "$RESPONSE" >/dev/null \
    || fail 'Execution changed or stopped before worker kill'
  OLD_GENERATION="$(jq -r '.generation' <<< "$RESPONSE")"

  KILLED_AT=$SECONDS
  require_request POST /api/chaos/kill-worker \
    "$(jq -nc --arg worker "$WORKER" '{workerId: $worker}')"
  jq -e --arg worker "$WORKER" '.workerId == $worker' <<< "$RESPONSE" >/dev/null \
    || fail "Kill API did not acknowledge worker $WORKER"

  if ! wait_for_failover "$FAILOVER_LIMIT"; then
    fail "$WORKER did not reach RUNNING in a newer generation within ${FAILOVER_LIMIT}s"
  fi
  FAILOVER_TIME=$((SECONDS - KILLED_AT))
  (( FAILOVER_TIME <= FAILOVER_LIMIT )) || fail "$WORKER failover took ${FAILOVER_TIME}s"

  if ! wait_for_rejoin "$REJOIN_LIMIT"; then
    fail "$WORKER did not rejoin and own partitions in a newer generation within ${REJOIN_LIMIT}s"
  fi
  REJOIN_TIME=$((SECONDS - KILLED_AT))
  (( REJOIN_TIME <= REJOIN_LIMIT )) || fail "$WORKER rejoin took ${REJOIN_TIME}s"

  FAILOVER_TIMES+=("$FAILOVER_TIME")
  REJOIN_TIMES+=("$REJOIN_TIME")
  WORKER_HISTORY+=("$WORKER")
  printf 'PASS kill %d/%d: %s failover=%ss rejoin=%ss\n' \
    "$kill" "$KILL_COUNT" "$WORKER" "$FAILOVER_TIME" "$REJOIN_TIME"

  if ! wait_for_checkpoint 60; then
    fail "No new completed checkpoint after kill $kill within 60s"
  fi
  PREVIOUS_CHECKPOINT="$CHECKPOINT"
done

require_request POST /api/compare '{}'
jq -e '.match == true and .referenceResults > 0 and
  .matched == .referenceResults and .engineResults == .referenceResults and
  (.mismatches | type == "array" and length == 0)' <<< "$RESPONSE" >/dev/null \
  || fail 'Final compare is empty or mismatched'
MATCHED="$(jq -r '.matched' <<< "$RESPONSE")"

printf '\n| Kill # | Worker | Failover s | Rejoin s |\n| ---: | --- | ---: | ---: |\n'
for ((row = 1; row <= KILL_COUNT; row++)); do
  printf '| %d | %s | %s | %s |\n' "$row" "${WORKER_HISTORY[$((row - 1))]}" \
    "${FAILOVER_TIMES[$((row - 1))]}" "${REJOIN_TIMES[$((row - 1))]}"
done
FAILOVER_VALUES="$(printf '%s\n' "${FAILOVER_TIMES[@]}")"
REJOIN_VALUES="$(printf '%s\n' "${REJOIN_TIMES[@]}")"
FAILOVER_MIN="$(sort -n <<< "$FAILOVER_VALUES" | head -n1)"
FAILOVER_MAX="$(sort -n <<< "$FAILOVER_VALUES" | tail -n1)"
REJOIN_MIN="$(sort -n <<< "$REJOIN_VALUES" | head -n1)"
REJOIN_MAX="$(sort -n <<< "$REJOIN_VALUES" | tail -n1)"
FAILOVER_MEDIAN="$(sort -n <<< "$FAILOVER_VALUES" | awk 'NR == 10 { a=$1 } NR == 11 { print (a+$1)/2 }')"
REJOIN_MEDIAN="$(sort -n <<< "$REJOIN_VALUES" | awk 'NR == 10 { a=$1 } NR == 11 { print (a+$1)/2 }')"
FAILOVER_P95="$(percentile "$FAILOVER_VALUES" 95)"
REJOIN_P95="$(percentile "$REJOIN_VALUES" 95)"
printf '\n| Metric | Min | Median | P95 | Max |\n| --- | ---: | ---: | ---: | ---: |\n'
printf '| Failover (s) | %s | %s | %s | %s |\n' "$FAILOVER_MIN" "$FAILOVER_MEDIAN" "$FAILOVER_P95" "$FAILOVER_MAX"
printf '| Rejoin (s) | %s | %s | %s | %s |\n' "$REJOIN_MIN" "$REJOIN_MEDIAN" "$REJOIN_P95" "$REJOIN_MAX"
printf '\nCompare: %s/%s matched; all 20 kills recovered.\n' "$MATCHED" "$MATCHED"
