#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
if [[ $# -lt 1 || $# -gt 2 || ! "$1" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]]; then
  echo 'Usage: bash infra/scripts/expo-prepull.sh PUBLISHED_TAG_OR_SHA [BACKUP.tar]' >&2
  exit 2
fi
export EXPO_TAG="$1"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-aether-offline}"
ARCHIVE="${2:-expo-images-${EXPO_TAG}.tar}"
ENV_FILE="${EXPO_ENV_FILE:-$ROOT/.expo-offline.env}"
COMPOSE=(docker compose -p "$COMPOSE_PROJECT_NAME" -f docker-compose.yml -f docker-compose.offline.yml --profile monitoring)
command -v docker >/dev/null

# Pull explicitly while online, despite the runtime override's never policy.
IMAGE_LIST="$("${COMPOSE[@]}" config --images)"
mapfile -t IMAGES < <(printf '%s\n' "$IMAGE_LIST" | tr -d '\r' | sort -u)
for image in "${IMAGES[@]}"; do
  docker pull "$image"
done
docker image save --output "$ARCHIVE" "${IMAGES[@]}"
printf 'EXPO_TAG=%s\n' "$EXPO_TAG" > "$ENV_FILE"
printf 'Cached %s images; backup: %s; configuration: %s\n' "${#IMAGES[@]}" "$ARCHIVE" "$ENV_FILE"
printf 'Keep this checkout, the configuration file, and tar on the expo machine.\n'
