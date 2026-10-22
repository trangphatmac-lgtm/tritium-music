# Fragmented M4A regression fixtures

These fixtures are generated sine waves, not user music. Tests need neither
FFmpeg nor an audio device. Generated with FFmpeg 8.1.2:

```sh
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=0.25' -ac 2 -c:a aac -b:a 96k tone.m4a
ffmpeg -i tone.m4a -c:a copy -movflags +empty_moov+default_base_moof -frag_duration 64000 tone-fragmented.m4a
ffmpeg -i tone.m4a -c:a copy -movflags +empty_moov -frag_duration 64000 tone-fragmented-base-offset.m4a
```

The fragmented fixtures contain the same compressed samples as `tone.m4a`, split
across multiple `moof`/`mdat` pairs with an empty initial sample table. They cover
both fragment-relative and explicit base data offsets. Repackaging must preserve
every compressed sample byte-for-byte and decoding must cover all fragments.

## AutoMix PCM fixture

`automix-entry-stereo.flac` is generated test audio, not a recording: 3 seconds of constant PCM, 44.1 kHz, stereo, signed 16-bit; left amplitude 0.5 and right 0.25.

Regenerate with FFmpeg (not required when running tests):

```sh
ffmpeg -f lavfi -i 'aevalsrc=0.5|0.25:s=44100:d=3' -c:a flac -sample_fmt s16 automix-entry-stereo.flac
```

It checks lazy FLAC decoding, cue prefetch and stereo continuity without opening an audio device.
