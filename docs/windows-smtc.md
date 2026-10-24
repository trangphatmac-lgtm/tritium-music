# Windows system media controls

On Windows 10/11, Tritium Music automatically registers a System Media Transport Controls (SMTC) session for its main window. Start a song to show it in the Windows media panel. No extra runtime, native DLL build or setting is needed; the existing JNA dependency calls WinRT. macOS and Linux do not start this integration.

Before creating any windows, the app sets the explicit Windows application ID `Tritium.Music`. It registers a per-user Start menu shortcut at `Programs/Tritium Music/Tritium Music.lnk` with the same ID and the display name **Tritium Music**, so Windows resolves the player independently of Java, its terminal, browser or IDE launcher. The shortcut launches the current JAR (or packaged executable); moving the portable app and launching it again refreshes the target. No administrator rights are required, and unrelated shortcuts are not overwritten. Fully exit older instances and restart the updated build to replace an already-created media session's identity.

The session publishes the song title, artists, album title, cover, playback status, duration and position. Windows can request play, pause, previous/next track and a seek. Available buttons follow the active queue, including repeat modes and queued “play next” songs. Media keys use Windows' normal active-session routing. The Windows version and control surface determine which controls are visible.

Tritium uses its own audio engine, so this follows Microsoft's [manual SMTC integration](https://learn.microsoft.com/windows/apps/develop/media-playback/system-media-transport-controls), with [GetForWindow](https://learn.microsoft.com/windows/win32/api/systemmediatransportcontrolsinterop/nf-systemmediatransportcontrolsinterop-isystemmediatransportcontrolsinterop-getforwindow) for the LWJGL desktop window.

- A dedicated MTA worker samples playback every 250 ms. Metadata changes and playback changes are published on the next sample. The timeline is refreshed every five seconds and on state/duration changes or a position discontinuity of at least one second.
- Native callbacks queue commands onto the application's main thread. Existing `CloudMusic` controls handle seeking and AutoMix pause/resume/cancellation; only the active deck is published during an overlap.
- Covers use the existing song cover URL through `RandomAccessStreamReference`. Windows fetches them independently. Missing, invalid or unavailable artwork does not prevent playback controls; each track clears the previous metadata and cover first.
- Before any song is selected, the session is disabled. Finished/unavailable playback disables transport buttons. Shutdown unregisters callbacks, disables the session and releases COM objects before destroying the main window.
- If Windows integration cannot initialize, a `[SMTC]` diagnostic is printed and the player continues normally. The HWND accessor uses reflection because LWJGL 2 does not expose a public native window handle; revisit it if the windowing library changes.

## Verification

Run the normal portable tests:

```shell
mvn test
```

On an interactive Windows desktop, run the native integration test as well:

```shell
mvn -Dtritium.testSmtc=true -Dtest=WindowsSmtcTest test
```

The native test temporarily registers its own Start menu shortcut and application ID, and verifies both the media session's source ID and the display name resolved by the Windows Apps folder. It creates a hidden test window, publishes Unicode metadata and a cover served on localhost, and reads them through Windows' global media session broker. It sends play/pause, previous/next and seek requests back through that broker, checks status/timeline updates and stale-cover removal, then verifies that closing removes the session and deletes its test shortcut. It does not play audio or control other applications' sessions.

For a manual check, play a song in Tritium, open the Windows media panel, and verify the title/cover and pause/resume. Minimize Tritium and use the media keys. Try previous/next and seeking where the Windows UI exposes them, then switch tracks during AutoMix and close the app to check that the session disappears.
