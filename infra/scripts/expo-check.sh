#!/usr/bin/env bash
# Presenter: bash infra/scripts/expo-check.sh [API_URL]
# CI: --ci uses the built Compose project, with up to 180s for container health.
# Local mode uses expo-offline-up.sh's files/project and a 58s total budget.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
ARGS=("$@")
CI_MODE=false
if [[ "${1:-}" == --ci ]]; then CI_MODE=true; shift; fi
if (( $# > 1 )) || [[ "${1:-}" == -* ]]; then
  echo 'Usage: bash infra/scripts/expo-check.sh [--ci] [API_URL]' >&2
  exit 2
fi
API="${1:-${API:-http://localhost:18080}}"
API="${API%/}"
GREEN=$'\033[32m' RED=$'\033[31m' YELLOW=$'\033[33m' RESET=$'\033[0m'
FAILURES=0
pass() { printf '%s[PASS]%s %s\n' "$GREEN" "$RESET" "$*"; }
warn() { printf '%s[WARN]%s %s\n' "$YELLOW" "$RESET" "$*"; }
fail() { printf '%s[FAIL]%s %s\n' "$RED" "$RESET" "$*"; FAILURES=$((FAILURES + 1)); }

# Bound Docker/PowerShell as well as demo-ready's own longer waits. Git Bash ships timeout.
if [[ "${EXPO_CHECK_BOUNDED:-}" != 1 ]]; then
  command -v timeout >/dev/null || { fail 'Required command missing: timeout'; exit 1; }
  BUDGET=58
  if "$CI_MODE"; then BUDGET=600; fi
  STATUS=0
  EXPO_CHECK_BOUNDED=1 timeout --kill-after=1s "${BUDGET}s" bash "$ROOT/infra/scripts/expo-check.sh" "${ARGS[@]}" || STATUS=$?
  if (( STATUS == 124 || STATUS == 137 )); then fail "Checklist exceeded ${BUDGET}s budget"; fi
  exit "$STATUS"
fi

for tool in docker curl jq; do
  command -v "$tool" >/dev/null || fail "Required command missing: $tool"
done
(( FAILURES == 0 )) || exit 1
# Native jq.exe must not put CRLF into captured names and IDs under Git Bash.
jq() { command jq --binary "$@"; }

COMPOSE=(docker compose)
OFFLINE_MODE=false
if ! "$CI_MODE" && [[ -f docker-compose.offline.yml ]]; then
  OFFLINE_MODE=true
  export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-aether-offline}"
  ENV_FILE="${EXPO_ENV_FILE:-$ROOT/.expo-offline.env}"
  if [[ -f .env ]]; then COMPOSE+=(--env-file "$ROOT/.env"); fi
  if [[ -f "$ENV_FILE" ]]; then COMPOSE+=(--env-file "$ENV_FILE"); fi
  COMPOSE+=(-f docker-compose.yml -f docker-compose.offline.yml --profile monitoring)
else
  COMPOSE+=(-f docker-compose.yml)
fi

ENGINE=false CONFIG='' RUNTIME='[]' HEALTHY=false
if docker info >/dev/null 2>&1; then
  pass 'Docker engine is up'
  ENGINE=true
else
  fail 'Docker engine is unavailable'
fi
if "$ENGINE"; then
  if CONFIG="$("${COMPOSE[@]}" config --format json)"; then
    # The normal stack uses the default profile; offline startup includes monitoring.
    CONFIG="$(jq --argjson offline "$OFFLINE_MODE" '.services |= with_entries(select(
      $offline or ((.value.profiles // []) | length == 0)))' <<< "$CONFIG")"
  else
    fail 'Cannot resolve Compose configuration (offline mode needs EXPO_TAG or .expo-offline.env)'
    CONFIG=''
  fi
fi

if [[ -n "$CONFIG" ]]; then
  HEALTH_WAIT=55
  if "$CI_MODE"; then HEALTH_WAIT=180; fi
  HEALTH_DEADLINE=$((SECONDS + HEALTH_WAIT))
  ONESHOTS="$(jq '[.services[].depends_on // {} | to_entries[] |
    select(.value.condition == "service_completed_successfully") | .key] | unique' <<< "$CONFIG")"
  while :; do
    PROBLEMS='Cannot inspect Compose containers'
    if IDS="$("${COMPOSE[@]}" ps --all --quiet)" && [[ -n "$IDS" ]]; then
      mapfile -t CONTAINERS < <(printf '%s\n' "$IDS" | tr -d '\r')
      if INSPECT="$(docker inspect "${CONTAINERS[@]}")"; then
        RUNTIME="$INSPECT"
        PROBLEMS="$(jq -r --argjson config "$CONFIG" --argjson oneshots "$ONESHOTS" '
          . as $runtime |
          ($config.services | keys[] | . as $service |
            select(all($runtime[]; .Config.Labels["com.docker.compose.service"] != $service)) |
            "\($service)=missing"),
          (.[] | .Config.Labels["com.docker.compose.service"] as $service |
            select(if ($oneshots | index($service)) != null then
              (.State.Status == "exited" and .State.ExitCode == 0) | not
            else
              (.State.Status == "running" and
                ((.State.Health.Status // "none") == "healthy" or
                 ((.State.Health.Status // "none") == "none" and
                  ($config.services[$service].healthcheck.test // [] | length) == 0))) | not
            end) |
            "\(.Name | ltrimstr("/"))=\(.State.Status)/\(.State.Health.Status // (.State.ExitCode | tostring))")
        ' <<< "$RUNTIME")"
        if [[ -z "$PROBLEMS" ]]; then HEALTHY=true; break; fi
      fi
    fi
    if (( SECONDS >= HEALTH_DEADLINE )); then break; fi
    sleep 2
  done
  if "$HEALTHY"; then
    pass 'All Compose containers healthy (one-shot initialization completed)'
  else
    fail "Containers not healthy within ${HEALTH_WAIT}s: $PROBLEMS"
  fi

  if "$CI_MODE"; then
    # Build-only services have generated local names, not offline GHCR tags.
    IMAGE_LIST="$(jq -r '.[].Image' <<< "$RUNTIME" | sort -u)"
  else
    IMAGE_LIST="$("${COMPOSE[@]}" config --images | tr -d '\r' | sort -u)"
  fi
  if [[ -z "$IMAGE_LIST" ]]; then
    fail 'No local demo images found'
  else
    while IFS= read -r image; do
      if docker image inspect "$image" >/dev/null 2>&1; then
        pass "Local image present: $image"
      else
        fail "Local image missing: $image"
      fi
    done <<< "$IMAGE_LIST"
  fi
fi

# Host listeners are compared with this project's running published bindings.
LISTENERS='' RAM_KIB='' LISTENERS_KNOWN=false
if [[ "${OS:-}" == Windows_NT ]]; then
  if HOST="$(powershell.exe -NoProfile -NonInteractive -Command '
    $ErrorActionPreference = "Stop"
    $ram = $null
    try { $ram = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory } catch {}
    $ports = @(Get-NetTCPConnection -State Listen | Select-Object -ExpandProperty LocalPort -Unique)
    @{freeKiB=$ram; ports=$ports} | ConvertTo-Json -Compress
  ' | tr -d '\r')"; then
    LISTENERS="$(jq -r '.ports[]' <<< "$HOST")"
    RAM_KIB="$(jq -r '.freeKiB // empty' <<< "$HOST")"
    LISTENERS_KNOWN=true
  else
    fail 'Cannot inspect Windows listening ports'
  fi
else
  if command -v ss >/dev/null; then
    if SOCKETS="$(ss -H -ltn)"; then
      LISTENERS="$(awk '{n=split($4,a,":"); print a[n]}' <<< "$SOCKETS")"
      LISTENERS_KNOWN=true
    else
      fail 'Cannot inspect listening ports with ss'
    fi
  elif [[ -r /proc/net/tcp && -r /proc/net/tcp6 ]]; then
    while read -r hex; do LISTENERS+="$((16#$hex))"$'\n'; done < <(
      awk '$4 == "0A" {split($2,a,":"); print a[2]}' /proc/net/tcp /proc/net/tcp6)
    LISTENERS_KNOWN=true
  else
    fail 'Cannot inspect listening ports (need ss or /proc/net/tcp)'
  fi
  if [[ -r /proc/meminfo ]]; then
    RAM_KIB="$(awk '$1 == "MemAvailable:" {print $2}' /proc/meminfo)"
  fi
fi
if [[ -n "$CONFIG" ]]; then
  PORTS="$(jq -r '[8088,18080] + [.services[].ports[]? | .published |
    select(. != null) | tostring | split("-") | map(tonumber) |
    if length == 2 then range(.[0]; .[1]+1) else .[0] end] | unique[]' <<< "$CONFIG")"
  OWNED="$(jq -r '.[] | select(.State.Running) | .NetworkSettings.Ports // {} |
    .[][]? | .HostPort' <<< "$RUNTIME")"
  while read -r port; do
    if grep -qx "$port" <<< "$OWNED"; then
      pass "Port $port owned by our containers"
    elif grep -qx "$port" <<< "$LISTENERS"; then
      fail "Port $port occupied outside our containers"
    elif "$LISTENERS_KNOWN"; then
      pass "Port $port free"
    else
      fail "Port $port availability unknown"
    fi
  done <<< "$PORTS"
else
  fail 'Compose port ownership cannot be checked'
fi
if [[ "$RAM_KIB" =~ ^[0-9]+$ ]]; then
  RAM_GIB="$(awk -v kib="$RAM_KIB" 'BEGIN {printf "%.1f", kib/1048576}')"
  if (( RAM_KIB < 3 * 1024 * 1024 )); then
    warn "Free RAM: ${RAM_GIB} GiB (below 3 GiB)"
  else
    pass "Free RAM: ${RAM_GIB} GiB"
  fi
else
  warn 'Free RAM: N/A'
fi

API_HEALTHY=false
if BODY="$(curl -fsS --connect-timeout 2 --max-time 5 "$API/api/health")" &&
    jq -e '.status == "UP"' <<< "$BODY" >/dev/null; then
  pass '/api/health is UP'
  API_HEALTHY=true
else
  fail '/api/health is unavailable or not UP'
fi
if BODY="$(curl -fsS --connect-timeout 2 --max-time 5 "$API/api/version")" &&
    COMMIT="$(jq -er '.commit | select(type == "string" and length > 0)' <<< "$BODY")"; then
  EXPECTED="${EXPECT_SHA:-}"
  if [[ -z "$EXPECTED" && "$COMMIT" =~ ^[[:xdigit:]]{40}$ ]]; then
    EXPECTED="$(git rev-parse HEAD 2>/dev/null || true)"
  fi
  if [[ -n "$EXPECTED" ]]; then
    if [[ "$COMMIT" == "$EXPECTED" ]]; then pass "/api/version commit: $COMMIT";
    else fail "/api/version commit: $COMMIT does not match expected $EXPECTED"; fi
  else
    warn "/api/version commit: $COMMIT (no EXPECT_SHA or comparable checkout SHA)"
  fi
else
  fail '/api/version is unavailable or has no commit'
fi

TUNNEL_LOG='C:/Users/Arena/aether-tunnel.log'
if [[ -f "$TUNNEL_LOG" ]]; then
  TUNNEL="$(grep -Eo 'https://[a-zA-Z0-9-]+\.trycloudflare\.com' "$TUNNEL_LOG" | tail -n 1 || true)"
  if [[ -n "$TUNNEL" ]]; then pass "Quick tunnel: $TUNNEL";
  else warn "No quick-tunnel URL in $TUNNEL_LOG"; fi
else
  pass 'Tunnel: offline mode'
fi

if "$HEALTHY" && "$API_HEALTHY"; then
  if bash "$ROOT/infra/scripts/demo-ready.sh" "$API"; then
    pass 'demo-ready.sh passed'
  else
    fail 'demo-ready.sh failed'
  fi
else
  fail 'demo-ready.sh skipped: stack or API not healthy'
fi
if (( FAILURES > 0 )); then
  fail "Pre-demo check failed ($FAILURES critical checks)"
  exit 1
fi
pass 'EXPO READY'
