#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
API="${API:-http://localhost:18080}"
API="${API%/}"
DURATION_MINUTES="${DURATION_MINUTES:-60}"
CSV="${CSV:-$ROOT/longrun.csv}"
SUMMARY="${GITHUB_STEP_SUMMARY:-$ROOT/longrun-summary.md}"
TEMP="$(mktemp -d)"
FAILURE='Unexpected command failure'
LAST_CHECKPOINT=''
LAST_ADVANCED_AT=0

fail() { FAILURE="$*"; printf 'FAIL %s\n' "$FAILURE" >&2; exit 1; }
cleanup() { rm -rf "$TEMP"; }
trap cleanup EXIT
trap 'fail "Interrupted"' INT TERM
trap 'fail "Unexpected failure at line $LINENO"' ERR

for tool in curl jq docker awk cut sleep date tr; do
  command -v "$tool" >/dev/null || fail "Required command missing: $tool"
done
[[ "$DURATION_MINUTES" =~ ^[1-9][0-9]*$ && "$DURATION_MINUTES" -ge 10 ]] || fail 'DURATION_MINUTES must be an integer of at least 10'

request() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS --max-time 20 -o "$TEMP/response.json" -w '%{http_code}' -X "$method")
  if [[ -n "$body" ]]; then args+=(-H 'Content-Type: application/json' --data "$body"); fi
  HTTP_STATUS="$(curl "${args[@]}" "$API$path")" || return 1
  RESPONSE="$(cat "$TEMP/response.json")"
  [[ "$HTTP_STATUS" =~ ^2 ]] || return 1
}

require_request() { request "$@" || fail "$1 $2 failed (HTTP ${HTTP_STATUS:-unknown}): ${RESPONSE:-no response}"; }

bytes_from_size() {
  awk -v value="$1" 'BEGIN {
    if (value ~ /KiB$/) { sub(/KiB$/, "", value); value *= 1024 }
    else if (value ~ /MiB$/) { sub(/MiB$/, "", value); value *= 1048576 }
    else if (value ~ /GiB$/) { sub(/GiB$/, "", value); value *= 1073741824 }
    else if (value ~ /TiB$/) { sub(/TiB$/, "", value); value *= 1099511627776 }
    else if (value ~ /kB$/) { sub(/kB$/, "", value); value *= 1000 }
    else if (value ~ /MB$/) { sub(/MB$/, "", value); value *= 1000000 }
    else if (value ~ /GB$/) { sub(/GB$/, "", value); value *= 1000000000 }
    else if (value ~ /B$/) sub(/B$/, "", value)
    printf "%.0f", value
  }'
}

memory_at() {
  awk -F, -v wanted="$1" -v minute="$2" '$1 == minute && $3 == wanted { value=$4 } END { if (value == "") exit 1; print value }' "$CSV"
}

printf 'minute,timestamp,container,memory_bytes,cpu_percent,events_per_sec,windows_written,checkpoint,kafka_disk_bytes,postgres_table_bytes\n' > "$CSV"

# Match the e2e job: build all services, then execute the existing readiness and parity gate.
docker compose up -d --build
bash infra/scripts/demo-ready.sh "$API"

printf '## 60-minute long-run stability\n\n' >> "$SUMMARY"
printf 'Recorded every 60 seconds. Per-container memory/CPU are in the raw CSV.\n\n' >> "$SUMMARY"
if [[ -n "${GITHUB_SERVER_URL:-}" && -n "${GITHUB_REPOSITORY:-}" && -n "${GITHUB_RUN_ID:-}" ]]; then
  printf '[Workflow run](%s/%s/actions/runs/%s).\n\n' \
    "$GITHUB_SERVER_URL" "$GITHUB_REPOSITORY" "$GITHUB_RUN_ID" >> "$SUMMARY"
fi
printf '| Min | Container | Memory (MiB) | CPU | Events/s | Windows | Checkpoint | Kafka disk (MiB) | Postgres tables (MiB) |\n' >> "$SUMMARY"
printf '|---:|---|---:|---:|---:|---:|---:|---:|---:|\n' >> "$SUMMARY"

STARTED=$SECONDS
for ((minute = 0; minute <= DURATION_MINUTES; minute++)); do
  NOW="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
  require_request GET /api/results/summary
  EVENTS="$(jq -er '.eventsPerSec | numbers' <<< "$RESPONSE")" || fail 'API summary has no eventsPerSec'
  WINDOWS="$(jq -er '.windowsWritten | numbers' <<< "$RESPONSE")" || fail 'API summary has no windowsWritten'
  require_request GET /api/executions/current
  CHECKPOINT="$(jq -er '.lastCompletedCheckpoint | numbers' <<< "$RESPONSE")" || fail 'Execution has no completed checkpoint'
  if [[ -z "$LAST_CHECKPOINT" || "$CHECKPOINT" -gt "$LAST_CHECKPOINT" ]]; then
    LAST_CHECKPOINT="$CHECKPOINT"
    LAST_ADVANCED_AT=$SECONDS
  elif (( SECONDS - LAST_ADVANCED_AT > 60 )); then
    fail "Checkpoints have not advanced for more than 60 seconds (last #$LAST_CHECKPOINT)"
  fi

  KAFKA_BYTES="$(docker compose exec -T kafka sh -c 'du -sb /var/lib/kafka/data | cut -f1' | tr -d '\r')"
  POSTGRES_BYTES="$(docker compose exec -T postgres psql -U aether -d aether -Atc \
    "SELECT COALESCE(sum(pg_total_relation_size(relid)), 0)::bigint FROM pg_stat_user_tables")"
  [[ "$KAFKA_BYTES" =~ ^[0-9]+$ && "$POSTGRES_BYTES" =~ ^[0-9]+$ ]] \
    || fail 'Could not parse Kafka or Postgres disk usage'

  while IFS=, read -r container memory_usage cpu_usage; do
    [[ -n "$container" ]] || continue
    memory_bytes="$(bytes_from_size "${memory_usage%% / *}")"
    cpu_percent="${cpu_usage%%%}"
    [[ "$memory_bytes" =~ ^[0-9]+$ ]] || fail "Invalid memory reading for $container: $memory_usage"
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "$minute" "$NOW" "$container" "$memory_bytes" "$cpu_percent" \
      "$EVENTS" "$WINDOWS" "$CHECKPOINT" "$KAFKA_BYTES" "$POSTGRES_BYTES" >> "$CSV"
    printf '| %d | %s | %.1f | %s%% | %s | %s | %s | %.1f | %.1f |\n' \
      "$minute" "$container" "$(awk -v n="$memory_bytes" 'BEGIN { print n / 1048576 }')" \
      "$cpu_percent" "$EVENTS" "$WINDOWS" "$CHECKPOINT" \
      "$(awk -v n="$KAFKA_BYTES" 'BEGIN { print n / 1048576 }')" \
      "$(awk -v n="$POSTGRES_BYTES" 'BEGIN { print n / 1048576 }')" >> "$SUMMARY"
  done < <(docker stats --no-stream --format '{{.Name}},{{.MemUsage}},{{.CPUPerc}}' \
    | awk -F, '{ usage=$2; sub(/ \/.*/, "", usage); print $1 "," usage "," $3 }')

  if (( minute > 0 && minute % 10 == 0 )); then
    require_request POST /api/compare '{}'
    jq -e '.match == true and .mismatches == [] and .referenceResults > 0 and .matched == .referenceResults' \
      <<< "$RESPONSE" >/dev/null || fail "Reference comparison mismatched at minute $minute: $RESPONSE"
  fi
  printf 'LONGRUN minute=%d/%d checkpoint=%s events/s=%s windows=%s\n' \
    "$minute" "$DURATION_MINUTES" "$CHECKPOINT" "$EVENTS" "$WINDOWS"
  if (( minute < DURATION_MINUTES )); then
    TARGET=$((STARTED + (minute + 1) * 60))
    WAIT=$((TARGET - SECONDS))
    (( WAIT > 0 )) && sleep "$WAIT"
  fi
done

printf '\n### Memory trend (minute 10 to minute %d)\n\n' "$DURATION_MINUTES" >> "$SUMMARY"
printf '| Container | Min 10 (MiB) | Final (MiB) | Peak (MiB) | Growth | Trend |\n|---|---:|---:|---:|---:|---|\n' >> "$SUMMARY"
while IFS=, read -r container _; do
  [[ -n "$container" ]] || continue
  INITIAL="$(memory_at "$container" 10)" || fail "No minute-10 memory sample for $container"
  FINAL="$(memory_at "$container" "$DURATION_MINUTES")" || fail "No final memory sample for $container"
  PEAK="$(awk -F, -v wanted="$container" 'NR > 1 && $3 == wanted && $4 > max { max=$4 } END { if (max == "") exit 1; print max }' "$CSV")" \
    || fail "No memory samples for $container"
  GROWTH="$(awk -v start="$INITIAL" -v end="$FINAL" 'BEGIN { if (start == 0) print (end == 0 ? 0 : 100); else printf "%.1f", (end-start)*100/start }')"
  if awk -v growth="$GROWTH" 'BEGIN { exit !(growth > 30) }'; then
    fail "$container memory grew $GROWTH% between minute 10 and minute $DURATION_MINUTES (limit 30%)"
  fi
  BARS="$(awk -v growth="$GROWTH" 'BEGIN { count=int(growth/5); if(count<0) count=0; if(count>20) count=20; for(i=0;i<count;i++) printf "#"; if(count==0) printf "-" }')"
  printf '| %s | %.1f | %.1f | %.1f | %s%% | %s |\n' "$container" \
    "$(awk -v n="$INITIAL" 'BEGIN { print n / 1048576 }')" \
    "$(awk -v n="$FINAL" 'BEGIN { print n / 1048576 }')" \
    "$(awk -v n="$PEAK" 'BEGIN { print n / 1048576 }')" "$GROWTH" "$BARS" >> "$SUMMARY"
done < <(awk -F, 'NR > 1 && !seen[$3]++ { print $3 }' "$CSV" | while IFS= read -r name; do printf '%s,\n' "$name"; done)

printf '\nComparison passed at every 10-minute interval; all containers stayed within the 30%% memory-growth limit.\n' >> "$SUMMARY"
printf 'LONGRUN PASS duration=%d minutes csv=%s\n' "$DURATION_MINUTES" "$CSV"
