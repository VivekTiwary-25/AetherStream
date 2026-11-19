# Glorious host AI bridge

Requires Python 3.10+ and Claude Code installed and logged in. No API key.

From the repository root, start with `powershell -ExecutionPolicy Bypass -File scripts/ai-bridge/start.ps1` (Windows) or `sh scripts/ai-bridge/start.sh` (Unix). Stop with `Stop-Process -Id (Get-Content scripts/ai-bridge/.run/bridge.pid)` on Windows or `kill "$(cat scripts/ai-bridge/.run/bridge.pid)"` on Unix. Logs and PID are under `scripts/ai-bridge/.run/`.

The listener defaults to `127.0.0.1:18090`. Docker Desktop forwards `host.docker.internal` to the host's localhost, so nginx in the web container still reaches it (verified on vivek-pc), and the bridge is not exposed on the expo Wi-Fi. Use `AI_BIND=0.0.0.0` only on a Linux Docker engine where host-gateway cannot reach localhost.

Environment: `AI_CMD` (default `claude`, executable path), `AI_MODEL` (default `claude-haiku-5-5`), `CLAUDE_CONFIG_DIR` (optional Claude login profile), `AI_PORT` (default `18090`), and `AI_VALIDATE_URL` (default `http://localhost:8088/api/glorious/pipelines/validate`). The Windows launcher uses `%USERPROFILE%\.swarm\profiles\vachana-claude\claude` only when it exists and no config dir was supplied.

Routes: `GET /health`, `POST /generate` with `{"prompt":"..."}`. Prompts must be non-empty and at most 500 characters. The bridge repairs common model output issues (fences/prose, numeric strings, and omitted linear edges), validates against the control API, and falls back to local structural validation when unreachable; saved schema examples are used if Claude fails.

## Tests

From the repository root:

```sh
python -m unittest discover -s scripts/ai-bridge -p 'test_*.py' -v
```

Tests use a fake Python CLI; they never call Claude Code.

## Limits

Only one generation runs at a time. Request bodies are limited to 4 KB and prompts to 500 characters.
The bridge binds to localhost by default; widen it with `AI_BIND` only if Docker cannot reach it.
