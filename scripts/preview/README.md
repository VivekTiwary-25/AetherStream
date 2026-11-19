# ui-glorious preview

- Start or update the stack and detached quick tunnel: `powershell -NoProfile -ExecutionPolicy Bypass -File C:\aether-preview\.preview\preview-up.ps1`
- Refresh changed app images: `powershell -NoProfile -ExecutionPolicy Bypass -File C:\aether-preview\.preview\preview-refresh.ps1`
- Stop the tunnel and containers: `powershell -NoProfile -ExecutionPolicy Bypass -File C:\aether-preview\.preview\preview-down.ps1`
- The UI uses `http://localhost:8089`; the public URL is stored in `C:\aether-preview\.preview\url.txt`.
