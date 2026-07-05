# Tritium Music Native Runtime Sources

Bundled native runtime jars are extracted by `tritium.desktop.DesktopNativeLoader` before LWJGL2 creates the display.

## Windows x64

- `windows-x64/lwjgl-platform-2.9.3-natives-windows.jar`
  - Source: `https://repo1.maven.org/maven2/org/lwjgl/lwjgl/lwjgl-platform/2.9.3/lwjgl-platform-2.9.3-natives-windows.jar`
  - SHA-256: `7d4dd95bee064d91e1110918b0a7f63a5294e2fd9a48833a13d37d68c9c06d23`
  - Contents: `lwjgl.dll`, `lwjgl64.dll`, `OpenAL32.dll`, `OpenAL64.dll`

- `windows-x64/jinput-platform-2.0.5-natives-windows.jar`
  - Source: `https://repo1.maven.org/maven2/net/java/jinput/jinput-platform/2.0.5/jinput-platform-2.0.5-natives-windows.jar`
  - SHA-256: `24afbd5e1fab17da57d16a4d3f19d53f36155ef46a9976484201a4bb9722287f`
  - Contents: `jinput-dx8.dll`, `jinput-dx8_64.dll`, `jinput-raw.dll`, `jinput-raw_64.dll`, `jinput-wintab.dll`

## macOS Apple Silicon

- `macos-arm64/lwjgl-platform-2.9.4-nightly-20150209-natives-osx-arm64.jar`
  - Source: `https://files.betacraft.uk/launcher/v2/assets/libraries/lwjgl/lwjgl-platform-2.9.4-nightly-20150209-natives-osx-arm64.jar`
  - SHA-256: `47b5ed881ba2b21332f0e3d559494294be50af2337f384eb4a80361168c17e51`
  - Contents: `liblwjgl.dylib`, `openal.dylib`

The macOS phase-1 desktop app uses LWJGL keyboard/mouse input directly. No separate Apple Silicon JInput native is bundled because the phase-1 runtime does not create LWJGL controllers.
