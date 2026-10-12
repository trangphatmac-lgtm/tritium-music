# Background video fixture

`loop.mp4` is a synthetic 96×64, 12 fps, 0.5 second H.264 test pattern with no audio.
It verifies decoding, repeated looping, pause/resume and file release without personal media.

Generated with:

```sh
ffmpeg -f lavfi -i 'testsrc2=size=96x64:rate=12:duration=0.5' -an -c:v libx264 -pix_fmt yuv420p loop.mp4
```

`single-frame.mp4` checks that a one-second still video holds its frame at the loop boundary instead of decoding continuously:

```sh
ffmpeg -f lavfi -i 'color=c=blue:size=32x32:rate=1:duration=1' -an -c:v libx264 -pix_fmt yuv420p single-frame.mp4
```
