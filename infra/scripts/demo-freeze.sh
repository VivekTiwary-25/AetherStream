#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! "$1" =~ ^(on|off|status)$ ]]; then
  printf 'Usage: bash %s on|off|status\n' "$0" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
file_freeze=0
if [[ -e infra/deploy/FREEZE ]]; then
  file_freeze=1
fi
printf 'Local infra/deploy/FREEZE present: %s (CD checks its own workflow commit).\n' "$file_freeze"

if ! command -v gh >/dev/null 2>&1; then
  cat >&2 <<'MESSAGE'
GitHub CLI (gh) is missing; no repository variable was read or changed.
In the GitHub repository, open Settings > Secrets and variables > Actions > Variables > Repository variables.
Create/edit DEMO_FREEZE: set 1 for on, 0 for off; inspect its value for status.
CD also freezes if infra/deploy/FREEZE exists in the workflow commit; remove it to fully unfreeze.
MESSAGE
  exit 127
fi

case "$1" in
  on)
    gh variable set DEMO_FREEZE --body 1
    printf 'DEMO_FREEZE=1: future CD deployments frozen; image publishing continues.\n'
    printf 'Wait for any deployment already running to finish before presenting.\n'
    ;;
  off)
    gh variable set DEMO_FREEZE --body 0
    printf 'DEMO_FREEZE=0: future CD deployments resume only if infra/deploy/FREEZE is absent from the workflow commit.\n'
    ;;
  status)
    value="$(gh variable list --json name,value --jq '.[] | select(.name == "DEMO_FREEZE") | .value')"
    value="${value//$'\r'/}"
    printf 'Repository DEMO_FREEZE=%s\n' "${value:-<unset>}"
    if [[ "$value" == 1 || "$file_freeze" == 1 ]]; then
      printf 'Demo freeze: ON (repository variable or local FREEZE file).\n'
    else
      printf 'Demo freeze: OFF for this checkout (CD checks its own workflow commit).\n'
    fi
    ;;
esac
