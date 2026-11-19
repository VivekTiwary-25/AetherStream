"""Turn demo-raw.webm (from record_demo.py / record-demo.spec.ts) into the expo backup files.

    pip install imageio-ffmpeg        # bundled ffmpeg; a system ffmpeg on PATH also works
    python -I docs/expo/backup/post_process.py [--keep-raw]

- demo-backup.webm: 1440x900 VP9. The ~60 s wait for the sink queues to fill (moments.json "backpressure_wait")
  is played 6x faster with a "6x time-lapse" caption; everything else is real time.
- gif-*.gif: one GIF per moment (720 px wide, 8 fps, <= 4 MB each).
"""
import json
import shutil
import subprocess
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent
SPEEDUP = 6
FONT = "C\\:/Windows/Fonts/arial.ttf" if sys.platform == "win32" else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
GIFS = {  # gif name -> (moment, seconds to skip at the start, max length)
    "gif-1-city.gif": ("city", 0, 10),
    "gif-2-kill-recovery.gif": ("kill_recovery", 0, 22),
    "gif-3-compare.gif": ("compare", 0, 14),
    "gif-4-footer.gif": ("footer", 0, 8),
}


def ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    import imageio_ffmpeg  # noqa: PLC0415
    return imageio_ffmpeg.get_ffmpeg_exe()


def run(*args: str) -> None:
    subprocess.run([ffmpeg(), "-v", "error", "-y", *args], check=True)


def main() -> None:
    raw = OUT / "demo-raw.webm"
    meta = json.loads((OUT / "moments.json").read_text(encoding="utf-8"))
    m = meta["moments"]

    for name, (moment, skip, max_len) in GIFS.items():
        start, end = m[moment]
        start += skip
        length = min(end - start, max_len)
        for width, fps in ((720, 8), (640, 6), (560, 5)):
            run("-ss", f"{start}", "-t", f"{length}", "-i", str(raw), "-vf",
                f"fps={fps},scale={width}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=4",
                "-loop", "0", str(OUT / name))
            if (OUT / name).stat().st_size <= 4 * 1024 * 1024:
                break
        print(name, round((OUT / name).stat().st_size / 1e6, 2), "MB")

    w0, w1 = m.get("backpressure_wait", [None, None])
    caption = (f"drawtext=fontfile='{FONT}':text='{SPEEDUP}x time-lapse - sink queues filling':"
               "x=(w-tw)/2:y=h-70:fontsize=34:fontcolor=white:box=1:boxcolor=black@0.6:boxborderw=14")
    if w0 is not None and w1 - w0 > 3:
        graph = (f"[0:v]trim=0:{w0},setpts=PTS-STARTPTS[a];"
                 f"[0:v]trim={w0}:{w1},setpts=(PTS-STARTPTS)/{SPEEDUP},{caption}[b];"
                 f"[0:v]trim=start={w1},setpts=PTS-STARTPTS[c];[a][b][c]concat=n=3:v=1:a=0,fps=25[v]")
        run("-i", str(raw), "-filter_complex", graph, "-map", "[v]", *vp9(), str(OUT / "demo-backup.webm"))
    else:
        run("-i", str(raw), "-vf", "fps=25", *vp9(), str(OUT / "demo-backup.webm"))
    print("demo-backup.webm", round((OUT / "demo-backup.webm").stat().st_size / 1e6, 2), "MB")
    if "--keep-raw" not in sys.argv:
        raw.unlink()


def vp9() -> list[str]:
    return ["-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "42", "-deadline", "good", "-cpu-used", "5",
            "-row-mt", "1", "-pix_fmt", "yuv420p"]


if __name__ == "__main__":
    main()
