# C: disk usage and safe cleanup

Measured on 2026-11-20. `Get-PSDrive C` before cleanup: 4,031,574,016 bytes free (3.75 GiB). After cleanup: 6,439,788,544 bytes free (6.00 GiB), a 2.25 GiB increase. The 15 GiB target remains short by 9.00 GiB.

## Largest top-level folders before cleanup

Folder sizes are recursive; scans completed for all top-level folders in both roots. Values in GiB.

| `%USERPROFILE%` folder | GiB |
|---|---:|
| `Downloads` | 6.619 |
| `Documents` | 4.600 |
| `.cache` | 3.100 |
| `.codex` | 2.806 |
| `CrossDevice` | 2.094 |
| `.gradle` | 1.786 |
| `Desktop` | 0.622 |
| `.local` | 0.561 |
| `.vscode` | 0.560 |
| `Space_debris_collision_predictor` | 0.472 |
| `.omp` | 0.347 |
| `.swarm` | 0.325 |
| `OrbitSentinel-Sample` | 0.324 |
| `.gemini` | 0.232 |
| `.venv` | 0.222 |

| `%LOCALAPPDATA%` folder | GiB |
|---|---:|
| `wsl` | 8.403 |
| `Docker` | 7.638 |
| `Packages` | 4.589 |
| `Programs` | 4.315 |
| `Microsoft` | 3.380 |
| `BraveSoftware` | 2.469 |
| `Google` | 2.087 |
| `Android` | 1.482 |
| `Perplexity` | 1.330 |
| `npm-cache` | 1.214 |
| `OpenAI` | 0.745 |
| `JetBrains` | 0.702 |
| `pip` | 0.443 |
| `Claude-Data` | 0.441 |
| `NVIDIA Corporation` | 0.414 |

## Requested paths

| Path | Before (GiB) | After (GiB) | Result |
|---|---:|---:|---|
| `%USERPROFILE%\.gradle\caches` | 1.214 | 1.214 | Preserved: Java process PID 24924 was running; no Gradle cache removal. |
| `%LOCALAPPDATA%\npm-cache` | 1.214 | 0.026 | `npm cache clean --force` ran; `npm cache verify` reports 0 content bytes and 0 index entries. |
| `%APPDATA%\npm-cache` | absent | absent | — |
| `%LOCALAPPDATA%\pip\Cache` | 0.443 | absent | Cache directory removed. |
| `%LOCALAPPDATA%\Temp` | 0.156 | 0.156 | Attempted to remove contents; all 179 enumerated files were locked by running processes and were skipped. |
| `%USERPROFILE%\.m2\repository` | absent | absent | — |
| `%LOCALAPPDATA%\Docker\wsl` | 7.620 | 7.620 | Measured only; not touched. |
| `%LOCALAPPDATA%\ms-playwright` | absent | absent | — |
| `C:\Windows\Temp` | 0.000 | 0.000 | Measured only. |
| Recycle Bin (`C:\$Recycle.Bin`, readable files) | 0.000 | 0.000 | Measured only. |

## Human decisions to reach 15 GiB free

**Human decisions only; no files outside the listed caches were removed.** The largest visible candidates are `%LOCALAPPDATA%\Docker` (7.638 GiB; its `Docker\wsl` path alone is 7.620 GiB), `%USERPROFILE%\Downloads` (6.619 GiB), and `%USERPROFILE%\Documents` (4.600 GiB). The 9.00 GiB gap could be covered by a human review of old Downloads plus an appropriate portion of another candidate. Docker data must only be considered through Docker Desktop's **Clean / Purge data** workflow or a human-managed move of its disk image to another drive; do not delete Docker/WSL files directly. Review personal files before removing them; uninstall apps through their normal uninstallers rather than deleting `%LOCALAPPDATA%\Packages` or `Programs` directly.

Top five measured space users across the two roots: `%LOCALAPPDATA%\wsl` (8.403 GiB), `%LOCALAPPDATA%\Docker` (7.638 GiB), `%USERPROFILE%\Downloads` (6.619 GiB), `%USERPROFILE%\Documents` (4.600 GiB), `%LOCALAPPDATA%\Packages` (4.589 GiB).
