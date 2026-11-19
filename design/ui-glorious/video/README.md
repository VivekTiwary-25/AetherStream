# Home loop video encode

These commands take the original 1264×720, 24 fps, 6.041667-second `home-loop.mp4`; restore that source before rerunning. The 0.75-second tail-to-head crossfade follows the retained middle segment, placing the loop boundary between adjacent head frames. The result is 127 frames (5.291667 s). H.264 uses CRF 26 to stay below 4 MB; VP9 at CRF 34 is optional only if its output is below 4 MB.

```sh
ffmpeg -i web/public/glorious/home-loop.mp4 -filter_complex "[0:v]trim=start=5.291667,setpts=PTS-STARTPTS,fps=24,settb=1/24[tail];[0:v]trim=start=0:duration=0.75,setpts=PTS-STARTPTS,fps=24,settb=1/24[head];[tail][head]xfade=transition=fade:duration=0.75:offset=0[seam];[0:v]trim=start=0.75:end=5.291667,setpts=PTS-STARTPTS,fps=24,settb=1/24[body];[body][seam]concat=n=2:v=1:a=0,format=yuv420p[out]" -map "[out]" -an -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 26 -movflags +faststart web/public/glorious/home-loop-encoded.mp4

ffmpeg -i web/public/glorious/home-loop.mp4 -filter_complex "[0:v]trim=start=5.291667,setpts=PTS-STARTPTS,fps=24,settb=1/24[tail];[0:v]trim=start=0:duration=0.75,setpts=PTS-STARTPTS,fps=24,settb=1/24[head];[tail][head]xfade=transition=fade:duration=0.75:offset=0[seam];[0:v]trim=start=0.75:end=5.291667,setpts=PTS-STARTPTS,fps=24,settb=1/24[body];[body][seam]concat=n=2:v=1:a=0,format=yuv420p[out]" -map "[out]" -an -c:v libvpx-vp9 -crf 34 -b:v 0 web/public/glorious/home-loop.webm

ffmpeg -i web/public/glorious/home-loop-encoded.mp4 -frames:v 1 -q:v 3 web/public/glorious/home-poster.jpg
```

## Grok watermark crop (2026-11-20)

The watermark ("Grok" logo, bottom-right) sat at about x 1140–1238, y 661–692 in every frame. It was removed by cropping only: a 1144×652 crop from the top-left (bottom edge 8 px above the logo, aspect 1.7546 vs 1.7556), scaled back to 1264×720 with lanczos. Same crop on every frame, no re-timing. The seam SSIM (last vs first frame) is 0.886 against 0.885 before when both are measured at the same scale.

```sh
ffmpeg -i web/public/glorious/home-loop.mp4 -vf "crop=1144:652:0:0,scale=1264:720:flags=lanczos,format=yuv420p" -an -r 24 -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 23 -movflags +faststart home-loop-cropped.mp4
ffmpeg -i home-loop-cropped.mp4 -frames:v 1 -q:v 3 web/public/glorious/home-poster.jpg
# before/after corner checks (5x zoom of x 1064–1263, y 600–679):
ffmpeg -i frame.png -vf "crop=200:80:1064:600,scale=1000:400:flags=neighbor" watermark-before.png
```
