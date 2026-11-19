#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/.check-logs"
WITH_DOCKER=0
DRY_RUN=0
while (($#)); do
  case "$1" in
    --with-docker) WITH_DOCKER=1 ;;
    --dry-run) DRY_RUN=1 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

node_bin="${USERPROFILE:-}/tools/node"
if [[ -d "$node_bin" ]]; then
  case ":$PATH:" in *":$node_bin:"*) ;; *) export PATH="$node_bin:$PATH" ;; esac
fi

rows=()
failed=0
run_check() {
  local name="$1" cwd="$2" seconds="$3" command="$4"
  local logfile="$LOG_DIR/${name//[^a-zA-Z0-9_-]/_}.log" status elapsed start
  if ((DRY_RUN)); then
    printf '%s: (timeout %ss) %s [cwd: %s]\n' "$name" "$seconds" "$command" "$cwd"
    return
  fi
  start=$(date +%s)
  mkdir -p "$LOG_DIR"
  if command -v timeout >/dev/null 2>&1; then
    (cd "$cwd" && timeout --signal=TERM "$seconds" bash -lc "$command") >"$logfile" 2>&1
    status=$?
  else
    echo "The 'timeout' command is required to enforce per-check timeouts." >"$logfile"
    status=127
  fi
  elapsed=$(( $(date +%s) - start ))
  if ((status == 124 || status == 137)); then status=124; fi
  if ((status != 0)); then failed=1; fi
  rows+=("$name|$([[ $status -eq 0 ]] && echo PASS || { [[ $status -eq 124 ]] && echo TIMEOUT || echo FAIL; })|$elapsed|${logfile#"$ROOT/"}")
  printf '%-24s %s (exit %s)\n' "$name" "$([[ $status -eq 0 ]] && echo PASS || { [[ $status -eq 124 ]] && echo TIMEOUT || echo FAIL; })" "$status"
}

skip_check() { rows+=("$1|SKIP|0|-"); }
run_check web-npm-ci "$ROOT/web" 300 'if [ ! -d node_modules ]; then npm ci; else echo "node_modules exists; npm ci skipped"; fi'
run_check web-lint "$ROOT/web" 300 'npm run lint'
run_check web-test "$ROOT/web" 600 'npm test -- --maxWorkers=2'
run_check web-build "$ROOT/web" 600 'npm run build'
run_check web-no-mock "$ROOT/web" 120 'npm run check:no-mock'

java_version=""
java21=""
java_ok=0
if ((DRY_RUN)); then
  java_ok=1
  run_check java-test "$ROOT" 900 './gradlew test --no-daemon'
else
  java_version=$(java -version 2>&1 | head -n 1 || true)
  if [[ "$java_version" != *'21.'* && "$java_version" != *' version "21"'* ]]; then
    candidates=("${JAVA_HOME_21:-}" "${JAVA_HOME:-}" /usr/lib/jvm/temurin-21-jdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-21-openjdk /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)
    for candidate in "${candidates[@]}"; do
      if [[ -n "$candidate" && -x "$candidate/bin/java" ]] && "$candidate/bin/java" -version 2>&1 | head -n 1 | grep -q '21\.'; then java21="$candidate"; break; fi
    done
    if [[ -z "$java21" ]]; then
      printf 'Java 21 is required; current java is %s and no JDK 21 was found (JAVA_HOME_21/common paths).\n' "${java_version:-unavailable}" >&2
      failed=1
      skip_check java-test
    else
      export JAVA_HOME="$java21" PATH="$java21/bin:$PATH"
      java_ok=1
      run_check java-test "$ROOT" 900 './gradlew test --no-daemon'
    fi
  else
    java_ok=1
    run_check java-test "$ROOT" 900 './gradlew test --no-daemon'
  fi
fi
if ((WITH_DOCKER && java_ok)); then
  run_check java-integration "$ROOT" 1200 './gradlew :tests:integrationTest --no-daemon --console=plain'
else
  skip_check java-integration
fi
run_check ai-bridge-unittest "$ROOT" 180 'python -I -m unittest discover -s scripts/ai-bridge -p "test_*.py" -v'
if [[ -n "${GLORIOUS_URL:-}" && "$GLORIOUS_URL" != *:8088* ]]; then
  run_check playwright-glorious "$ROOT/tests/ui" 2100 'npm run test:glorious'
else
  skip_check playwright-glorious
fi
if [[ -n "${CLASSIC_URL:-}" ]]; then
  run_check playwright-classic "$ROOT/tests/ui" 2100 'npm test -- --config=playwright.config.ts'
else
  skip_check playwright-classic
fi

if ((DRY_RUN)); then exit 0; fi
printf '\n%-24s %-10s %-8s %s\n' 'suite' 'result' 'seconds' 'log file'
printf '%-24s %-10s %-8s %s\n' '-----' '------' '-------' '--------'
for row in "${rows[@]}"; do IFS='|' read -r suite result seconds logfile <<< "$row"; printf '%-24s %-10s %-8s %s\n' "$suite" "$result" "$seconds" "$logfile"; done
if ((failed)); then echo 'Overall: FAIL'; exit 1; fi
echo 'Overall: PASS'
