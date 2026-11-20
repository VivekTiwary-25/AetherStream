# Full repository checks

Run `powershell.exe -NoProfile -NonInteractive -File scripts/check-all.ps1` on Windows (PowerShell 5.1 or newer), or `bash scripts/check-all.sh` on Unix.

Each command has a timeout, exit status, and separate `.check-logs/` log; failures produce a nonzero exit code.
Use `-WithDocker` or `--with-docker` for container-backed Java integration tests; use `-DryRun` or `--dry-run` to print commands only.
Java checks require JDK 21. On Windows, the runner searches in this order without requiring `JAVA_HOME`:

1. `JAVA_HOME`
2. `JAVA_HOME_21_X64`
3. `JDK21_HOME`
4. `%ProgramFiles%\Eclipse Adoptium\jdk-21*`
5. `%ProgramFiles%\Java\jdk-21*`
6. `%ProgramFiles%\Microsoft\jdk-21*`
7. `%USERPROFILE%\.jdks\*21*`
8. `%USERPROFILE%\.gradle\jdks\*21*`

Every matching installation is considered until one contains both `bin\java.exe` and `bin\javac.exe` and reports Java 21; each version probe has a 10-second timeout.
The selected home is set as `JAVA_HOME`, prepended to `PATH`, and passed to both Java Gradle commands as `"-Dorg.gradle.java.home=C:\path with spaces\jdk-21"`.
If no JDK 21 is found, Java checks show `SKIP` and print every searched environment variable (including its value or `<unset>`) and folder pattern; a normal run still returns a nonzero exit status because the required Java check could not run.
`-DryRun` also performs discovery and prints either the selected home and quoted Gradle commands or the same search diagnostics, without running the suites.
On Unix, set `JAVA_HOME_21` to the JDK 21 installation when needed.

Web dependencies install with `npm ci` only if `web/node_modules` is absent.

Playwright checks are opt-in: set `GLORIOUS_URL` for new UI and `CLASSIC_URL` for classic UI tests.
The new UI suite refuses URLs containing `:8088`; install `tests/ui` dependencies and browsers first.
Logs are retained locally and ignored by Git; timeouts are fixed per command.
On Unix, the runner needs GNU `timeout` to enforce command deadlines.
`CLASSIC_URL` enables the classic Playwright suite using its configured test endpoint.
Skipped optional checks appear as `SKIP` in the summary.
