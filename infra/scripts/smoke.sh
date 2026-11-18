#!/usr/bin/env bash
# Infra smoke checks. Later tasks append app-service checks as functions and call them from main.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

DEADLINE=$((SECONDS + 120))
FAILURES=0

pass() { printf 'PASS %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

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

main() {
  if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    fail "docker compose is not available"
    exit 1
  fi

  wait_healthy kafka || true
  wait_healthy postgres || true
  wait_healthy minio || true
  check_kafka_topic || true
  check_postgres_executions || true
  check_minio_bucket || true
  # Later tasks: call additional check_* functions for app services here.

  if (( FAILURES > 0 )); then
    fail "smoke (${FAILURES} failed)"
    exit 1
  fi
  pass "smoke"
}

main
