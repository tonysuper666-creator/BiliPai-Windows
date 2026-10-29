# Synthetic decoder samples

The six MP4 clips are generated from FFmpeg's `testsrc2`, with no third-party video, audio,
accounts or metadata. Each is 42 frames / 0.7 seconds at 60fps, 4:2:0 8-bit, in 1920x1080 and
3840x2160. The self-test verifies `SHA256SUMS` before loading a clip and mutes audio.
These clips measure decoder availability and a conservative decode-speed limit, not perceptual
quality, presentation performance, HDR output or vendor super-resolution activation.

Reproduction (FFmpeg with libx264/libx265/libsvtav1):

```sh
ffmpeg -f lavfi -i 'testsrc2=size=3840x2160:rate=60:duration=0.7' -an -c:v libx264 -preset ultrafast -crf 35 -threads 2 -pix_fmt yuv420p avc-2160.mp4
ffmpeg -f lavfi -i 'testsrc2=size=3840x2160:rate=60:duration=0.7' -an -c:v libx265 -preset ultrafast -crf 35 -x265-params 'pools=2:frame-threads=2:log-level=error' -pix_fmt yuv420p hevc-2160.mp4
ffmpeg -f lavfi -i 'testsrc2=size=3840x2160:rate=60:duration=0.7' -an -c:v libsvtav1 -preset 12 -crf 45 -svtav1-params lp=4 -pix_fmt yuv420p av1-2160.mp4
```

Repeat at 1920x1080 with the `1080` suffix. Encoded bytes depend on encoder version; update the
manifest from the actual generated files. This set was generated using FFmpeg 9 / SVT-AV1 4.2.0.

The self-test uses a hidden, never-shown D3D11 host so the selected adapter is actually honored.
Pinned mpv's null-VO d3d11va-copy path selects the default adapter and cannot validate Optimus.
The test requires an observed matching adapter LUID and hardware decoder, and never requests SR/HDR.
