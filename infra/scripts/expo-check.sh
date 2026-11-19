#!/usr/bin/env bash
# Presenter: bash infra/scripts/expo-check.sh (offline images, <60 seconds).
# CI: bash infra/scripts/expo-check.sh --ci (built images, 180 seconds).
# Requires Docker Compose v2, curl, jq and GNU timeout (included in Git Bash).
# EXPECT_SHA enforces a version; otherwise only full SHAs are compared to HEAD.
set -uo pipefail

CI_MODE=false
LIMIT=55
for arg in "$@"; do
  case "$arg" in
    --ci) CI_MODE=true; LIMIT=180 ;;
    --help|-h)
      printf 'Usage: bash infra/scripts/expo-check.sh [--ci]\nAPI defaults to http://localhost:18080; EXPECT_SHA optionally pins the version.\n'
      exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$arg" >&2; exit 2 ;;
  esac
done

# Bound everything, including a stalled Docker daemon and demo-ready.sh's waits.
if [[ "${EXPO_CHECK_TIMED:-}" != 1 ]]; then
  command -v timeout >/dev/null || { printf '\033[31m[FAIL] GNU timeout is required\033[0m\n'; exit 1; }
  EXPO_CHECK_TIMED=1 timeout --kill-after=1 "$LIMIT" bash "${BASH_SOURCE[0]}" "$@"
  result=$?
  if [[ "$result" == 124 || "$result" == 137 ]]; then
    printf '\033[31m[FAIL] Checklist exceeded its %ss deadline\033[0m\n' "$LIMIT"
    exit 1
  fi
  exit "$result"
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1
API="${API:-http://localhost:18080}"
API="${API%/}"
FAILURES=0
pass() { printf '\033[32m[PASS] %s\033[0m\n' "$*"; }
warn() { printf '\033[33m[WARN] %s\033[0m\n' "$*"; }
fail() { printf '\033[31m[FAIL] %s\033[0m\n' "$*"; FAILURES=$((FAILURES + 1)); }
# jq.exe otherwise emits CRLF in Git Bash command substitutions.
jq() { command jq --binary "$@"; }
for tool in docker curl jq; do
  command -v "$tool" >/dev/null || fail "Required command missing: $tool"
done
(( FAILURES == 0 )) || exit 1
if ! timeout 8 docker info >/dev/null 2>&1; then
  fail 'Docker engine is unavailable'
  exit 1
fi
pass 'Docker engine is up'

COMPOSE=(docker compose)
if [[ "$CI_MODE" == false && -f docker-compose.offline.yml ]]; then
  COMPOSE+=(-p "${COMPOSE_PROJECT_NAME:-aether-offline}")
  [[ ! -f .env ]] || COMPOSE+=(--env-file .env)
  ENV_FILE="${EXPO_ENV_FILE:-$ROOT/.expo-offline.env}"
  [[ ! -f "$ENV_FILE" ]] || COMPOSE+=(--env-file "$ENV_FILE")
  COMPOSE+=(-f docker-compose.yml -f docker-compose.offline.yml --profile monitoring)
else
  COMPOSE+=(-f docker-compose.yml)
fi
if ! CONFIG="$(timeout 10 "${COMPOSE[@]}" config --format json)"; then
  fail 'Cannot resolve Compose configuration (offline mode needs EXPO_TAG or .expo-offline.env; run expo-prepull.sh first)'
  exit 1
fi
# Compose config includes disabled profile services; the default CI stack does not.
if [[ "$CI_MODE" == true ]]; then
  CONFIG="$(jq --arg profiles "${COMPOSE_PROFILES:-}" '
    ($profiles | split(",")) as $active |
    .services |= with_entries(select(
      (.value.profiles // []) as $p |
      ($p | length) == 0 or ($active | index("*")) != null or
      any($p[]; . as $profile | $active | index($profile) != null)
    ))' <<< "$CONFIG")" || exit 1
fi
PROJECT="$(jq -er '.name' <<< "$CONFIG")" || { fail 'Compose project name is missing'; exit 1; }
SERVICES="$(jq -c '.services' <<< "$CONFIG")"

# RAM and tunnel information are advisory, never reasons to fail CI.
RAM_KB=''
case "${OSTYPE:-}" in
  msys*|cygwin*|win32*)
    RAM_KB="$(timeout 8 powershell.exe -NoProfile -NonInteractive -Command \
      '(Get-CimInstance Win32_OperatingSystem -ErrorAction Stop).FreePhysicalMemory' 2>/dev/null | tr -d '\r')" ;;
  linux*) RAM_KB="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null)" ;;
esac
if [[ "$RAM_KB" =~ ^[0-9]+$ ]]; then
  RAM_MB=$((RAM_KB / 1024))
  if (( RAM_KB < 3 * 1024 * 1024 )); then warn "Free RAM: ${RAM_MB} MiB (<3 GiB)";
  else pass "Free RAM: ${RAM_MB} MiB"; fi
else
  warn 'Free RAM: N/A on this host'
fi
TUNNEL_LOG='C:/Users/Arena/aether-tunnel.log'
if [[ -f "$TUNNEL_LOG" ]]; then
  TUNNEL="$(grep -Eo 'https://[a-zA-Z0-9-]+\.trycloudflare\.com' "$TUNNEL_LOG" | tail -n 1)"
  if [[ -n "$TUNNEL" ]]; then pass "Quick tunnel (last logged URL): $TUNNEL";
  else warn 'Quick tunnel: no URL in log (offline mode)'; fi
else
  pass 'Quick tunnel: offline mode'
fi

# Inspect all containers in the selected project, including successful init jobs.
snapshot() {
  local ids
  ids="$(timeout 8 "${COMPOSE[@]}" ps --all --quiet)" || return 1
  ids="${ids//$'\r'/}"
  [[ -n "$ids" ]] || return 1
  local -a containers
  mapfile -t containers <<< "$ids"
  STATE="$(timeout 8 docker inspect "${containers[@]}")" || return 1
  PENDING="$(jq -r --argjson services "$SERVICES" --arg project "$PROJECT" '
    . as $containers | $services | to_entries[] |
    .key as $service | .value as $config |
    [$containers[] | select(.Config.Labels["com.docker.compose.project"] == $project and
      .Config.Labels["com.docker.compose.service"] == $service and
      .Config.Labels["com.docker.compose.oneoff"] != "True")] as $matches |
    select(($matches | length) == 0 or any($matches[];
      if $config.restart == "no" and $config.healthcheck == null then
        .State.Status != "exited" or .State.ExitCode != 0
      else .State.Running != true or .State.Health.Status != "healthy" end)) |
    $service' <<< "$STATE")" || return 1
  [[ -z "$PENDING" ]]
}
STATE='[]'
PENDING='No containers found'
if ! snapshot; then
  warn "Waiting for healthy containers / completed init jobs: ${PENDING//$'\n'/, }"
  until snapshot; do sleep 2; done
fi
pass 'All Compose containers healthy; one-shot init jobs completed successfully'

if [[ "$CI_MODE" == true ]]; then
  # Image IDs are the actual built artifacts, not the laptop's GHCR offline tags.
  IMAGES="$(jq -r '.[].Image' <<< "$STATE" | sort -u)"
else
  IMAGES="$(jq -r '.services[].image // empty' <<< "$CONFIG" | sort -u)"
fi
while IFS= read -r image; do
  [[ -n "$image" ]] || continue
  if timeout 5 docker image inspect "$image" >/dev/null 2>&1; then pass "Local image: $image";
  else fail "Local image missing: $image"; fi
done <<< "$IMAGES"

# Take one host socket snapshot; Docker's published ports also count as occupied
# (Docker Desktop and Linux's NAT need not expose a host listening socket).
SOCKETS=''
SOCKETS_OK=true
case "${OSTYPE:-}" in
  msys*|cygwin*|win32*)
    # PowerShell, not Bash, expands these variables.
    # shellcheck disable=SC2016
    SOCKETS="$(timeout 8 powershell.exe -NoProfile -NonInteractive -Command \
      '$ErrorActionPreference = "Stop"; Get-NetTCPConnection | Where-Object State -eq Listen | ForEach-Object { "{0:X4}/tcp" -f $_.LocalPort }; Get-NetUDPEndpoint | ForEach-Object { "{0:X4}/udp" -f $_.LocalPort }' | tr -d '\r')" || SOCKETS_OK=false ;;
  linux*)
    for proto in tcp udp; do
      for table in "/proc/net/$proto" "/proc/net/${proto}6"; do
        [[ -r "$table" ]] || { SOCKETS_OK=false; continue; }
        SOCKETS+=$'\n'"$(awk -v proto="$proto" 'FNR > 1 && (proto == "udp" || $4 == "0A") {split($2, a, ":"); print toupper(a[2]) "/" proto}' "$table")"
      done
    done ;;
  *) SOCKETS_OK=false ;;
esac
PORTS="$(jq -r '["8088/tcp", "18080/tcp"] + [
  .services[].ports[]? | select(.published != null) |
  (.protocol // "tcp") as $proto | (.published | tostring | split("-") | map(tonumber)) as $range |
  range($range[0]; ($range[-1] + 1)) | "\(.)/\($proto)"
] | unique[]' <<< "$CONFIG")" || exit 1
ALL_IDS="$(timeout 8 docker ps --quiet)" || { fail 'Cannot inspect port owners'; exit 1; }
ALL_IDS="${ALL_IDS//$'\r'/}"
ALL_STATE='[]'
if [[ -n "$ALL_IDS" ]]; then
  mapfile -t CONTAINERS <<< "$ALL_IDS"
  ALL_STATE="$(timeout 8 docker inspect "${CONTAINERS[@]}")" || { fail 'Cannot inspect port owners'; exit 1; }
fi
while IFS= read -r port; do
  OWNERS="$(jq -r --arg port "$port" --arg project "$PROJECT" --argjson services "$SERVICES" '
    .[] | . as $c | (.NetworkSettings.Ports // {} | to_entries[]) |
    (.key | split("/")[1]) as $proto | .value[]? |
    select((.HostPort + "/" + $proto) == $port) |
    if $c.Config.Labels["com.docker.compose.project"] == $project and
       ($services | has($c.Config.Labels["com.docker.compose.service"])) then "ours" else $c.Name end
  ' <<< "$ALL_STATE")" || exit 1
  FOREIGN="$(grep -v '^ours$' <<< "$OWNERS")"
  printf -v SOCKET_KEY '%04X/%s' "${port%/*}" "${port#*/}"
  if [[ -n "$FOREIGN" ]]; then fail "Port $port owned by another container: ${FOREIGN//$'\n'/, }";
  elif [[ -n "$OWNERS" ]]; then pass "Port $port owned by our containers";
  elif [[ "$SOCKETS_OK" != true ]]; then fail "Port $port: cannot inspect host listeners";
  elif grep -Fqx "$SOCKET_KEY" <<< "$SOCKETS"; then fail "Port $port occupied by a non-project listener";
  else pass "Port $port is free"; fi
done <<< "$PORTS"

if HEALTH="$(curl -fsS --connect-timeout 2 --max-time 5 "$API/api/health")" &&
    jq -e '.status == "UP"' <<< "$HEALTH" >/dev/null; then pass '/api/health is UP';
else fail '/api/health is unavailable or not UP'; fi
if VERSION="$(curl -fsS --connect-timeout 2 --max-time 5 "$API/api/version")" &&
    COMMIT="$(jq -er '.commit | select(type == "string" and length > 0)' <<< "$VERSION")"; then
  pass "/api/version commit: $COMMIT"
  EXPECTED="${EXPECT_SHA:-}"
  if [[ -z "$EXPECTED" && "$COMMIT" =~ ^[[:xdigit:]]{40}$ ]]; then
    EXPECTED="$(timeout 3 git rev-parse HEAD 2>/dev/null)" || EXPECTED=''
    EXPECTED="${EXPECTED//$'\r'/}"
  fi
  if [[ -n "$EXPECTED" ]]; then
    if [[ "$COMMIT" == "$EXPECTED" ]]; then pass "Version matches expected commit: $EXPECTED";
    else fail "Version commit $COMMIT does not match expected commit $EXPECTED"; fi
  else
    warn 'Version not compared: set EXPECT_SHA, or use a full SHA in a Git checkout'
  fi
else
  fail '/api/version is unavailable or has no commit'
fi
printf 'Running demo-ready.sh (within the checklist deadline)...\n'
if bash infra/scripts/demo-ready.sh "$API"; then pass 'demo-ready.sh';
else fail 'demo-ready.sh'; fi
if (( FAILURES > 0 )); then
  fail "Expo NOT READY ($FAILURES critical checks failed)"
  exit 1
fi
pass 'Expo READY'
