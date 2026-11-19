# Home loop video encode

These commands take the original 1264×720, 24 fps, 6.041667-second `home-loop.mp4`; restore that source before rerunning. The 0.75-second tail-to-head crossfade follows the retained middle segment, placing the loop boundary between adjacent head frames. The result is 127 frames (5.291667 s). H.264 uses CRF 26 to stay below 4 MB; VP9 at CRF 34 is optional only if its output is below 4 MB.

```sh
ffmpeg -i web/public/glorious/home-loop.mp4 -filter_complex "[0:v]trim=start=5.291667,setpts=PTS-STARTPTS,fps=24,settb=1/24[tail];[0:v]trim=start=0:duration=0.75,setpts=PTS-STARTPTS,fps=24,settb=1/24[head];[tail][head]xfade=transition=fade:duration=0.75:offset=0[seam];[0:v]trim=start=0.75:end=5.291667,setpts=PTS-STARTPTS,fps=24,settb=1/24[body];[body][seam]concat=n=2:v=1:a=0,format=yuv420p[out]" -map "[out]" -an -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 26 -movflags +faststart web/public/glorious/home-loop-encoded.mp4

ffmpeg -i web/public/glorious/home-loop.mp4 -filter_complex "[0:v]trim=start=5.291667,setpts=PTS-STARTPTS,fps=24,settb=1/24[tail];[0:v]trim=start=0:duration=0.75,setpts=PTS-STARTPTS,fps=24,settb=1/24[head];[tail][head]xfade=transition=fade:duration=0.75:offset=0[seam];[0:v]trim=start=0.75:end=5.291667,setpts=PTS-STARTPTS,fps=24,settb=1/24[body];[body][seam]concat=n=2:v=1:a=0,format=yuv420p[out]" -map "[out]" -an -c:v libvpx-vp9 -crf 34 -b:v 0 web/public/glorious/home-loop.webm

ffmpeg -i web/public/glorious/home-loop-encoded.mp4 -frames:v 1 -q:v 3 web/public/glorious/home-poster.jpg
```
