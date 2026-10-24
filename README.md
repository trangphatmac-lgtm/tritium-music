# Tritium Music

A Java desktop NetEase Cloud Music (NCM) player featured with Beautiful Render, system-level HUDs, QR-code login, cookie login, lyric translation/romaji, and music cache management. 

> Current target platforms: Desktop Systems like Windows x64, linux and macOS Apple Silicon.  
> Runtime target: Java 21.

## Feature List 

### Main Player

- QR-code login for NetEase Cloud Music accounts
- Cookie login and persistence
- Home page, playlists, search, and liked songs
- Switch between Sidebar list and Top navigation in Settings → Appearance → Layout; the choice is saved automatically. Top navigation includes Explore, My playlists, Saved playlists, Settings, and search, with dedicated scrollable playlist libraries.
- Play, pause, previous track, and next track
- Restore the last song, playback position, playing/paused state, queue (including play-next requests), and play mode on startup. State is saved on normal exit; a saved Heartbeat queue resumes in the preceding regular play mode. A song selected during startup takes priority over restoration.
- Windows 10/11 system media controls (SMTC): song, artist, album, cover art, playback status and timeline, with play/pause, previous/next and seeking from Windows and supported media keys. Enabled automatically; see [Windows SMTC](docs/windows-smtc.md).
- Stream uncached MP3, FLAC, WAV and M4A/MP4 songs after a short initial buffer while downloading and decoding in the background. Playback and lyrics wait when the buffer runs out; completed downloads remain reusable in the music cache. See [streaming playback](docs/streaming.md).
- Volume adjustment and seek bar dragging
- Optional smart transitions (AutoMix) in Settings → Playback, disabled by default and saved automatically. It follows the official client's feature-based transition approach for natural advancement to the next song, with a 5-second crossfade fallback when song features are unavailable. Manual track changes remain immediate. An optional Full AutoMix mode adds independent fade windows, 50 ms gain sampling and multi-stage EQ at the original playback speed. See [AutoMix details](docs/automix.md).
- Lyric page, word-by-word lyrics, translations, and romaji
- Automatically select the best lyrics from NetEase, AMLLDB, QQ Music and Kugou in Settings → Lyrics. Enabled by default with NetEase priority; source preference is saved and changes apply immediately. See [matching rules and verification](docs/best-lyrics.md).
- Cover loading and blurred backgrounds
- Navigation modes in Settings → Appearance: List (default), Coverflow (OldSchool, Mirror or Card), and Wall. Explore, My playlists, Saved playlists and playlist pages share the selected mode. Covers follow source order, with one entry per song instead of album grouping.
- OldSchool restores the original full-window Coverflow; Mirror retains screen-space reflections and the shared mirror plane. Both support the saved cover-reflection toggle.
- Folia Card offers artwork-only and Polaroid styles, full-bleed/square options, saved focus, and size/opacity falloff. Bravais Wall offers transparency, window count, stacked collection edges, backdrop lyrics and blur. Playlist cards form a compact single-row carousel with two neighbours on either side and flip into the song view; the wall repeats to fill the window. Both draw behind the original navigation and playback controls, including Blur/Liquid Glass materials, with hover, opening and reflow animations. All Coverflow styles have a back button and a darkened background in playlist details. The Bravais information strip stays hidden. See [navigation modes and controls](docs/navigation-modes.md).
- Music download cache and cache size display
- Simplified Chinese, Traditional Chinese, and English in Settings → General → Language; changes apply immediately and are saved automatically (default: Simplified Chinese)
- Settings → Playlist Export: select created or subscribed playlists and export HTML, Markdown, plain text, JSON, or CSV with optional song title, artists, NetEase ID, song URL, and duration. Selected playlists are combined in one UTF-8 file; JSON uses `name`/`songs` groups and string fields (duration is `m:ss` or `h:mm:ss`), while CSV includes a playlist column and a UTF-8 BOM for Excel.
- Light and dark modes in Settings → Appearance, with smooth transitions and a saved preference; lyrics and system HUDs keep their existing appearance
- Live accent colors with eight presets and an HSV picker in Settings → Appearance
- Custom main-window backgrounds in Settings → Main Background: static, picture, video, shader, and Latent. Enable “Apply background to lyrics page” to share any non-solid background with lyrics; the toggle is off by default and saved automatically. Lyrics retain their readability overlay, and system HUDs stay independent.
- Saturation/brightness, hue, and spectrum opacity controls with saved custom colors
- Optional FPS overlay in Settings → Debug → Show FPS
- M4A/MP4 cloud music playback (AAC and 16/24-bit Apple Lossless)
### System HUDs

- `musicInfo`: cover art, song title, artist/current lyric, playback progress, and download status
- `musicLyrics`: desktop lyrics, scrolling effect, alignment, shadow, single-line mode, translation/romaji
- `musicSpectrum`: desktop spectrum visualizer, rectangle/line styles, indicator line, color, and scale
- `musicToast`: independent, always-on-top playback notification at the top-left of the screen, with the artist and song title, pixel border, animated music notes, and slide animations; stays visible for about five seconds and refreshes on track changes
- Toggle playback notifications in Settings → System HUDs → Enable Playback Toast HUD; the setting is saved automatically
- HUDs are click-through by default. The playback toast does not take focus; the info, lyrics, and spectrum HUDs can be dragged, resized, and saved in edit mode

## Quick Start

### Requirements

- JDK 21+
- Maven 3.9+
- Windows x64, linux or macOS Apple Silicon.

### Clone and Build

```bash
mvn package
```
The tests will be automatically run.


After the build finishes, the fat jar is located at:

```text
target/tritium-music-1.x.x.jar
```

Portable packages are located at:

```text
target/tritium-music-1.x.x-portable.zip
target/tritium-music-1.x.x-portable.tar.gz
```

## Running 

### Interface styles

- Blur
- Liquid Glass
- Static

### Custom backgrounds

- Picture
- Video
- Shader
- Latent
- Cover: Solid, Blur, Nomand (five effects), Monet (two layouts)
- Static

### Windows

```bash
java -jar target/tritium-music-1.x.x.jar
```

### macOS Apple Silicon

```bash
java -jar target/tritium-music-1.x.x.jar
```

### Linux

```bash
java -jar target/tritium-music-1.x.x.jar
```

### Other Launch Arguments

```bash
# Exit automatically after 3 seconds for smoke tests
java -jar target/tritium-music-1.x.x.jar --smoke-exit-after=3

# Force a synthetic preview of the info, lyrics, and spectrum HUDs
java -jar target/tritium-music-1.x.x.jar --hud-smoke --smoke-exit-after=3

# Adjust UI size in window units (independent of Retina pixel density)
java -jar target/tritium-music-1.x.x.jar --ui-scale=1.25
```


## Packaging 

### Portable Packages

```bash
mvn package
```

The output includes:

- `lib/tritium-music.jar`
- `bin/tritium-music`
- `bin/tritium-music.bat`
- Native runtime source notes

### package

macOS Apple Silicon:

```bash
mvn -Pjpackage-macos-arm64 package
```

Windows x64:

```bash
mvn -Pjpackage-windows-x64 package
```

Package output directory:

```text
target/jpackage/
```

Note: package usually needs to run on the corresponding platform. For example, build Windows installers on Windows and macOS `.dmg` packages on macOS.

## Data Directories 

The app no longer writes cookies or caches to the current working directory.

### Windows

```text
%APPDATA%/Tritium Music
%APPDATA%/Tritium Music/cache/MusicCache
```

### macOS

```text
~/Library/Application Support/Tritium Music
~/Library/Caches/Tritium Music/MusicCache
```

Main files:

- `NCMCookie.txt`: NetEase Cloud Music login cookie
- `preferences.json`: player and HUD settings
- `playback.json`: last playback session (saved atomically on exit)
- `MusicCache/`: music cache
- `natives/`: native runtime extracted at startup

## Testing 

```bash
mvn test
```

On a desktop with a display, also validate actual framebuffer pixels, clipping, and resizing:

```bash
mvn -Dtritium.testRendering=true test
```

---
