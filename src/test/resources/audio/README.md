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
