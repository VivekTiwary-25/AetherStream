# Check Docker on this PC and, if it works, bring up the ui-glorious preview here

Files: none to edit (only fix scripts/preview/*.ps1 if they have a bug). Do NOT read or describe swarm/.

You are on upendra-pc. The preview could not run on utkarsh-pc or vimlesh-pc (Docker not working there). An earlier task here said "Docker is unavailable" without details: find out precisely (installed? engine down? WSL?).
1. Run `docker info` with a 30 s timeout (Start-Process + WaitForExit). If it fails and Docker Desktop is installed, start it without admin (`Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"`) and poll `docker info` every 15 s for up to 6 minutes. Do not change Docker settings. If Docker is not installed or never comes up: report exactly that and stop.
2. Check free RAM and disk (`Get-CimInstance Win32_OperatingSystem` FreePhysicalMemory, `Get-PSDrive C`). If free RAM < 6 GB or free disk < 20 GB, report and stop.
3. Run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/preview/preview-up.ps1` (allow up to 25 min). If a host port is already in use, report which and by what; do not stop other stacks.
4. Verify http://localhost:8089/ (HTML), /api/health, `docker compose -p aether-preview ps` all healthy, and the tunnel URL in C:\aether-preview\.preview\url.txt returns 200 for /.
Report: Docker yes/no, RAM/disk, the tunnel URL, service status list.
