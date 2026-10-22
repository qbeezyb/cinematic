# Synthetic language fixture

`languages.mkv` is a two-second, 16×16 black MPEG-4 video with mono FLAC audio:
container stream 1 is tagged `deu` / German (440 Hz), and stream 2 is tagged
`eng` / English (880 Hz, default). Distinct tones verify the actual decoded track,
not just metadata. The file is generated from FFmpeg test sources; it contains no
third-party recordings. The default intentionally differs from the first track.

Regenerate with an installed FFmpeg (the mod itself does not need it):

```sh
ffmpeg -y -f lavfi -i color=c=black:s=16x16:r=10:d=2 \
  -f lavfi -i sine=frequency=440:sample_rate=48000:duration=2 \
  -f lavfi -i sine=frequency=880:sample_rate=48000:duration=2 \
  -map 0:v -map 1:a -map 2:a -c:v mpeg4 -c:a flac \
  -metadata:s:a:0 language=deu -metadata:s:a:0 title=German \
  -metadata:s:a:1 language=eng -metadata:s:a:1 title=English \
  -disposition:a:0 0 -disposition:a:1 default tests/fixtures/languages.mkv
```

JUnit also generates single-audio WAV and video-only MP4 fixtures. Missing and
duplicate language tags, metadata bounds, and fallback rules are covered by
`PlaybackStateTest`.
