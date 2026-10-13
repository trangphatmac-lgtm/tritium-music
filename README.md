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
- Light and dark modes in Settings → Appearance, with smooth transitions and a saved preference; lyrics and system HUDs keep their existing appearance
- Live accent colors with eight presets and an HSV picker in Settings → Appearance
- Custom main-window backgrounds in Settings → Main Background: static, picture, video, and shader; lyrics and system HUDs retain their existing appearance
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

Open **设置 → 外观 → 界面风格** to switch between **Static**, **Blur · 毛玻璃**, and **Liquid Glass**. Left-click cycles forward; right-click cycles backward. The selection applies immediately and is saved automatically. Existing configurations default to Static.

- **Static** keeps the original flat controls.
- **Blur** uses frosted, softly tinted surfaces with diffused backgrounds.
- **LiquidGlass** uses clearer surfaces, rounded refracting edges, subtle color dispersion, and highlights that respond to the pointer. The optical approach is inspired by [LiquidGlassRenderer](https://github.com/bakabaicai/PVPUtils/blob/main/src/client/java/com/pvp_utils/client/render/skia/LiquidGlassRenderer.java), implemented here for the existing OpenGL renderer.

Materials apply to main-window panels, buttons, inputs, sliders, and switches. They work with dark/light appearance and custom backgrounds. On the lyrics page, only the translation button and the hovered lyric's highlight use the selected material; the album background, artwork, typography, transport controls, progress and volume bars retain their existing appearance. Desktop HUD appearance is unchanged. If glass shaders are unavailable, the app falls back to flat controls; switching styles retries initialization.

### Custom backgrounds

Open **设置 → 主界面背景** and click **背景模式** to cycle through the modes (right-click cycles backwards). Changes apply immediately and are saved automatically.

- **static**: the existing solid background, following the dark/light appearance setting.
- **picture**: choose a JPG, PNG, BMP, or GIF file (GIF uses its first frame), or a folder for a slideshow. Folder playback uses supported files directly in that folder, with a 5–300 second interval and optional shuffle. Unreadable images are skipped. Use **重新加载背景 / 刷新文件夹** after changing folder contents.
- **video**: choose a local MP4, M4V, MOV, WebM, MKV, or AVI. Playback loops silently, independently of music playback. FFmpeg is bundled for the supported desktop platforms; no separate installation is needed.
- **shader**: choose **星云 / Nebula** (based on the supplied shader), **极光 / Aurora**, or **流光 / Waves**, with adjustable animation speed.

Images and videos support fill/crop, fit, and stretch. **背景强度** controls visibility against the current dark/light background; **面板不透明度** controls the navigation and player panels, with a readability tint on settings cards. File paths are saved as references, so the selected files must remain available. Missing or unsupported media falls back to the solid background and displays a status message; choose another file or reload to retry.

In Blur and Liquid Glass, **歌曲行不透明度** (default 28%) and **歌词悬停高亮不透明度** (default 50%) appear beside the panel opacity setting. Both range from 0–100%, update immediately, and are saved independently. Song hover/playing states remain slightly stronger, while text and icons keep their visibility. These controls also work with a static background and do not affect Static interface style or the HUD.

Media loads off the render thread. Images are bounded to 2560 pixels on their longest edge; video textures are bounded to 1920×1080 and uploaded at up to 30 fps. Videos, slideshows, and shader animation pause while the main background is hidden by the lyrics page or the window is minimized.

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
