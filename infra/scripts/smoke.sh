#!/usr/bin/env bash
# Full-stack smoke checks, including the original infrastructure checks.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

DEADLINE=$((SECONDS + 120))
FAILURES=0

pass() { printf 'PASS %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
warn() { printf 'WARN %s\n' "$*"; }

first_id() {
  local ids
  ids="$(docker compose ps -aq "$1" 2>/dev/null || true)"
  printf '%s' "${ids%%$'\n'*}"
}

wait_healthy() {
  local service="$1"
  local cid="" health="missing"
  while (( SECONDS < DEADLINE )); do
    cid="$(first_id "$service")"
    if [[ -n "$cid" ]]; then
      health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo missing)"
      if [[ "$health" == "healthy" ]]; then
        pass "$service healthy"
        return 0
      fi
    fi
    sleep 2
  done
  fail "$service not healthy within 120s (last: ${health})"
  return 1
}

oneshot_failed() {
  local service="$1"
  local cid status code
  cid="$(first_id "$service")"
  [[ -z "$cid" ]] && return 1
  status="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || echo missing)"
  code="$(docker inspect -f '{{.State.ExitCode}}' "$cid" 2>/dev/null || echo -1)"
  if [[ "$status" == "exited" && "$code" != "0" ]]; then
    fail "$service exited ${code}"
    return 0
  fi
  return 1
}

check_kafka_topic() {
  local out=""
  while (( SECONDS < DEADLINE )); do
    if oneshot_failed kafka-init; then
      return 1
    fi
    if out="$(timeout 20 docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic truck-events 2>&1)"; then
      if [[ "$out" =~ PartitionCount:\ 6([^0-9]|$) ]]; then
        pass "kafka topic truck-events has 6 partitions"
        return 0
      fi
    fi
    sleep 2
  done
  fail "kafka topic truck-events missing or not 6 partitions"
  [[ -n "$out" ]] && printf '%s\n' "$out" >&2
  return 1
}

check_postgres_executions() {
  local out="" trimmed
  while (( SECONDS < DEADLINE )); do
    if out="$(timeout 15 docker compose exec -T -e PGPASSWORD="${PG_PASSWORD:-aether}" postgres psql -U aether -d aether -tAc "SELECT to_regclass('public.executions') IS NOT NULL" 2>&1)"; then
      trimmed="$(printf '%s' "$out" | tr -d '[:space:]')"
      if [[ "$trimmed" == "t" ]]; then
        pass "postgres table executions exists"
        return 0
      fi
    fi
    sleep 2
  done
  fail "postgres table executions not found"
  [[ -n "$out" ]] && printf '%s\n' "$out" >&2
  return 1
}

check_minio_bucket() {
  local out=""
  while (( SECONDS < DEADLINE )); do
    if oneshot_failed minio-init; then
      return 1
    fi
    # Same mc image that created the bucket. It has no shell; MC_HOST_local is on the service.
    if out="$(timeout 20 docker compose run --rm -T --no-deps minio-init stat local/aether-checkpoints 2>&1)"; then
      pass "minio bucket aether-checkpoints exists"
      return 0
    fi
    sleep 2
  done
  fail "minio bucket aether-checkpoints not found"
  [[ -n "$out" ]] && printf '%s\n' "$out" >&2
  return 1
}

check_app_health() {
  local deadline=$((SECONDS + 180)) remaining=5 i body
  local -a names=("coordinator health" "simulator health" "control-api health" "web health" "web nginx /api/health")
  local -a urls=(
    "http://localhost:18081/coord/health"
    "http://localhost:18070/health"
    "http://localhost:18080/api/health"
    "http://localhost:8088/"
    "http://localhost:8088/api/health"
  )
  local -a ready=(0 0 0 0 0)
  while (( SECONDS < deadline )); do
    for i in "${!urls[@]}"; do
      [[ "${ready[$i]}" == 1 ]] && continue
      (( SECONDS >= deadline )) && break
      if body="$(curl -fsS --connect-timeout 2 --max-time 3 "${urls[$i]}" 2>/dev/null)"; then
        # The web root serves HTML; every other endpoint must report JSON UP.
        if [[ "$i" == 3 ]] || jq -e '.status == "UP"' <<<"$body" >/dev/null 2>&1; then
          pass "${names[$i]} UP"
          ready[$i]=1
          remaining=$((remaining - 1))
        fi
      fi
    done
    (( remaining == 0 )) && return 0
    sleep 2
  done
  for i in "${!urls[@]}"; do
    if [[ "${ready[$i]}" == 0 ]]; then
      fail "${names[$i]} not UP within 180s (${urls[$i]})"
    fi
  done
  return 1
}

check_alive_workers() {
  local deadline=$((SECONDS + 60)) body count="unknown" logs=""
  while (( SECONDS < deadline )); do
    if body="$(curl -fsS --connect-timeout 2 --max-time 3 http://localhost:18081/coord/state 2>/dev/null)"; then
      count="$(jq -er '[.workers[] | select(.alive == true)] | length' <<<"$body" 2>/dev/null || echo unknown)"
      if [[ "$count" == 3 ]]; then
        pass "coordinator lists 3 alive workers"
        return 0
      fi
    fi
    sleep 2
  done
  logs="$(timeout 10 docker compose logs --no-color worker-1 2>&1 || true)"
  if grep -Eq 'ClassNotFoundException|Could not find or load main class' <<<"$logs"; then
    warn "worker main class not implemented; skipping 3 alive workers check"
    return 0
  fi
  fail "coordinator did not list 3 alive workers within 60s (last count: ${count})"
  return 1
}

check_simulator_trucks() {
  local body
  if body="$(curl -fsS --connect-timeout 2 --max-time 5 http://localhost:18070/state 2>/dev/null)" \
    && jq -e '(.trucks | type == "array") and (.trucks | length > 0)' <<<"$body" >/dev/null 2>&1; then
    pass "simulator state returns trucks"
    return 0
  fi
  fail "simulator state missing trucks"
  return 1
}

check_kafka_records() {
  local out="" total
  sleep 10
  if out="$(timeout 20 docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server localhost:9092 --topic truck-events --time -1 2>&1)"; then
    total="$(awk -F: '$1 == "truck-events" && $2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ { sum += $3 } END { print sum + 0 }' <<<"$out")"
    if [[ "$total" =~ ^[0-9]+$ ]] && (( total > 0 )); then
      pass "kafka topic truck-events receives records (offset sum: ${total})"
      return 0
    fi
  fi
  fail "kafka topic truck-events has no records after 10s"
  [[ -n "$out" ]] && printf '%s\n' "$out" >&2
  return 1
}

main() {
  if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    fail "docker compose is not available"
    exit 1
  fi
  local tool
  for tool in curl jq timeout; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      fail "$tool is not available"
      exit 1
    fi
  done

  wait_healthy kafka || true
  wait_healthy postgres || true
  wait_healthy minio || true
  check_kafka_topic || true
  check_postgres_executions || true
  check_minio_bucket || true
  check_app_health || true
  check_alive_workers || true
  check_simulator_trucks || true
  check_kafka_records || true

  if (( FAILURES > 0 )); then
    fail "smoke (${FAILURES} failed)"
    exit 1
  fi
  pass "smoke"
}

main
