#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! "$1" =~ ^[[:xdigit:]]{7,64}$ ]]; then
  printf 'Usage: %s <commit-sha>\n' "$0" >&2
  exit 2
fi
expected_sha="$1"
url_base="http://localhost:8088/api"
deadline=$((SECONDS + 120))

while (( SECONDS < deadline )); do
  health="$(curl --fail --silent --show-error --max-time 5 "$url_base/health" 2>/dev/null || true)"
  if grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"$health"; then
    version="$(curl --fail --silent --show-error --max-time 5 "$url_base/version" 2>/dev/null || true)"
    if grep -Eq '"commit"[[:space:]]*:[[:space:]]*"'"$expected_sha"'"' <<<"$version"; then
      printf 'PASS: health is UP and deployed commit is %s\n' "$expected_sha"
      exit 0
    fi
  fi
  sleep 5
done

printf 'FAIL: service did not report health UP and commit %s within 120 seconds.\n' "$expected_sha" >&2
printf 'Last health response: %s\n' "${health:-<no response>}" >&2
printf 'Last version response: %s\n' "${version:-<no response>}" >&2
exit 1
