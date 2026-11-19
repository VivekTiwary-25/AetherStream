# Full repository checks

Run `pwsh -File scripts/check-all.ps1` on Windows or `bash scripts/check-all.sh` on Unix.

Each command has a timeout, exit status, and separate `.check-logs/` log; failures produce a nonzero exit code.
Use `-WithDocker` or `--with-docker` for container-backed Java integration tests; use `-DryRun` or `--dry-run` to print commands only.
Java checks require JDK 21; set `JAVA_HOME_21` to its installation when needed.
Web dependencies install with `npm ci` only if `web/node_modules` is absent.

Playwright checks are opt-in: set `GLORIOUS_URL` for new UI and `CLASSIC_URL` for classic UI tests.
The new UI suite refuses URLs containing `:8088`; install `tests/ui` dependencies and browsers first.
Logs are retained locally and ignored by Git; timeouts are fixed per command.
On Unix, the runner needs GNU `timeout` to enforce command deadlines.
`CLASSIC_URL` enables the classic Playwright suite using its configured test endpoint.
Skipped optional checks appear as `SKIP` in the summary.
