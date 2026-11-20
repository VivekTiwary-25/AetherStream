# UI preview standby

The preview runs on `vimlesh-pc` at `http://localhost:8089`; the college network blocks the public tunnel. `utkarsh-pc` is the hot standby. These are separate from the live demo.

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

Use `http://localhost:8089/` on `vimlesh-pc`; the college network blocks the public tunnel.

## No tunnel (college network)

The preview port publishes as `8089:80` without a host-IP restriction, so its host bind is `0.0.0.0:8089`. The presenter can always use `http://localhost:8089` in the laptop’s browser. For another device on the same Wi-Fi, allow inbound TCP 8089 in **Administrator PowerShell**:

```powershell
New-NetFirewallRule -DisplayName "Aether preview 8089" -Direction Inbound -Protocol TCP -LocalPort 8089 -Action Allow
```

Run `ipconfig` on the laptop and use its Wi-Fi adapter’s IPv4 Address (check); open `http://<laptop IPv4>:8089` on the other device.

## Ask AI

On the chosen host, from the repository root, run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ai-bridge/start.ps1
```

That PC needs Python 3.10+, Claude Code installed, and Claude Code logged in there. The bridge listens on `127.0.0.1:18090` by default. Without a working bridge/login, Ask AI should show its honest offline state; check `scripts\ai-bridge\.run\bridge.err.log` on that PC if startup fails.

## Do not

Never touch port **8088** or the live demo on `vivek-pc`. Use only the preview on **8089**; do not stop, restart, or otherwise alter the live demo.
