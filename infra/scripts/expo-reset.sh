#!/usr/bin/env bash
# Reset the local Compose demo; --api selects its control-api origin (no /api suffix).
set -euo pipefail

fail() {
  printf 'DEMO NOT READY: %s\n' "$*" >&2
  exit 1
}

step() {
  printf '\n==> %s\n' "$*"
}

# Bound Docker, API calls and demo-ready together, not just the cleanup phase.
run() {
  local remaining=$((58 - SECONDS))
  (( remaining > 0 )) || fail 'Reset exceeded its 58s budget'
  timeout --kill-after=1 "${remaining}s" "$@" || fail "Command failed or timed out: $*"
}

# Native jq.exe otherwise returns CRLF under Git Bash.
jq() {
  command jq --binary "$@"
}

request() {
  local method="$1" path="$2" status
  local -a args=(-sS --connect-timeout 2 --max-time 5 -X "$method")
  if (( $# == 3 )); then
    args+=(-H 'Content-Type: application/json' --data "$3")
  fi
  status="$(run curl "${args[@]}" -o "$BODY_FILE" -w '%{http_code}' "$API$path")"
  RESPONSE="$(<"$BODY_FILE")"
  [[ "$status" == 2[0-9][0-9] ]] || fail "$method $path failed (HTTP $status): $RESPONSE"
}

psql_demo() {
  run docker compose exec -T postgres psql -X -U aether -d aether -v ON_ERROR_STOP=1 "$@"
}

main() {
  API="${API:-http://localhost:18080}"
  local hard=false dry_run=false ids id path trucks
  while (( $# > 0 )); do
    case "$1" in
      --api)
        (( $# >= 2 )) && [[ -n "$2" && "$2" != --* ]] || fail '--api requires a URL'
        API="$2"; shift 2 ;;
      --hard) hard=true; shift ;;
      --dry-run) dry_run=true; shift ;;
      *) fail "Unknown argument: $1 (use --api URL, --hard, --dry-run)" ;;
    esac
  done
  API="${API%/}"
  [[ "$API" == http://* || "$API" == https://* ]] || fail '--api must be an HTTP(S) origin'
  local root
  root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
  cd -- "$root"
  local sql='TRUNCATE TABLE window_results, alerts, dead_letters;'
  if [[ "$hard" == true ]]; then
    sql='TRUNCATE TABLE window_results, alerts, dead_letters, checkpoints, executions, pipelines, audit_log;'
  fi

  if [[ "$dry_run" == true ]]; then
    step "DRY RUN: target $API; no commands executed"
    step 'Disable slow sink via API (coordinator API if no active execution)'
    step 'Stop all active demo executions via POST /api/executions/{id}/stop'
    step 'docker compose start worker-1 worker-2 worker-3; wait for all three alive and IDLE'
    step "docker compose exec -T postgres psql -X -U aether -d aether -v ON_ERROR_STOP=1 -c '$sql'"
    step 'Compare cache: none (reference comparisons are computed per request)'
    step 'Remove extra trucks via simulator DELETE /trucks/{id}; POST /api/simulator/scenario {"name":"reset"}'
    step 'Simulator POST /control {"running":true,"timeScale":10}; verify truck-001..truck-008'
    step "bash infra/scripts/demo-ready.sh $API (within remaining 58s budget)"
    printf '\nDRY RUN complete; DEMO READY not checked\n'
    return
  fi

  local tool
  for tool in curl jq docker timeout; do
    type -P "$tool" >/dev/null || fail "Required command missing: $tool"
  done
  BODY_FILE="$(mktemp)"
  trap 'rm -f "$BODY_FILE"' EXIT

  step 'Disable slow sink'
  request GET /api/executions/current
  if [[ -n "$RESPONSE" ]] && jq -e '.state == "RUNNING" or .state == "RECOVERING"' <<< "$RESPONSE" >/dev/null; then
    request POST /api/chaos/slow-sink '{"delayMs":0}'
  else
    # Contract 04's chaos endpoint refuses requests without an active execution.
    run docker compose exec -T coordinator curl -fsS --max-time 5 \
      -H 'Content-Type: application/json' --data '{"delayMs":0}' \
      http://localhost:8081/coord/chaos/sink-delay
  fi

  step 'Stop active demo executions via API'
  # Current returns only one execution; enumerate any additional active rows as well.
  ids="$(psql_demo -Atc "SELECT id FROM executions WHERE org_id = 'demo' AND state IN ('CREATED', 'RUNNING', 'RECOVERING') ORDER BY id;")"
  ids="${ids//$'\r'/}"
  while IFS= read -r id; do
    [[ -n "$id" ]] || continue
    path="$(jq -nr --arg id "$id" '$id | @uri')"
    request POST "/api/executions/$path/stop"
    jq -e '.state == "STOPPED"' <<< "$RESPONSE" >/dev/null || fail "Execution $id did not stop"
    printf 'Stopped %s\n' "$id"
  done <<< "$ids"
  # Do not erase shared results while an execution outside the demo org is active.
  ids="$(psql_demo -Atc "SELECT count(*) FROM executions WHERE state IN ('CREATED', 'RUNNING', 'RECOVERING');")"
  [[ "${ids//$'\r'/}" == 0 ]] || fail 'Active non-demo executions remain; refusing to truncate shared tables'

  step 'Start all workers and wait for cancellation to finish'
  run docker compose start worker-1 worker-2 worker-3
  while :; do
    request GET /api/workers
    if jq -e '
      [.[] | select(.alive == true and .state == "IDLE") | .workerId] as $idle |
      ["worker-1", "worker-2", "worker-3"] - $idle | length == 0
    ' <<< "$RESPONSE" >/dev/null; then break; fi
    run sleep 1
  done

  step 'Clear result tables (after workers are IDLE, so no stale writes can repopulate them)'
  psql_demo -c "SET lock_timeout = '3s'; SET statement_timeout = '5s'; $sql"
  step 'Compare cache: none (reference comparisons are computed per request)'

  step 'Remove extra trucks and restore seeded default fleet'
  request GET /api/simulator/state
  trucks="$(jq -er '[.trucks[] | .truckId | select(test("^truck-00[1-8]$") | not) | @uri] | join("\n")' <<< "$RESPONSE")"
  while IFS= read -r id; do
    [[ -n "$id" ]] || continue
    run docker compose exec -T simulator curl -fsS --max-time 5 -X DELETE "http://localhost:8070/trucks/$id"
  done <<< "$trucks"
  # Reset rebuilds trucks from the configured seed, preserving monotonic event time/IDs.
  request POST /api/simulator/scenario '{"name":"reset"}'
  run docker compose exec -T simulator curl -fsS --max-time 5 \
    -H 'Content-Type: application/json' --data '{"running":true,"timeScale":10}' \
    http://localhost:8070/control
  request GET /api/simulator/state
  jq -e '
    .running == true and .timeScale == 10 and .scenario == "none" and
    ([.trucks[].truckId] | sort) == ["truck-001", "truck-002", "truck-003", "truck-004",
      "truck-005", "truck-006", "truck-007", "truck-008"]
  ' <<< "$RESPONSE" >/dev/null || fail 'Simulator did not restore the default fleet/control state'

  step 'Start and verify a fresh demo pipeline'
  run bash infra/scripts/demo-ready.sh "$API"
}

trap 'fail "Unexpected failure at line $LINENO"' ERR
main "$@"
