Native Folia lyric designs

Runtime: Java 21 and the existing LWJGL 2 renderer. No extra dependencies.
Settings: Lyrics > Lyrics page style. Normal preserves the existing page.
The other eleven modes retain the selected BottomBar or FoliaMini controls.

Regenerate design data (development only, Node 24+):
  node tools/folia/import-profiles.mjs /path/to/folia-major

Reference checkout: 0.7.22, 1f6d0f468f5bc81b23a8b40ad7ca96ccd4b1ecbe.
Attribution and license: ../../THIRD_PARTY_NOTICES.md (Folia sections).
The importer evaluates only pure upstream builders using a vector command
recorder. UI framework imports used by unrelated icon rendering are stripped;
optional smart-beat services are inactive during static geometry recording.
The app reads the resulting JSON and GLSL. It never runs the importer.

Validation:
  mvn test
  mvn -Dtritium.testRendering=true -Dtest=FoliaLyricsRenderingTest test
On Windows, run rendering checks in an isolated APPDATA directory to avoid
writing preferences or replacing native DLLs used by a running player.
Screenshots: target/ui-preview/lyrics-*.png.

English regressions cover word-timed short/long lines, contractions and punctuation
at 1650x972 and 720x1000, including Normal and all eleven native styles. Hanabi's
50 profiles also render entry, singing and exit samples; English previews are
saved as target/ui-preview/lyrics-*-English-*.png. LyricLayoutTest checks word
boundaries, original glyph offsets, backing spacing and layout region fitting.

Classic ports the default Folia tuning: spring 200/20, 7-second breathing,
5-second passed-word rotation, per-grapheme 20/40px double glow, short-line
profiles, chorus ripples, and shared Latent background. Timed animation is
seekable and pauses with playback. The shared bottom-caption toggle applies.
ClassicSceneTest checks timing, word preservation and narrow-window fitting.
Rendering checks cover entry/singing/exit, chorus and captions on/off; the
GPU regression also verifies real halo pixels and framebuffer restoration.
