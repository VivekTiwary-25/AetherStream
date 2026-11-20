# ui-glorious preview

Scripts support Windows PowerShell 5.1; HTTP requests use basic parsing and explicit timeouts.

- Start or update the stack and detached quick tunnel: `powershell -NoProfile -ExecutionPolicy Bypass -File C:\aether-preview\.preview\preview-up.ps1`
- Refresh from `origin/ui-glorious`: `powershell -NoProfile -ExecutionPolicy Bypass -File C:\aether-preview\.preview\preview-refresh.ps1` (rebuilds `web`, and also `control-api` if API/build inputs changed; Docker step timeout: 270 seconds).
- Stop the tunnel and containers: `powershell -NoProfile -ExecutionPolicy Bypass -File C:\aether-preview\.preview\preview-down.ps1`
- The UI uses `http://localhost:8089`; the public URL is stored in `C:\aether-preview\.preview\url.txt`.

## Refresh after a new merge
Run this after the merge reaches `origin/ui-glorious`:
`powershell -NoProfile -ExecutionPolicy Bypass -File C:\aether-preview\.preview\preview-refresh.ps1`
