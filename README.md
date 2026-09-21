# Tritium Music

A Java desktop NetEase Cloud Music (NCM) player featured with Beautiful Render, system-level HUDs, QR-code login, cookie login, lyric translation/romaji, and music cache management. 

> Current target platforms: Desktop Systems like Windows x64, linux and macOS Apple Silicon.  
> Runtime target: Java 21.

## Feature List 

### Main Player

- QR-code login for NetEase Cloud Music accounts
- Cookie login and persistence
- Home page, playlists, search, and liked songs
- Play, pause, previous track, and next track
- Volume adjustment and seek bar dragging
- Lyric page, word-by-word lyrics, translations, and romaji
- Cover loading and blurred backgrounds
- Music download cache and cache size display
- M4A/MP4 cloud music playback (AAC and 16/24-bit Apple Lossless), with bundled Java decoders

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

This check opens a temporary test window.

---
