# Glorious host AI bridge

Requires Python 3.10+ and Claude Code installed and logged in. No API key.

From the repository root, start with `powershell -ExecutionPolicy Bypass -File scripts/ai-bridge/start.ps1` (Windows) or `sh scripts/ai-bridge/start.sh` (Unix). Stop with `Stop-Process -Id (Get-Content scripts/ai-bridge/.run/bridge.pid)` on Windows or `kill "$(cat scripts/ai-bridge/.run/bridge.pid)"` on Unix. Logs and PID are under `scripts/ai-bridge/.run/`.

The listener defaults to `0.0.0.0:18090` for Docker's `host.docker.internal` proxy. Windows Firewall may ask permission; allow private-network access if Docker cannot connect. Set `AI_BIND=127.0.0.1` only for host-local testing.

Environment: `AI_CMD` (default `claude`, executable path), `AI_MODEL` (default `claude-haiku-5-5`), `CLAUDE_CONFIG_DIR` (optional Claude login profile), `AI_PORT` (default `18090`), and `AI_VALIDATE_URL` (default `http://localhost:8088/api/glorious/pipelines/validate`). The Windows launcher uses `%USERPROFILE%\.swarm\profiles\vachana-claude\claude` only when it exists and no config dir was supplied.

Routes: `GET /health`, `POST /generate` with `{"prompt":"..."}`. The bridge validates against the control API, falling back to local structural validation when unreachable; saved schema examples are used if Claude fails.

## Tests

From the repository root:

```sh
python -m unittest discover -s scripts/ai-bridge -p 'test_*.py' -v
```

Tests use a fake Python CLI; they never call Claude Code.

## Limits

Only one generation runs at a time. Request bodies are limited to 4 KB and prompts to 500 characters.
The bridge binds on all interfaces by default for Docker reachability; restrict it with `AI_BIND` when Docker access is not needed.
