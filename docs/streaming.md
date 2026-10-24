# Streaming playback

Uncached songs start after roughly a quarter second of decoded audio is available;
the network transfer and decoder continue in the background. Startup also depends
on network speed and the format's headers. Existing complete caches use the local
playback path. No setting or additional installation is required.

MP3, FLAC, WAV and M4A/MP4 (AAC/Apple Lossless) use the bundled FFmpeg decoder and
the existing JSyn output, so volume, spectrum analysis and AutoMix still share the
same audio engine. AutoMix's incoming deck retains its existing complete-download
and cue-prefetch preparation.

- Pause/resume and seeking remain available. Seeking beyond decoded audio waits
  for that position to arrive; seeking backward reads the temporary PCM cache.
- An underrun outputs silence and freezes the audio clock and lyrics. It does not
  count as the end of the song. Playback resumes when more PCM is available.
- HTTP Range can fetch M4A metadata at the end of a file without downloading the
  intervening audio first. A server that ignores Range still works sequentially;
  an M4A with metadata at its end may then require a complete download to start.
- Unknown-length/chunked responses support sequential streaming. MP4 containers
  that require seeking need a server providing a content length.
- Only complete downloads are promoted from a private `.part` file to the normal
  quality-specific cache. Interrupted downloads are discarded. Switching tracks
  cancels the old stream and releases its temporary files asynchronously.
- PCM is held in a temporary disk file with a two-second window in memory. Disk
  usage grows with decoded duration until the track is closed; RAM does not grow
  with song length. Network, decoder and disk operations stay off the audio thread.
- A network/decode failure ends the affected playback with an error message;
  selecting the song again retries it. There is no automatic network retry loop.

## Verification

`mvn test` includes a local HTTP server that withholds most of a WAV file until
the test has observed decoded stereo output, then checks buffer exhaustion,
frozen frame counts, resumption, seeking, true EOF, exact cached bytes and cleanup.
Range, ignored-Range, chunked and truncated HTTP responses are tested separately,
alongside compressed format decoding and the existing playback/AutoMix tests.
No NetEase account, external network or physical audio output is required.
