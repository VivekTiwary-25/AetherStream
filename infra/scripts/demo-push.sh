#!/usr/bin/env bash
set -euo pipefail

# Run explicitly from a clean, fully deployed main checkout; never force-push.
if [[ $# -ne 1 || "$1" != --push ]]; then
  printf 'Usage: bash infra/scripts/demo-push.sh --push\n'
  printf 'Commits only the Expo counter and pushes main; CD restarts the live stack.\n'
  [[ ${1:-} == --help ]] && exit 0
  exit 2
fi

fail() {
  printf 'FAIL after %ss: %s\n' "$SECONDS" "$*" >&2
  exit 1
}
phase() {
  printf '%s: %ss (total %ss)\n' "$1" "$((SECONDS - phase_start))" "$SECONDS"
  phase_start=$SECONDS
}
trap 'fail "Unexpected failure at line $LINENO; inspect the checkout/Actions before retrying"' ERR

for tool in git curl jq; do
  command -v "$tool" >/dev/null || fail "Required command missing: $tool"
done
# Native jq.exe otherwise leaves CR in captured strings under Git Bash.
jq() { command jq --binary "$@"; }
repo='VivekTiwary-25/AetherStream'
api="https://api.github.com/repos/$repo"
api_get() {
  local body
  if command -v gh >/dev/null && body="$(gh api --method GET "repos/$repo/$1" 2>/dev/null)"; then
    printf '%s\n' "$body"
  else
    curl --fail --silent --show-error --connect-timeout 10 --max-time 20 \
      -H 'Accept: application/vnd.github+json' "$api/$1"
  fi
}

phase_start=$SECONDS
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
[[ "$(git branch --show-current)" == main ]] || fail 'Run from main, not a task branch'
[[ -z "$(git status --porcelain --untracked-files=all)" ]] || fail 'Checkout/index must be clean, including untracked files'
case "$(git remote get-url --push origin)" in
  https://github.com/VivekTiwary-25/AetherStream.git|https://github.com/VivekTiwary-25/AetherStream|git@github.com:VivekTiwary-25/AetherStream.git) ;;
  *) fail 'origin push URL must be VivekTiwary-25/AetherStream on GitHub' ;;
esac
before="$(git rev-parse HEAD)"
remote="$(git ls-remote --exit-code origin refs/heads/main)"
[[ "${remote%%[[:space:]]*}" == "$before" ]] || fail 'HEAD must equal remote main; do not include unpublished commits'
version="$(curl --fail --silent --show-error --connect-timeout 5 --max-time 10 http://localhost:18080/api/version)"
[[ "$(jq -er '.commit' <<<"$version")" == "$before" ]] || fail 'Local API must already be running remote main before this demo'
runs="$(api_get 'actions/workflows/cd.yml/runs?branch=main&event=push&per_page=20')"
jq -e 'all(.workflow_runs[]; .status == "completed")' <<<"$runs" >/dev/null \
  || fail 'A main CD run is still active/queued; wait before starting a demo push'
counter_file='web/public/expo-build.txt'
git ls-files --error-unmatch "$counter_file" >/dev/null
counter="$(cat "$counter_file")"
counter="${counter%$'\r'}"
[[ "$counter" =~ ^[0-9]{1,9}$ ]] || fail 'Expo counter must contain an integer with at most nine digits'
next=$((10#$counter + 1))
phase 'Preflight'

printf '%s\n' "$next" > "$counter_file"
git add -- "$counter_file"
git commit --only -m "demo: expo build #$next" -- "$counter_file"
sha="$(git rev-parse HEAD)"
[[ "$(git rev-parse HEAD^)" == "$before" && "$(git diff-tree --no-commit-id --name-only -r HEAD)" == "$counter_file" ]] \
  || fail 'Commit is not exactly one counter change; refusing to push'
[[ "$(git branch --show-current)" == main && -z "$(git status --porcelain --untracked-files=all)" ]] \
  || fail 'Checkout changed while committing; refusing to push'
phase 'Counter + commit'

pushed_at=$SECONDS
git push origin HEAD:refs/heads/main
phase 'Push'
printf 'Expo build #%s, commit %s\nActions for this commit: https://github.com/%s/commit/%s/checks\n' "$next" "$sha" "$repo" "$sha"
deadline=$((SECONDS + 1800))
run_id=''
while (( SECONDS < deadline )); do
  if runs="$(api_get "actions/workflows/cd.yml/runs?branch=main&event=push&head_sha=$sha&per_page=20")"; then
    run_id="$(jq -r --arg sha "$sha" '[.workflow_runs[] | select(.head_sha == $sha and .head_branch == "main" and .event == "push")] | first | .id // empty' <<<"$runs")"
    [[ -z "$run_id" ]] || break
  fi
  sleep 10
done
[[ -n "$run_id" ]] || fail 'No CD run appeared for this SHA within 30 min'
printf 'CD run: https://github.com/%s/actions/runs/%s\n' "$repo" "$run_id"
phase 'Actions discovery'

while (( SECONDS < deadline )); do
  if ! run="$(api_get "actions/runs/$run_id")"; then
    sleep 10
    continue
  fi
  if jobs="$(api_get "actions/runs/$run_id/jobs?filter=latest&per_page=100")"; then
    deploy="$(jq -c '[.jobs[] | select(.name == "deploy")] | first // {}' <<<"$jobs")"
    if [[ "$(jq -r '.status // empty' <<<"$deploy")" == completed ]]; then
      conclusion="$(jq -r '.conclusion' <<<"$deploy")"
      [[ "$conclusion" == success ]] || fail "CD deploy finished: $conclusion (see run $run_id)"
      break
    fi
    # Read run state first so a just-completed deploy cannot race this check.
    [[ "$(jq -r '.status' <<<"$run")" != completed ]] \
      || fail "CD finished without a successful deploy: $(jq -r '.conclusion' <<<"$run")"
  fi
  sleep 10
done
[[ "${conclusion:-}" == success ]] || fail 'CD deploy did not finish within 30 min'
phase 'Publish + deploy job'

deadline=$((SECONDS + 120))
while (( SECONDS < deadline )); do
  if version="$(curl --fail --silent --show-error --connect-timeout 5 --max-time 10 http://localhost:18080/api/version)" \
      && [[ "$(jq -r '.commit // empty' <<<"$version")" == "$sha" ]]; then
    phase 'Live /api/version'
    printf 'DEPLOYED %s Expo build #%s; push -> deployed %ss. Refresh the page to show the new footer.\n' \
      "$sha" "$next" "$((SECONDS - pushed_at))"
    exit 0
  fi
  sleep 5
done
fail "Local /api/version did not equal $sha within 120s after deploy"
