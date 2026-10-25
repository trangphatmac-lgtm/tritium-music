# Minecraft HUD

Settings → **Minecraft / 一键注入** opens the Minecraft page immediately. A single
candidate is selected automatically; multiple clients are listed by name and PID.
The four HUD controls, 3D lyrics, Edit HUD toggle and UnInject action appear after an actual rendering
callback acknowledges configuration. System HUD windows are temporarily suppressed;
their saved settings and layout are unchanged.

Cards always use Static material and the current accent color, regardless of the
application material. Expand a card to configure it. Click NONE to bind a key;
Escape cancels, Delete/Backspace clears. Bindings operate only in a focused world
without a screen open. A shared key toggles all matching HUDs, with UnInject taking
precedence. The UnInject module is an action and has no persisted enabled state.

Enable the **编辑HUD / Edit HUD** module on the desktop, then open Minecraft's existing chat screen. Drag a
HUD, use its bottom-right handle to resize, or scroll over it to scale. Closing chat
saves the current drag but keeps the module enabled for the next chat opening.
Switch the module off or use its bound key to end editing. The key follows the same
focus/screen and press-edge rules as other bindings, and can disable an enabled
editor after chat closes. UnInject takes priority when sharing its key; the edit
key takes priority over HUD toggles. Its switch reflects the current session and is not
saved as a HUD preference. Disabled HUDs and the toast are previewed during editing.
No custom Minecraft screen, menu or configuration GUI is created.

## World-space 3D lyrics

Expand **3D 歌词 / 3D lyrics** on the Minecraft page and enable its switch. This
module starts disabled and has its own game key binding. Its lyrics follow the
song playing in Tritium and Tritium's existing lyric-source selection; the Agent
does not log into a music service or fetch a second lyric stream. YRC and other
word-timed sources retain their absolute word timestamps. Line-only lyrics use
Gale's estimated word/character reveal timing. The separate screen-centered
Gale Text flash mode and Gale's independent Spotify/NetEase player selectors are
outside this world-space module.

Each phrase appears at a world position in front of the view, with randomized
horizontal spread and a slight upward bias. It faces the player by default.
Characters pop in over 160 ms, with optional low-frame-rate hand-drawn wiggle;
old phrases fade and drift upward over 400 ms. Pause fades the phrase and resume
reintroduces it. Lyrics render through blocks, matching Gale's disabled depth
test. They do not create entities, collide with blocks, or illuminate the world.
The glow uses a separate text mask, seven downsample levels and additive
upsampling before crisp text is drawn. Smooth font uses cached system-font
glyphs; disabling it uses the game's font renderer.

| Setting | Default | Range / behavior |
| --- | --- | --- |
| Lyric offset | 0 ms | −2000…2000 ms; positive values show lyrics earlier |
| Scale | 1 | 0.5…3 |
| Distance | 10 blocks | 3…40; world text size also scales with this value |
| Glow | On | Additional settings appear while enabled |
| Glow color | Text color | Text, pink, blue or gold tint |
| Glow strength / radius | 0.3 / 0.5 | 0…3 / 0…1 |
| Smooth font | On | System font glyphs, or Minecraft's font when off |
| Text color | `#F7E1D7` | RGB picker |
| Hand-drawn wiggle | On | Additional settings appear while enabled |
| Wiggle frame rate / amount | 8 / 0.8 | 4…24 FPS / 0…2 |
| Word pop | On | Uses word timing when available; otherwise estimated |
| Face player | On | Rotates the text plane toward the view |
| Keep away | On | Pushes text out to at least 60% of the configured distance |
| Stay on screen | Off | Smoothly moves out-of-view text back inside the view |
| Horizontal spread | 30° | 0…60° to either side |
| Split lyric phrases | On | Splits eligible CJK spacing into separate timed phrases |

The world module is excluded from chat HUD drag/resize/scroll and Reset layout;
its scale and spatial options are configured on the desktop. Preferences persist
independently of the 2D lyric HUD. Old preference files acquire the module with
its default disabled state. Synthesized "No lyrics" UI messages remain available
to the 2D HUD but carry an explicit placeholder flag so they are not sung in the
world. Instrumental placeholders and lyric credits are also filtered there;
timed blank/break rows clear the current world phrase.

The behavior reference is the supplied `GaleMod-2.0.jar`, SHA-256
`330bda34f8600d400fcefca73e62e8374aceacf6eb334861719cf1157d535b80`.
REA inventory evidence `ev_151d2694be959ca9f0ae300bd3112f4fdc62f44e9701bd66d3b64a2a510f8eba`
identifies the package; it is static inventory, not a runtime observation.
The implementation was checked against the extracted `gale.lyrics.LyricsModule`,
`LyricSplit`, `Lyrics` and `gale.render.CjkFont`/`DeepGlow` bytecode and CFR output.
`WorldLyricTimelineTest` and `WorldLyricSceneTest` verify the timing and geometry;
`WorldLyricsRenderingTest` executes the font and glow pipeline in a real OpenGL
context, including depth-test bypass, framebuffer resizing and restoration of
game GL state. Its generated images are synthetic world-space previews, not
screenshots of Minecraft. Native-font cache restoration has an independent test.
Live Forge/Lunar, shader-pack and third-person acceptance remains a separate
in-game check. If an older Tritium Agent was already loaded into the JVM, restart
Minecraft once before using the updated world callback bridge.

## Runtime requirements

- Windows x64; desktop JDK 21 including `jdk.attach` and its JDK-provided native
  dependency. This project adds no custom native loader or helper injector JVM.
- Minecraft 1.8.9 with named MCP (Lunar) or SRG (Forge) classes and LWJGL 2.
- Both processes must run as the same Windows user with compatible permissions.
- The target must permit Attach and dynamically loaded agents. Remove
  `-XX:+DisableAttachMechanism` and restart the game. On JDK 9+ an explicitly
  disabled dynamic-agent setting requires `-XX:+EnableDynamicAgentLoading`.
  Do not pass that latter option to Java 8. Launchers that enforce disabled
  Attach are incompatible with this design; there is no bypass.

See [Oracle's JVM option documentation](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html).
Some Java 8 targets return bare `0` after successful loading, which newer Attach
clients parse as `AgentLoadException: 0`. This exact case is tolerated only after
verifying the target's Java specification version is 1.8. It never substitutes for
the authenticated session and actual renderer READY.

## Build and architecture

`mvn clean verify` builds the independent `minecraft-agent/pom.xml` first and embeds
its shaded JAR at `tritium/minecraft/minecraft-hud-agent.jar` in the desktop artifact.
Shared Java 8 DTOs/framing are in `src/minecraft-shared/java`. The Agent compiles to
Java 8; ASM/Gson are shaded. The bridge contains a JDK `IntPredicate` reference
and the world callback's primitive partial-tick value
and is appended to the bootstrap search path. Runtime classes live in an isolated
loader whose parent is the game loader; no desktop/audio dependencies enter it.

The desktop JVM performs Attach on a worker and always detaches the Attach
transport afterward. Each session creates an IPv4 loopback listener with a random
256-bit token and protocol version. Track/lyrics/art/config are sent on changes;
playback and spectrum are sampled at 30 Hz, without queued obsolete snapshots.
FFT frames are published as independent finite snapshots. Both IPC endpoints
sanitize non-finite playback values before JSON serialization or HUD rendering;
an invalid audio sample becomes zero without dropping the connection. Runtime
sync failures do not display Attach-permission troubleshooting instructions.
The game renderer follows the supplied Opai widget geometry: 230x56 music card,
48px cover, 5px progress bar, expanding 26px download panel, SF/PingFang bold
glyph metrics, per-line lyric easing and cubic SlideIn word reveal. The cover's
41-tap Gaussian blur is prepared on the IPC worker. Spectrum preserves the full
band array, selects its first half with step 3, uses the original 16x10 gain and
200ms peak hold, and does not normalize each frame against its loudest band.
Absolute/relative volume spectra are computed from the same FFT independently of
the system HUD's setting. Compact/curve display and simultaneous translation and
romanization remain available. A headless before/after gallery is generated by
`HudReferenceGalleryTest`; it is a composition preview, not a live-game screenshot.
The toast uses the system HUD's 240x48 minimum, 14px SF/PingFang bold font,
32px note icon and 52px text inset at half-sized game GUI units (120x24).
It expands for long titles within the screen and ellipsizes when space runs out.
Legacy untouched 240x32 toast dimensions migrate once; custom dimensions, position,
scale and opacity are preserved. `MinecraftToastAppearanceTest` compares desktop
and Agent text/background pixels at GUI scale 2, including Chinese and ellipsis.
The game extrapolates position with a monotonic clock. Artwork decoding and IPC
run in workers; Java2D HUD composition is local to the game and cached textures
are uploaded/drawn/deleted only on its render thread. The frame hook is on
`EntityRenderer.updateCameraAndRender(FJ)V`, outside Lunar's cached HUD path.
Input hooks use `Minecraft.runTick()V` and `GuiChat.handleMouseInput()V` (the latter
must cancel before the chat override calls its superclass).
The 3D callback is inserted in `EntityRenderer.renderWorldPass(IFJ)V`, immediately
before the profiler switches to the hand section, while the world matrices are
still active. The equivalent SRG method is `func_175068_a`. This boundary remains
outside conditional hand drawing. Missing or ambiguous hook anchors fail
installation rather than guessing an injection point. New track/lyric JSON
replaces the Agent's immutable timeline input, including same-song lyric-source
and word-timing changes; metadata is sampled approximately every 300 ms.

On Windows, discovery merges the Attach list with a read-only WMI query through
the player's existing JNA dependency. `ProcessHandle.Info.arguments()` can be
unavailable and `-XX:-UsePerfData` hides a JVM from the Attach list, while direct
Attach by PID may still work. WMI supplies the launcher command line for candidate
recognition and disabled-Attach checks; it does not inject code. Command lines and
launcher credentials are never shown in diagnostics or the UI. There is no shell
or helper JVM for this fallback.

Heartbeats run once a second; five seconds without inbound activity disconnects.
Session generation guards reject stale UI callbacks. UnInject first stops input
and rendering, finishes layout edits and releases textures on the render thread.
It removes its transformer and retransforms affected classes through the remaining
transform chain. It never restores a saved whole-class snapshot over other mods.
When no render frame can run, cleanup remains pending and the UI reports it rather
than claiming complete removal. Agent/bridge class unloading is not guaranteed;
this is distinct from `VirtualMachine.detach()`.
See [Instrumentation retransformation semantics](https://docs.oracle.com/en/java/javase/25/docs/api/java.instrument/java/lang/instrument/Instrumentation.html).

## Automated checks

```
mvn test
mvn -Dminecraft.testAttach=true -Dtest=AgentAttachFixtureTest test
mvn -Dtritium.testRendering=true -Dtest=MinecraftPanelRenderingTest,GameGlStateRenderingTest,WorldLyricsRenderingTest test
```

Set `JAVA8_HOME` for the Attach fixture test and run it with Java 21 on Windows.
Also run with `-Djvm="<JDK 25>/bin/java.exe"` to exercise the Java 25 Attach
client against Java 8. Windows CI covers both clients. Java 8's successful
load response can appear as `AgentLoadException: 0` on Java 21 or
`AgentLoadException: Failed to load agent library: 0` on Java 25. Only these
exact responses from a verified Java 8 target are tolerated; authenticated
render READY is still required. Error 102 remains a startup failure.
The small Agent entry records a bounded cause chain before IPC starts, keyed
to the current session. The desktop reads it over Attach so class-loading and
initialization errors appear directly instead of generic permission advice.
It creates a **test fixture JVM, not Minecraft**, verifies real cross-version
Attach, disabled Attach/dynamic loading, failed agent loads, repeated install/remove,
no premature READY, and preservation of a second agent's transformation.
The Windows fixture also runs with perf data disabled and verifies discovery even
when the Attach process list omits the target. This reproduces the Forge detection
failure observed on the development machine.
Rendering tests use a real local OpenGL context; UI screenshots are under `target/ui-preview`.
Windows CI also builds a jpackage runtime and checks `jdk.attach` and `attach.dll`.

## Live acceptance record

On 2026-10-25 the supplied Lunar 1.8.9 v2.23.8-2643 process used Zulu 17.0.18 and
was launched with `-XX:+DisableAttachMechanism`. This configuration cannot be
injected by standard Attach. **Successful live Lunar and Forge rendering acceptance
is still required**; automated fixtures and compilation do not prove it.

The Forge 1.8.9 process on Zulu 8 was initially missing from both the Attach list
and Java's command-line information. The Windows metadata fallback now identifies
that running process. The user subsequently reported that the updated version
appeared to work. Detailed per-feature acceptance below remains unverified;
interactive validation was interrupted by the user's physical Escape key.

For each attach-enabled Forge and Lunar 1.8.9 runtime, verify: inject/READY,
all four HUDs with a real song, translations/romanization/word timing, pause/seek,
F1, chat and inventory key suppression, chat-only drag/resize/scroll, GUI scale and
fullscreen changes, button/key UnInject, reconnection, game exit and player exit.
Permissions-denied behavior and launcher-specific class-loader differences also
require the corresponding real environment. Do not mark these checked from fixture
results alone.
