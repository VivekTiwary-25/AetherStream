# Check-all run 4

Docker and Java integration checks skipped as instructed. Docker was not started. No UI endpoint checks were configured.
## Web — PASS

Command: `powershell.exe -NoProfile -NonInteractive -File scripts/check-all.ps1`; the runner applies per-command deadlines. `web-npm-ci` (44.3s), `web-lint` (9.9s), `web-test` (195s), `web-build` (84.3s), and `web-no-mock` (4.2s) all PASS. No failing test names or error lines.

## Java — SKIP

Runner command: `.\gradlew.bat test --no-daemon` (900s). SKIP: Java 21 required; current Java was not 21 and no JDK 21 was found in `JAVA_HOME_21`/common paths. No Java test ran. Java integration was SKIP (Docker disabled).

## ai-bridge — PASS

Command: `python -I -m unittest discover -s scripts/ai-bridge -p "test_*.py" -v` (180s); PASS (23.8s). No failing test names or error lines. Playwright suites SKIP because neither endpoint variable was configured; no URLs were contacted.

