#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="docker-compose.yml"
API="http://localhost:18080"
FROM_COLD=false
CLEAN=false

usage() {
  cat <<'USAGE'
Usage: cold-start-timer.sh [--compose-file FILE] [--api URL] [--from-cold] [--clean]
  --from-cold  Stop the selected Compose project before timing; volumes are kept.
  --clean      With --from-cold, also remove named volumes.
USAGE
}

while (($#)); do
  case "$1" in
    --compose-file)
      (($# >= 2)) || { usage >&2; exit 2; }
      COMPOSE_FILE="$2"
      shift 2
      ;;
    --api)
      (($# >= 2)) || { usage >&2; exit 2; }
      API="${2%/}"
      shift 2
      ;;
    --from-cold) FROM_COLD=true; shift ;;
    --clean) CLEAN=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done
if [[ "$CLEAN" == true && "$FROM_COLD" != true ]]; then
  printf '%s\n' '--clean requires --from-cold' >&2
  exit 2
fi

cd "$ROOT"
COMPOSE=(docker compose -f "$COMPOSE_FILE")
LOG_FILE="docs/expo/cold-start-times.md"
mkdir -p "$(dirname "$LOG_FILE")"

start=$SECONDS
printf 'Waiting for Docker engine...\n'
until docker info >/dev/null 2>&1; do sleep 2; done
engine_time=$SECONDS
if [[ "$FROM_COLD" == true ]]; then
  if [[ "$CLEAN" == true ]]; then
    "${COMPOSE[@]}" down -v
  else
    "${COMPOSE[@]}" down
  fi
fi
down_time=$SECONDS
printf 'Docker engine reachable. Starting Compose...\n'
"${COMPOSE[@]}" up -d
compose_time=$SECONDS

services_healthy() {
  local service id state
  while IFS= read -r service; do
    [[ -n "$service" ]] || continue
    id="$("${COMPOSE[@]}" ps -q "$service" 2>/dev/null || true)"
    [[ -n "$id" ]] || continue
    state="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || true)"
    case "$service:$state" in
      kafka-init:exited|minio-init:exited) ;;
      *:healthy) ;;
      *) return 1 ;;
    esac
  done < <("${COMPOSE[@]}" config --services)
}

printf 'Waiting for all Compose services to be healthy...\n'
health_deadline=$((SECONDS + 900))
until services_healthy; do
  if (( SECONDS >= health_deadline )); then
    printf 'Timed out waiting for Compose services to become healthy.\n' >&2
    "${COMPOSE[@]}" ps -a >&2 || true
    exit 1
  fi
  sleep 2
done
healthy_time=$SECONDS

printf 'Waiting for %s/api/health...\n' "$API"
api_deadline=$((SECONDS + 300))
until curl -fsS --max-time 5 "$API/api/health" >/dev/null; do
  if (( SECONDS >= api_deadline )); then
    printf 'Timed out waiting for API health at %s/api/health.\n' "$API" >&2
    exit 1
  fi
  sleep 2
done
api_time=$SECONDS

printf 'Waiting for demo readiness...\n'
DEMO_READY_OUTPUT="$(bash infra/scripts/demo-ready.sh "$API")"
printf '%s\n' "$DEMO_READY_OUTPUT"
grep -Fq 'DEMO READY' <<< "$DEMO_READY_OUTPUT" || {
  printf '%s\n' 'demo-ready.sh returned without printing DEMO READY' >&2
  exit 1
}
demo_time=$SECONDS

engine_s=$((engine_time - start))
down_s=$((down_time - engine_time))
compose_s=$((compose_time - down_time))
healthy_s=$((healthy_time - compose_time))
api_s=$((api_time - healthy_time))
demo_s=$((demo_time - api_time))
total_s=$((demo_time - start))
date_utc="$(date -u +%Y-%m-%d)"

printf '\n| Date (UTC) | Docker engine | Compose down | Compose up | Services healthy | API health | DEMO READY | Total |\n'
printf '|---|---:|---:|---:|---:|---:|---:|---:|\n'
printf '| %s | %ss | %ss | %ss | %ss | %ss | %ss | %ss |\n' \
  "$date_utc" "$engine_s" "$down_s" "$compose_s" "$healthy_s" "$api_s" "$demo_s" "$total_s"
{
  printf '| %s | %ss | %ss | %ss | %ss | %ss | %ss | %ss |\n' \
    "$date_utc" "$engine_s" "$down_s" "$compose_s" "$healthy_s" "$api_s" "$demo_s" "$total_s"
} >> "$LOG_FILE"
