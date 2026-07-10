# 🔥🔥Tritium Music🔥🔥

A standalone desktop NetEase Cloud Music (NCM) player Developed in Java.  🎵
It has interface, shaders, fonts, and control system with features such as system-level HUDs, QR-code login, cookie login, lyric translation/romaji, and music cache management. ✨

> Current target platforms: Desktop Systems like Windows x64, linux and macOS Apple Silicon.  
> Runtime target: Java 21.

## Highlights 🚀

- 🎧 **Standalone desktop player**: With features such as system-level HUDs, QR-code login, cookie login, lyric translation/romaji, and music cache management.
- 🖼️ **Original custom-rendered UI**: Beautiful Render includes the cover wall, blurred background, animations, shaders, and font rendering.
- 🔐 **Two login methods**: Supports NetEase Cloud Music QR-code login and raw cookie login.
- 📚 **Complete core features**: Playlists, search, liked songs, playback controls, seek bar dragging, volume control, and download caching.
- 📝 **Lyric experience**: Supports regular lyrics, word-by-word lyrics, translations, romaji, and single-line or multi-line display.
- 🪟 **System-level HUDs**: `musicInfo`, `musicLyrics`, and `musicSpectrum` have been migrated into transparent always-on-top desktop HUDs.
- 🛠️ **HUD layout editing**: Enable edit mode to drag and resize HUDs, then persist their positions.
- 🧹 **Cache management**: The settings page shows current cache usage and provides one-click cache clearing.
- 🍎 **Apple Silicon support**: Includes a macOS arm64 runtime library and system level support.
- 📦 **Ready for distribution**: Supports Maven shade fat jars, portable zip/tar.gz packages, and package installers.

## Feature List ✅

### Main Player

- QR-code login for NetEase Cloud Music accounts
- Cookie login and persistence
- Home page, playlists, search, and liked songs
- Play, pause, previous track, and next track
- Volume adjustment and seek bar dragging
- Lyric page, word-by-word lyrics, translations, and romaji
- Cover loading, blurred backgrounds, and music toasts
- Music download cache and cache size display

### System HUDs

- `musicInfo`: cover art, song title, artist/current lyric, playback progress, and download status
- `musicLyrics`: desktop lyrics, scrolling effect, alignment, shadow, single-line mode, translation/romaji
- `musicSpectrum`: desktop spectrum visualizer, rectangle/line styles, indicator line, color, and scale
- Click-through by default; in edit mode, HUDs can be dragged, resized, and saved

## Quick Start ⚡

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
target/tritium-music-1.0.2.jar
```

Portable packages are located at:

```text
target/tritium-music-1.0.2-portable.zip
target/tritium-music-1.0.2-portable.tar.gz
```

## Running 🏃

### Windows

```bash
java -jar target/tritium-music-1.0.2.jar
```

### macOS Apple Silicon

```bash
java -jar target/tritium-music-1.0.2.jar
```

### Linux

```bash
java -jar target/tritium-music-1.0.2.jar
```

### Other Launch Arguments

```bash
# Exit automatically after 3 seconds for smoke tests
java -jar target/tritium-music-1.0.2.jar --smoke-exit-after=3

# Force a synthetic preview of all three HUDs
java -jar target/tritium-music-1.0.2.jar --hud-smoke --smoke-exit-after=3

# Adjust UI scaling
java -jar target/tritium-music-1.0.2.jar --ui-scale=1.25
```


## Packaging 📦

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

## Data Directories 🗂️

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
- `natives/`: LWJGL2/JInput native runtime extracted at startup

## Native Runtime 🧩

The project already included native packages in the repository.

At startup, `DesktopNativeLoader` will automatically extract the native runtime for the current OS/architecture.

## Project Structure 🧭

```text
src/main/java/tritium/desktop
  Desktop entry point, configuration, paths, cache, cookies, native loader, screen manager

src/main/java/tritium/desktop/hud
  System-level HUD management, platform windows, state snapshots, and three HUD renderers

src/main/java/tritium/screens/ncm
  Main player window, login overlay, lyric page, and player panels

src/main/java/tritium/ncm
  NetEase Cloud Music API, encryption, requests, DTOs, and playback logic

src/main/java/tritium/rendering
  OpenGL rendering, fonts, shaders, animations, textures, and control system

src/main/java/repackage
  Source code for audio playback-related dependencies

src/main/resources/tritium
  Fonts, shaders, textures, native runtime, and test lyric resources
```

## Testing 🧪

```bash
mvn test
```

''''
Disclaimer : This project is intended only for learning, research, and personal use. Music copyrights belong to their respective rights holders. Please follow the NetEase Cloud Music terms of service and the laws and regulations of your region.
''''
---