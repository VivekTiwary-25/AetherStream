#!/usr/bin/env bash
# Presenter: bash infra/scripts/expo-check.sh [API_URL]
# Uses expo-offline-up.sh's env files, project and monitoring profile when the
# offline overlay exists. --ci checks only the locally built, default stack.
# demo-ready.sh may start the demo pipeline; this script never starts containers.
set -uo pipefail

# Bound the entire process tree, including Docker/PowerShell and demo-ready.sh.
if [[ "${1:-}" != --internal-check ]]; then
  if ! timeout --version 2>/dev/null | grep -q 'GNU coreutils'; then
    printf '\033[31m[FAIL] GNU timeout is required (included with Git Bash)\033[0m\n'
    exit 1
  fi
  timeout -k 2s 55s bash "${BASH_SOURCE[0]}" --internal-check "$@"
  result=$?
  if [[ "$result" == 124 || "$result" == 137 ]]; then
    printf '\033[31m[FAIL] Checklist exceeded 55s; demo is not ready\033[0m\n'
  fi
  exit "$result"
fi
shift

FAILED=0
pass() { printf '\033[32m[PASS] %s\033[0m\n' "$*"; }
fail() { printf '\033[31m[FAIL] %s\033[0m\n' "$*"; FAILED=1; }
warn() { printf '\033[33m[WARN] %s\033[0m\n' "$*"; }
run() { timeout -k 1s 5s "$@"; }
CI_MODE=false
if [[ "${1:-}" == --ci ]]; then CI_MODE=true; shift; fi
if (( $# > 1 )) || [[ "${1:-}" == -* ]]; then
  fail 'Usage: bash infra/scripts/expo-check.sh [--ci] [API_URL]'
  exit 1
fi
API="${1:-${API:-http://localhost:18080}}"
API="${API%/}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)" || exit 1
cd "$ROOT" || exit 1
for tool in docker curl jq; do
  if ! command -v "$tool" >/dev/null; then fail "Required command missing: $tool"; fi
done
(( FAILED == 0 )) || exit 1
# Match the other expo scripts: native jq.exe must not emit CRLF in Git Bash.
jq() { command jq --binary "$@"; }

COMPOSE=(docker compose -f docker-compose.yml)
if [[ "$CI_MODE" == false && -f docker-compose.offline.yml ]]; then
  export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-aether-offline}"
  COMPOSE=(docker compose -p "$COMPOSE_PROJECT_NAME")
  if [[ -f .env ]]; then COMPOSE+=(--env-file .env); fi
  ENV_FILE="${EXPO_ENV_FILE:-$ROOT/.expo-offline.env}"
  if [[ -f "$ENV_FILE" ]]; then COMPOSE+=(--env-file "$ENV_FILE"); fi
  COMPOSE+=(-f docker-compose.yml -f docker-compose.offline.yml --profile monitoring)
  printf 'Expo checklist: offline stack (EXPO_TAG from environment or %s)\n' "$ENV_FILE"
else
  printf 'Expo checklist: default stack (offline overlay N/A)\n'
fi

if run docker info >/dev/null 2>&1; then
  pass 'Docker engine is up'
else
  fail 'Docker engine is unavailable'
  exit 1
fi
if ! CONFIG="$(run "${COMPOSE[@]}" config --format json)"; then
  fail 'Cannot resolve Compose configuration; offline mode needs EXPO_TAG (run expo-prepull.sh online first)'
  exit 1
fi
# Compose versions may include disabled profiles in config output. CI does not
# start or cache optional monitoring services; offline mode enables monitoring.
if [[ "$CI_MODE" == true ]]; then
  CONFIG="$(jq '.services |= with_entries(select((.value.profiles // [] | length) == 0))' <<< "$CONFIG")"
fi
if ! jq -e '.services | type == "object" and length > 0' <<< "$CONFIG" >/dev/null; then
  fail 'Compose returned no services'
  exit 1
fi
if ! IMAGE_LIST="$(jq -er '.name as $project |
    [.services | to_entries[] | .value.image // ($project + "-" + .key)] | unique[]' <<< "$CONFIG")"; then
  fail 'Cannot resolve Compose image names'
  exit 1
fi
mapfile -t IMAGES <<< "$IMAGE_LIST"
for image in "${IMAGES[@]}"; do
  if run docker image inspect "$image" >/dev/null 2>&1; then
    pass "Cached image: $image"
  else
    fail "Missing local image: $image (restore the image archive or prepull online)"
  fi
done

CONTAINERS='[]'
if IDS="$(run "${COMPOSE[@]}" ps --all --quiet)"; then
  IDS="${IDS//$'\r'/}"
  if [[ -n "$IDS" ]]; then
    mapfile -t ID_LIST <<< "$IDS"
    if ! CONTAINERS="$(run docker inspect "${ID_LIST[@]}")"; then
      fail 'Cannot inspect our containers'
      CONTAINERS='[]'
    fi
  fi
else
  fail 'Cannot list our Compose containers'
fi
# Only successful one-shot dependency jobs may be exited. A stopped long-lived
# service is a failure even when its exit code is zero.
# Native Windows programs have a small command-line limit; inspect JSON belongs
# in a file, not in jq's --argjson argument.
CONTAINER_FILE="$(mktemp)" || { fail 'Cannot create container snapshot'; exit 1; }
trap 'rm -f "$CONTAINER_FILE"' EXIT
printf '%s\n' "$CONTAINERS" > "$CONTAINER_FILE"
if ! SERVICE_STATES="$(jq -r --slurpfile containers "$CONTAINER_FILE" '
  . as $config |
  [.services[].depends_on // {} | to_entries[] |
    select(.value.condition == "service_completed_successfully") | .key] as $jobs |
  .services | keys[] as $service |
  [$containers[0][] | select(.Config.Labels["com.docker.compose.service"] == $service)] as $matches |
  if ($matches | length) == 0 then "\($service)\tmissing"
  else $matches[] |
    if .State.Running and
        (if (.State.Health // null) != null then .State.Health.Status == "healthy"
         else $config.services[$service].healthcheck == null end) then
      "\($service)\tOK (\(.State.Health.Status // "running; no healthcheck"))"
    elif ($jobs | index($service)) != null and .State.Status == "exited" and .State.ExitCode == 0 then
      "\($service)\tOK (one-shot completed)"
    else "\($service)\t\(.State.Status)/\(.State.Health.Status // "no healthcheck") exit=\(.State.ExitCode)"
    end
  end
' <<< "$CONFIG")"; then
  fail 'Cannot evaluate container health'
  exit 1
fi
while IFS=$'\t' read -r service state; do
  if [[ "$state" == OK* ]]; then pass "Container $service: $state"; else fail "Container $service: $state"; fi
done <<< "$SERVICE_STATES"

# Actual running bindings, not merely configured ports, establish ownership.
OWNED="$(jq -r '.[] | select(.State.Running) | .NetworkSettings.Ports // {} |
  to_entries[] | .key as $key | .value[]? |
  "\($key | split("/")[1])\t\(.HostPort)"' <<< "$CONTAINERS")"
# Docker's Linux NAT may publish ports without a host listening socket. Include
# foreign Docker bindings so those ports cannot be mistaken for free ports.
FOREIGN=''
if RUNNING_IDS="$(run docker ps --quiet)"; then
  RUNNING_IDS="${RUNNING_IDS//$'\r'/}"
  if [[ -n "$RUNNING_IDS" ]]; then
    mapfile -t RUNNING_LIST <<< "$RUNNING_IDS"
    if ALL_CONTAINERS="$(run docker inspect "${RUNNING_LIST[@]}")"; then
      FOREIGN="$(jq -r --slurpfile ours "$CONTAINER_FILE" '
        ($ours[0] | map(.Id)) as $ids |
        .[] | select(.Id as $id | ($ids | index($id)) == null) |
        .NetworkSettings.Ports // {} | to_entries[] | .key as $key |
        .value[]? | "\($key | split("/")[1])\t\(.HostPort)"
      ' <<< "$ALL_CONTAINERS")"
    else
      fail 'Cannot inspect other containers for port conflicts'
    fi
  fi
else
  fail 'Cannot list running containers for port conflicts'
fi
PORTS="$(jq -r '(["tcp\t8088", "tcp\t18080"] +
  [.services[].ports[]? | select(.published != null) |
    "\(.protocol // "tcp")\t\(.published)"]) | unique[]' <<< "$CONFIG")"
OS="$(uname -s)"
LISTENERS=''
RAM=''
case "$OS" in
  MINGW*|MSYS*|CYGWIN*)
    if LISTENERS="$(timeout -k 1s 8s powershell.exe -NoProfile -NonInteractive -Command '
      $ErrorActionPreference = "Stop"
      Get-NetTCPConnection | Where-Object State -eq Listen | ForEach-Object { "tcp`t$($_.LocalPort)" }
      Get-NetUDPEndpoint | ForEach-Object { "udp`t$($_.LocalPort)" }
    ')"; then
      LISTENERS="${LISTENERS//$'\r'/}"
    else
      fail 'Cannot enumerate Windows listening ports'
    fi
    RAM="$(run powershell.exe -NoProfile -NonInteractive -Command \
      '(Get-CimInstance Win32_OperatingSystem -ErrorAction Stop).FreePhysicalMemory')" || RAM=''
    RAM="${RAM//[$'\r\n ']/}"
    ;;
  Linux*)
    if SOCKETS="$(run ss -H -lntu)"; then
      LISTENERS="$(awk '{n=split($5, endpoint, ":"); print $1 "\t" endpoint[n]}' <<< "$SOCKETS")"
    else
      fail 'Cannot enumerate listening ports (ss is required on Linux)'
    fi
    if [[ -r /proc/meminfo ]]; then RAM="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"; fi
    ;;
  *) fail "Cannot verify ports on $OS" ;;
esac
while IFS=$'\t' read -r protocol port; do
  if [[ ! "$port" =~ ^[0-9]+$ ]]; then
    fail "Unsupported published port: $protocol/$port"
  elif grep -Fxq "$protocol"$'\t'"$port" <<< "$FOREIGN"; then
    fail "Port $port/$protocol: published by another Docker container"
  elif grep -Fxq "$protocol"$'\t'"$port" <<< "$OWNED"; then
    pass "Port $port/$protocol: owned by our running containers"
  elif grep -Fxq "$protocol"$'\t'"$port" <<< "$LISTENERS"; then
    fail "Port $port/$protocol: occupied outside our containers"
  else
    pass "Port $port/$protocol: free"
  fi
done <<< "$PORTS"
if [[ "$RAM" =~ ^[0-9]+$ ]]; then
  if (( RAM < 3 * 1024 * 1024 )); then
    warn "Free RAM: $((RAM / 1024)) MiB (below 3 GiB)"
  else
    pass "Free RAM: $((RAM / 1024)) MiB"
  fi
else
  warn 'Free RAM: N/A'
fi

if HEALTH="$(run curl -fsS --connect-timeout 2 --max-time 4 "$API/api/health")" &&
    jq -e '.status == "UP"' <<< "$HEALTH" >/dev/null; then
  pass "$API/api/health: UP"
else
  fail "$API/api/health: not UP"
fi
HEAD=''
if command -v git >/dev/null; then HEAD="$(run git rev-parse HEAD 2>/dev/null)" || HEAD=''; fi
if VERSION="$(run curl -fsS --connect-timeout 2 --max-time 4 "$API/api/version")" &&
    COMMIT="$(jq -er '.commit | select(type == "string" and length > 0)' <<< "$VERSION")"; then
  if [[ -n "$HEAD" && "$COMMIT" != "$HEAD" ]]; then
    fail "/api/version commit=$COMMIT differs from checkout=$HEAD"
  else
    pass "/api/version commit=$COMMIT (checkout=${HEAD:-N/A})"
  fi
else
  fail "$API/api/version: unavailable or missing commit"
fi

TUNNEL_LOG='C:/Users/Arena/aether-tunnel.log'
if [[ -f "$TUNNEL_LOG" ]]; then
  URL="$(grep -Eo 'https://[a-zA-Z0-9-]+\.trycloudflare\.com' "$TUNNEL_LOG" | tail -n 1)"
  if [[ -n "$URL" ]]; then pass "Quick tunnel: $URL"; else warn 'Quick tunnel: log exists but no URL found'; fi
else
  pass 'Quick tunnel: offline mode (N/A)'
fi

# Reserve two seconds for reporting before the outer 55-second deadline.
REMAINING=$((53 - SECONDS))
if (( REMAINING > 0 )); then
  if EXPECT_SHA="${HEAD:-${EXPECT_SHA:-}}" timeout -k 1s "${REMAINING}s" \
      bash infra/scripts/demo-ready.sh "$API"; then
    pass 'demo-ready.sh: DEMO READY'
  else
    fail 'demo-ready.sh: failed or exceeded the remaining checklist budget'
  fi
else
  fail 'demo-ready.sh: no time left in the checklist budget'
fi
if (( FAILED == 0 )); then pass 'EXPO READY'; else fail 'EXPO NOT READY'; fi
exit "$FAILED"
