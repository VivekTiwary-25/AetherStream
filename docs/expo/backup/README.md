# Expo backup recording

Use these files if the live demo or the venue network fails. Everything here plays offline.

Recorded on 2026-11-19 against the live deployed app (public tunnel link). The deployed commit was
`1035c378eb873ea6ea17816651b2c9cdddff90c7` (footer: "Deployed version: 1035c37"). It was deployed by CD run
https://github.com/VivekTiwary-25/AetherStream/actions/runs/44460264804.

## Files

| File | What it shows |
|---|---|
| [demo-backup.webm](demo-backup.webm) | The full demo, 1280x720, about 2 min 11 s (VP9, ~4 MB). The first ~12 s is the page loading. |
| [gif-1-city.gif](gif-1-city.gif) | Truck city with the glowing event stream flowing into the stream engine (10 s). |
| [gif-2-kill-recovery.gif](gif-2-kill-recovery.gif) | Kill worker -> confirm -> amber "Recovering from checkpoint #206" -> recovered, checkpoints resume (22 s). The red / Dead worker row is not in view in this take (the camera stays on the header banner). |
| [gif-3-compare.gif](gif-3-compare.gif) | Compare with reference -> "3,785 / 3,785 results match" (12 s). |
| [gif-4-footer.gif](gif-4-footer.gif) | Footer with the deployed version `1035c37` (8 s). |
| [github-actions-green.png](github-actions-green.png) | Green GitHub Actions runs on `main`. |
| [github-cd-deploy.png](github-cd-deploy.png) | The CD run that published and deployed the recorded commit. |
| [moments.json](moments.json) | Start and end second of each moment in the raw recording, plus the commit and URL. |
| [record_demo.py](record_demo.py) | Playwright script that recorded the demo. |
| [post_process.py](post_process.py) | ffmpeg script that made the final `.webm` and the GIFs. |

Timeline of `demo-backup.webm` (approximate seconds):

| Time | Moment |
|---|---|
| 0:12 - 0:22 | City, trucks and the glowing event stream |
| 0:22 - 0:45 | Panels open and update live (speed windows, deliveries, workers, ...) |
| 0:45 - 1:17 | Kill worker -> "Recovering from checkpoint #206" -> recovered |
| 1:17 - 1:24 | Trucks keep driving after recovery |
| 1:28 - 1:40 | Compare with reference -> "3,785 / 3,785 results match" |
| 1:40 - 2:01 | Slow down output -> amber "Backpressured" -> back to normal |
| 2:01 - 2:11 | Footer: deployed version |

## Play fullscreen offline

- Browser (works everywhere): drag `demo-backup.webm` or a GIF into Chrome, Edge or Firefox, then press `F11`
  (or double-click the video) for fullscreen.
- VLC: `vlc --fullscreen --loop docs/expo/backup/demo-backup.webm`.
- Windows: Movies & TV / Media Player play `.webm`; press `Alt+Enter` for fullscreen.

## Record again

```
pip install playwright imageio-ffmpeg
python -I docs/expo/backup/record_demo.py https://<public-link>       # writes demo-raw.webm + moments.json
python -I docs/expo/backup/post_process.py                            # writes demo-backup.webm + 4 GIFs, deletes the raw file
```

Note: recording kills a worker and slows the sink on the live system (it switches the sink back off at the end).
Playwright's temporary files go into `_video_tmp/`, which is git-ignored.
