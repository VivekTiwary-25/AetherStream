#!/usr/bin/env bash
# Usage: API=http://localhost:18080 DURATION_S=900 bash infra/scripts/soak.sh
# Requires an already healthy Compose stack; CI owns startup and volume cleanup.
# Every kill must fail over within 30s, then restart and regain partition ownership within 60s.
# Summary rejoin seconds are measured from the Compose start request, not from the kill.
# jq variables in single-quoted filters belong to jq, not the shell.
# shellcheck disable=SC2016
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
API="${API:-http://localhost:18080}"
API="${API%/}"
DURATION_S="${DURATION_S:-900}"
SUMMARY="$ROOT/soak-summary.md"
TEMP=''
RESPONSE=''
EXECUTION_ID=''
FAILURE='Unexpected command failure'
KILLS=0
RECOVERIES=0
REJOINS=0
RECOVERY_TOTAL=0
RECOVERY_MIN=0
RECOVERY_MAX=0
COMPARES=0
COMPARE_ATTEMPTS=0
LATE_SENT=0
SLOW_CYCLES=0
SLOW_UNTIL=0
RECOVERY_DEADLINE=0
REJOIN_DEADLINE=0
COMPARE_PID=''
COMPLETED='[]'
REJECTED='unavailable'
LATE='unavailable'
STARTED=$SECONDS

fail() { FAILURE="$*"; printf 'FAIL %s\n' "$FAILURE" >&2; exit 1; }

# A recovery poll must never let curl run past the 30-second kill deadline.
request() {
  local method="$1" path="$2" max_time="${4:-15}" status body_file="${REQUEST_BODY:-$TEMP/body}"
  local -a args=(-sS --connect-timeout 3 --max-time "$max_time" -X "$method")
  if (( $# >= 3 )); then args+=(-H 'Content-Type: application/json' --data "$3"); fi
  : > "$body_file"
  if ! status="$(curl "${args[@]}" -o "$body_file" -w '%{http_code}' "$API$path")"; then
    RESPONSE="$(<"$body_file")"
    printf '%s %s: curl failed: %s\n' "$method" "$path" "$RESPONSE" >&2
    return 1
  fi
  RESPONSE="$(<"$body_file")"
  if [[ "$status" != 2[0-9][0-9] ]]; then
    printf '%s %s: HTTP %s: %s\n' "$method" "$path" "$status" "$RESPONSE" >&2
    return 1
  fi
}

require_request() { request "$@" || fail "$1 $2 failed"; }
require_json() { jq -e "$@" <<< "$RESPONSE" >/dev/null || fail "Invalid response: $RESPONSE"; }

collect_counts() {
  require_request GET "/api/executions/$EXECUTION_ID/checkpoints?limit=500"
  require_json 'type == "array"'
  # The endpoint caps pages at 500; accumulate IDs every comparison, not just at exit.
  COMPLETED="$(jq -c --argjson previous "$COMPLETED" '
    ($previous + [.[] | select(.state == "COMPLETED" and .checkpointId > 0) | .checkpointId]) | unique
  ' <<< "$RESPONSE")"
  require_request GET /api/results/summary
  require_json --arg id "$EXECUTION_ID" '.executionId == $id and
    (.rejected | type == "number" and . >= 0) and (.late | type == "number" and . >= 0)'
  REJECTED="$(jq -r '.rejected' <<< "$RESPONSE")"
  LATE="$(jq -r '.late' <<< "$RESPONSE")"
}

finish() {
  local rc=$? inventory='[]' stats='[]' ids_text running_text kill
  local -a ids=() running=()
  trap - EXIT
  set +e
  # Reset chaos even on a mismatch, signal, or recovery timeout.
  if (( SLOW_UNTIL > 0 )) && [[ -n "$TEMP" ]]; then
    if ! request POST /api/chaos/slow-sink '{"delayMs":0}'; then
      rc=1; FAILURE="$FAILURE; could not clear slow sink"
    fi
  fi
  if [[ -n "$COMPARE_PID" ]]; then wait "$COMPARE_PID"; fi
  if command -v docker >/dev/null && command -v jq >/dev/null; then
    if ids_text="$(docker compose ps -aq)" && [[ -n "$ids_text" ]]; then
      mapfile -t ids <<< "$ids_text"
      if ! inventory="$(docker inspect --format \
        '{"name":{{json .Name}},"restarts":{{.RestartCount}},"running":{{.State.Running}},"id":{{json .Id}}}' \
        "${ids[@]}" | jq -s .)"; then
        rc=1; FAILURE="$FAILURE; docker inspect failed"; inventory='[]'
      fi
      running_text="$(jq -r '.[] | select(.running) | .id' <<< "$inventory")"
      if [[ -n "$running_text" ]]; then
        mapfile -t running <<< "$running_text"
        if ! stats="$(docker stats --no-stream --format '{{json .}}' "${running[@]}" | jq -s .)"; then
          rc=1; FAILURE="$FAILURE; docker stats failed"; stats='[]'
        fi
      else
        rc=1; FAILURE="$FAILURE; no running containers for memory sampling"
      fi
    else
      rc=1; FAILURE="$FAILURE; could not list Compose containers"
    fi
  else
    rc=1
  fi
  {
    printf '# Chaos soak summary\n\n'
    if (( rc == 0 )); then printf '**PASS**\n\n'; else printf '**FAIL**: %s\n\n' "$FAILURE"; fi
    printf '| Metric | Value |\n| --- | --- |\n'
    printf '| Requested / elapsed seconds | %s / %s |\n' "$DURATION_S" "$((SECONDS - STARTED))"
    printf '| Execution | `%s` |\n' "$EXECUTION_ID"
    printf '| Worker kills / failovers / rejoins | %s / %s / %s |\n' "$KILLS" "$RECOVERIES" "$REJOINS"
    if (( RECOVERIES > 0 )); then
      awk -v min="$RECOVERY_MIN" -v total="$RECOVERY_TOTAL" -v count="$RECOVERIES" -v max="$RECOVERY_MAX" \
        'BEGIN { printf "| Failover seconds min / avg / max | %d / %.2f / %d |\n", min, total/count, max }'
    else
      printf '| Failover seconds min / avg / max | n/a |\n'
    fi
    printf '| Compares matched / attempted | %s / %s |\n' "$COMPARES" "$COMPARE_ATTEMPTS"
    printf '| Completed checkpoints (excluding checkpoint 0) | %s |\n' "$(jq 'length' <<< "$COMPLETED")"
    printf '| Rejected / late records | %s / %s |\n' "$REJECTED" "$LATE"
    printf '| Late events injected / slow-sink cycles | %s / %s |\n' "$LATE_SENT" "$SLOW_CYCLES"
    printf '\n## Containers\n\n| Container | Restarts (docker inspect) | Memory (docker stats) | Memory %% |\n| --- | --- | --- | --- |\n'
    jq -r --argjson stats "$stats" '.[] | . as $c |
      ([$stats[] | select(.ID == $c.id or .Name == ($c.name | ltrimstr("/")))] | first) as $s |
      "| \($c.name | ltrimstr("/")) | \($c.restarts) | \($s.MemUsage // "not running / unavailable") | \($s.MemPerc // "n/a") |"' <<< "$inventory"
    if [[ -n "$TEMP" ]] && (( KILLS > 0 )); then
      printf '\n## Recoveries\n\nRejoin is measured from the restart request; pending means the phase did not complete.\n\n'
      printf '| Worker | Generation before | Failover generation | Failover s | Rejoin s |\n| --- | --- | --- | --- | --- |\n'
      for ((kill = 1; kill <= KILLS; kill++)); do cat "$TEMP/recovery-$kill"; done
    fi
  } > "$SUMMARY"
  cat "$SUMMARY"
  if [[ -n "$TEMP" ]]; then rm -rf "$TEMP"; fi
  exit "$rc"
}
trap finish EXIT
trap 'fail "Interrupted"' INT TERM

for tool in curl jq docker timeout awk; do command -v "$tool" >/dev/null || fail "Required command missing: $tool"; done
[[ "$DURATION_S" =~ ^[1-9][0-9]*$ && ${#DURATION_S} -le 7 ]] || fail 'DURATION_S must be a positive integer (at most 7 digits)'
TEMP="$(mktemp -d)"
require_request GET /api/ready
require_json '.status == "UP"'
require_request GET /api/pipelines/demo
PIPELINE="$RESPONSE"
require_request POST /api/pipelines/validate "$PIPELINE"
require_json '.valid == true'
require_request POST /api/pipelines "$PIPELINE"
PIPELINE_ID="$(jq -er '.id | select(type == "string" and length > 0) | @uri' <<< "$RESPONSE")"
IDEMPOTENCY_KEY="$(cat /proc/sys/kernel/random/uuid)"
require_request POST "/api/pipelines/$PIPELINE_ID/executions" \
  "$(jq -nc --arg key "$IDEMPOTENCY_KEY" '{idempotencyKey: $key}')"
require_json '.state == "RUNNING" and (.id | type == "string" and length > 0)'
EXECUTION_ID="$(jq -r '.id | @uri' <<< "$RESPONSE")"
collect_counts

# Compare asynchronously: reference replay must not extend a 30s sink pulse or block recovery polls.
start_compare() {
  [[ -z "$COMPARE_PID" ]] || fail 'Previous reference comparison has not finished'
  COMPARE_ATTEMPTS=$((COMPARE_ATTEMPTS + 1))
  rm -f "$TEMP/compare.done" "$TEMP/compare.error"
  (
    trap - EXIT INT TERM
    # Isolate the response file from requests in the scheduler.
    REQUEST_BODY="$TEMP/compare.body"
    if request POST /api/compare '{}' 60; then
      printf '%s' "$RESPONSE" > "$TEMP/compare.json"
    else
      printf '%s' "$RESPONSE" > "$TEMP/compare.error"
    fi
    touch "$TEMP/compare.done"
  ) &
  COMPARE_PID=$!
}

finish_compare() {
  [[ -n "$COMPARE_PID" && -f "$TEMP/compare.done" ]] || return 0
  wait "$COMPARE_PID" || fail 'Reference comparison process failed'
  COMPARE_PID=''
  [[ ! -f "$TEMP/compare.error" ]] || fail "Reference comparison request failed: $(<"$TEMP/compare.error")"
  RESPONSE="$(<"$TEMP/compare.json")"
  require_json '.match == true and (.mismatches | length) == 0 and .referenceResults > 0 and .recordsReplayed > 0'
  COMPARES=$((COMPARES + 1))
  printf 'PASS reference compare %s: %s\n' "$COMPARES" "$RESPONSE"
  collect_counts
}

STARTED=$SECONDS
END=$((STARTED + DURATION_S))
NEXT_KILL=$((STARTED + 90))
NEXT_SLOW=$((STARTED + 120))
NEXT_LATE=$((STARTED + 60))
NEXT_COMPARE=$((STARTED + 180))
while (( SECONDS < END || RECOVERY_DEADLINE > 0 || REJOIN_DEADLINE > 0 )); do
  if (( SLOW_UNTIL > 0 && (SECONDS >= SLOW_UNTIL || SECONDS >= END) )); then
    require_request POST /api/chaos/slow-sink '{"delayMs":0}'
    require_json '.delayMs == 0'
    SLOW_UNTIL=0
  fi
  finish_compare
  if (( RECOVERY_DEADLINE > 0 )); then
    REMAINING=$((RECOVERY_DEADLINE - SECONDS))
    (( REMAINING > 0 )) || fail "$WORKER did not recover RUNNING in 30s after generation $GENERATION"
    if request GET /api/executions/current '' "$REMAINING"; then
      require_json --arg id "$EXECUTION_ID" '.id == $id and .state != "FAILED" and .state != "STOPPED"'
      if jq -e --argjson generation "$GENERATION" '.state == "RUNNING" and .generation > $generation' <<< "$RESPONSE" >/dev/null; then
        RECOVERY_TIME=$((SECONDS - KILLED_AT))
        (( RECOVERY_TIME <= 30 )) || fail "$WORKER recovery exceeded 30s"
        RECOVERIES=$((RECOVERIES + 1))
        RECOVERY_TOTAL=$((RECOVERY_TOTAL + RECOVERY_TIME))
        if (( RECOVERIES == 1 || RECOVERY_TIME < RECOVERY_MIN )); then RECOVERY_MIN=$RECOVERY_TIME; fi
        if (( RECOVERY_TIME > RECOVERY_MAX )); then RECOVERY_MAX=$RECOVERY_TIME; fi
        FAILOVER_GENERATION="$(jq -r '.generation' <<< "$RESPONSE")"
        printf '| %s | %s | %s | %s | pending |\n' "$WORKER" "$GENERATION" "$FAILOVER_GENERATION" "$RECOVERY_TIME" > "$TEMP/recovery-$KILLS"
        printf 'PASS %s failed over in %ss\n' "$WORKER" "$RECOVERY_TIME"
        RECOVERY_DEADLINE=0
        # Compose kill is a manual stop; unless-stopped does not restart this container.
        RESTARTED_AT=$SECONDS
        REJOIN_DEADLINE=$((RESTARTED_AT + 60))
        timeout 60s docker compose start "$WORKER" || fail "Could not start $WORKER within 60s"
      fi
    fi
  fi
  if (( REJOIN_DEADLINE > 0 )); then
    REMAINING=$((REJOIN_DEADLINE - SECONDS))
    (( REMAINING > 0 )) || fail "$WORKER did not rejoin with partitions in 60s"
    if request GET /api/workers '' "$REMAINING" &&
      jq -e --arg worker "$WORKER" 'any(.[]; .workerId == $worker and .alive == true and
        .state == "RUNNING" and (.partitions | length) > 0)' <<< "$RESPONSE" >/dev/null; then
      REMAINING=$((REJOIN_DEADLINE - SECONDS))
      (( REMAINING > 0 )) || fail "$WORKER rejoin exceeded 60s"
      if request GET /api/executions/current '' "$REMAINING"; then
        require_json --arg id "$EXECUTION_ID" '.id == $id and .state != "FAILED" and .state != "STOPPED"'
        if jq -e --argjson generation "$FAILOVER_GENERATION" '.state == "RUNNING" and .generation > $generation' <<< "$RESPONSE" >/dev/null; then
          REJOIN_TIME=$((SECONDS - RESTARTED_AT))
          (( REJOIN_TIME <= 60 )) || fail "$WORKER rejoin exceeded 60s"
          REJOINS=$((REJOINS + 1))
          printf '| %s | %s | %s | %s | %s |\n' "$WORKER" "$GENERATION" "$FAILOVER_GENERATION" "$RECOVERY_TIME" "$REJOIN_TIME" > "$TEMP/recovery-$KILLS"
          printf 'PASS %s rejoined with partitions in %ss\n' "$WORKER" "$REJOIN_TIME"
          REJOIN_DEADLINE=0
        fi
      fi
    fi
  fi
  if (( SECONDS >= END )); then
    if (( RECOVERY_DEADLINE > 0 || REJOIN_DEADLINE > 0 )); then sleep 1; fi
    continue
  fi
  if (( SECONDS >= NEXT_LATE )); then
    require_request POST /api/chaos/late-events '{"count":10,"lateByMs":180000}'
    require_json '.sent == 10'
    LATE_SENT=$((LATE_SENT + 10))
    NEXT_LATE=$((NEXT_LATE + 60))
  fi
  if (( SECONDS >= NEXT_SLOW )); then
    # Arm cleanup before the request: a lost response may still have changed the server.
    SLOW_UNTIL=$((SECONDS + 30))
    require_request POST /api/chaos/slow-sink '{"delayMs":200}'
    require_json '.delayMs == 200'
    SLOW_CYCLES=$((SLOW_CYCLES + 1))
    NEXT_SLOW=$((NEXT_SLOW + 120))
  fi
  if (( SECONDS >= NEXT_KILL && RECOVERY_DEADLINE == 0 && REJOIN_DEADLINE == 0 )); then
    require_request GET /api/workers
    # Killing an idle worker cannot exercise generation recovery; choose a random live owner.
    WORKERS="$(jq -ce '[.[] | select(.alive == true and (.partitions | length) > 0) |
      .workerId | select(test("^worker-[1-3]$"))] | select(length > 0)' <<< "$RESPONSE")" || fail 'No alive partition-owning Compose worker'
    WORKER="$(jq -r --argjson random "$RANDOM" '.[$random % length]' <<< "$WORKERS")"
    require_request GET /api/executions/current
    require_json --arg id "$EXECUTION_ID" '.id == $id and .state == "RUNNING" and (.generation | type == "number" and . >= 1)'
    GENERATION="$(jq -r '.generation' <<< "$RESPONSE")"
    KILLED_AT=$SECONDS
    RECOVERY_DEADLINE=$((KILLED_AT + 30))
    timeout 30s docker compose kill "$WORKER" || fail "Could not kill $WORKER within 30s"
    KILLS=$((KILLS + 1))
    printf '| %s | %s | pending | pending | pending |\n' "$WORKER" "$GENERATION" > "$TEMP/recovery-$KILLS"
    NEXT_KILL=$((NEXT_KILL + 90))
  fi
  if (( SECONDS >= NEXT_COMPARE )); then
    start_compare
    NEXT_COMPARE=$((NEXT_COMPARE + 180))
  fi
  sleep 1
done

# Clear any final pulse and wait for a fresh completed checkpoint before the final comparison.
if (( SLOW_UNTIL > 0 )); then
  require_request POST /api/chaos/slow-sink '{"delayMs":0}'
  require_json '.delayMs == 0'
  SLOW_UNTIL=0
fi
if [[ -n "$COMPARE_PID" ]]; then wait "$COMPARE_PID"; finish_compare; fi
require_request GET /api/executions/current
require_json --arg id "$EXECUTION_ID" '.id == $id and .state == "RUNNING"'
FINAL_CHECKPOINT="$(jq -er '.lastCompletedCheckpoint | select(type == "number")' <<< "$RESPONSE")"
DRAIN_DEADLINE=$((SECONDS + 60))
while :; do
  REMAINING=$((DRAIN_DEADLINE - SECONDS))
  (( REMAINING > 0 )) || fail 'No fresh checkpoint / late-event drain within 60s'
  require_request GET /api/executions/current '' "$REMAINING"
  require_json --arg id "$EXECUTION_ID" '.id == $id and .state == "RUNNING"'
  if jq -e --argjson checkpoint "$FINAL_CHECKPOINT" '.lastCompletedCheckpoint > $checkpoint' <<< "$RESPONSE" >/dev/null; then
    collect_counts
    if (( LATE >= LATE_SENT )); then break; fi
  fi
  sleep 1
done
start_compare
wait "$COMPARE_PID"
finish_compare
(( KILLS == RECOVERIES && KILLS == REJOINS && COMPARES == COMPARE_ATTEMPTS )) || fail 'Incomplete failover, worker rejoin, or comparison'
[[ "$COMPLETED" != '[]' ]] || fail 'No completed checkpoints'
FAILURE=''
