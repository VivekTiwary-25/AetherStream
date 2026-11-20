# UI preview standby

The preview runs independently on `vimlesh-pc` (main) and `utkarsh-pc` (hot standby), on port **8089**. These are separate from the live demo.

## Check a host

On the selected PC, open `http://localhost:8089/` and `http://localhost:8089/api/health`; the health response should report `UP` or `OK`. For the full GET-only check from the repository root, run in PowerShell:

```powershell
$env:AETHER_URL = 'http://localhost:8089'
.\scripts\expo\preflight.cmd
```

## Refresh after a merge

After the merge reaches `origin/ui-glorious`, run on that host from its checkout:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\aether-preview\.preview\preview-refresh.ps1
```

The script fetches `origin/ui-glorious`, resets the preview checkout to it, builds and updates `web`, and also rebuilds `control-api` when its inputs changed. Re-run the health checks above.

## Switch presenter

Read the other host’s `C:\aether-preview\.preview\url.txt` and navigate the presenter’s browser to that exact URL. The quick-tunnel URL can change when the host starts a new tunnel; do not reuse a URL from memory. Current URL: **(check)**.

## Ask AI

On the chosen host, from the repository root, run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ai-bridge/start.ps1
```

That PC needs Python 3.10+, Claude Code installed, and Claude Code logged in there. The bridge listens on `127.0.0.1:18090` by default. Without a working bridge/login, Ask AI should show its honest offline state; check `scripts\ai-bridge\.run\bridge.err.log` on that PC if startup fails.

## Do not

Never touch port **8088** or the live demo on `vivek-pc`. Use only the preview on **8089**; do not stop, restart, or otherwise alter the live demo.
