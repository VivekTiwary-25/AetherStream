#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! "$1" =~ ^[[:xdigit:]]{7,64}$ ]]; then
  printf 'Usage: %s <commit-sha>\n' "$0" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
export AETHER_TAG="$1"
compose=(docker compose -p aether -f docker-compose.yml -f infra/deploy/docker-compose.deploy.yml)

if ! pull_output="$("${compose[@]}" pull 2>&1)"; then
  printf '%s\n' "$pull_output" >&2
  if grep -Eqi 'denied|unauthorized|authentication required|requested access to the resource is denied' <<<"$pull_output"; then
    cat >&2 <<'MESSAGE'
GHCR denied the image pull. Make the repository's GHCR packages public:
GitHub repository > Packages > package settings > Change visibility > Public.
Alternatively, authenticate Docker on this runner with a token that can read the packages.
MESSAGE
  fi
  exit 1
fi
printf '%s\n' "$pull_output"

if up_output="$("${compose[@]}" up -d --remove-orphans --wait --wait-timeout 180 2>&1)"; then
  printf '%s\n' "$up_output"
else
  if ! grep -Eqi 'unknown flag: --wait|unknown flag: --wait-timeout|unknown shorthand flag' <<<"$up_output"; then
    printf '%s\n' "$up_output" >&2
    exit 1
  fi
  printf 'Compose --wait unsupported; starting services and polling health.\n'
  "${compose[@]}" up -d --remove-orphans
  all_ready=false
  deadline=$((SECONDS + 180))
  while (( SECONDS < deadline )); do
    ids="$("${compose[@]}" ps -q)"
    if [[ -n "$ids" ]]; then
      all_ready=true
      while IFS= read -r container_id; do
        [[ -n "$container_id" ]] || continue
        state="$(docker inspect --format '{{.State.Status}}' "$container_id")"
        health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id")"
        if [[ "$state" != running || ( "$health" != healthy && "$health" != none ) ]]; then
          all_ready=false
          break
        fi
      done <<<"$ids"
      if [[ "$all_ready" == true ]]; then
        break
      fi
    fi
    sleep 5
  done
  if [[ "$all_ready" != true ]]; then
    printf 'Services did not become healthy within 180 seconds.\n' >&2
    "${compose[@]}" ps >&2 || true
    exit 1
  fi
fi

printf '%s\n' "$AETHER_TAG" > infra/deploy/.last-deployed
printf 'Deployed AetherStream image SHA %s\n' "$AETHER_TAG"
