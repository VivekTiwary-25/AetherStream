#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-aether-offline}"
ENV_FILE="${EXPO_ENV_FILE:-$ROOT/.expo-offline.env}"
COMPOSE=(docker compose -p "$COMPOSE_PROJECT_NAME")
if [[ -f "$ROOT/.env" ]]; then
  COMPOSE+=(--env-file "$ROOT/.env")
fi
if [[ -f "$ENV_FILE" ]]; then
  COMPOSE+=(--env-file "$ENV_FILE")
fi
COMPOSE+=(-f docker-compose.yml -f docker-compose.offline.yml --profile monitoring)
for tool in docker curl jq; do
  command -v "$tool" >/dev/null || { echo "Required command missing: $tool" >&2; exit 1; }
done

# Check every image before starting anything. Never pull, build, or remove volumes.
CONFIG="$("${COMPOSE[@]}" config --format json)"
mapfile -t IMAGES < <(jq --binary -r '.services[].image' <<< "$CONFIG" | sort -u)
for image in "${IMAGES[@]}"; do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    printf 'Missing cached image: %s\nRestore with docker image load --input BACKUP.tar, or run expo-prepull.sh online.\n' "$image" >&2
    exit 1
  fi
done
"${COMPOSE[@]}" up -d --no-build --pull never --wait --wait-timeout 300
SHA="$(jq --binary -r '.services["control-api"].environment.GIT_SHA' <<< "$CONFIG")"
EXPECT_SHA="$SHA" bash infra/scripts/demo-ready.sh http://localhost:18080
printf '\nWeb: http://localhost:8088\nAPI: http://localhost:18080/api/ready\nGrafana: http://localhost:13000\nPrometheus: http://localhost:19090\nMinIO: http://localhost:19001\n'
