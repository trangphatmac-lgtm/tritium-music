# Inject runtime layout

The runtime is Java 8 and is loaded beneath the game's class loader. It must not
link desktop classes or compile against a bundled copy of Minecraft/Forge.

- `hook/`: game adapters (`GameAccess`, `CombatAccess`, `MovementAccess`), module
  coordinators (`CombatRuntime`, `MovementRuntime`) and the `RuntimeSession`
  entry point. The session routes callbacks, applies configuration, owns teardown
  and reports failures. Keep future `*Access` / `*Runtime` integration classes here.
- `lyric/`: HUD painting, artwork, widgets, input/layout editing, 3D lyric scenes,
  timelines, fonts, glow and GL state helpers.
- `combat/`: AimAssist, AutoBlock, AutoClicker and the OpenB4 AntiKnockback,
  ClickAssist, HitSelect, JumpReset, Reach and WTap modules.
- `network/`: Backtrack, Blink, FakeLag and LagRange.
- `player/`: AutoJump, AutoLadder, AutoSwap, AutoTool, DelayRemove, FastMine,
  FastPlace, SafeWalk, WaterBucket and RavenBs BridgeAssist; `ScaffoldSupport` supplies SafeWalk's
  original Scaffold mode with the dependency's original default settings.
- `world/`: AntiBot and its session-owned shared bot classification state.
- `render/`: AntiDebuff, BedESP, Chams, ChestESP, Indicators, ItemESP, Nametags,
  NoCameraClip, NoHurtCam, PlayerESP, Radar, TargetHud, Tracers, Trajectories and Xray.
- `util/render/`: original themes, shape primitives, projection, item rendering,
  framebuffer, blur, outline and glow shaders, plus GL/cache state scopes.
- `movement/`: `SprintModule`, `StasisModule`.
- `util/`: `GameReflection` (cached MCP/SRG lookup), `CombatMath` (rotation,
  sensitivity and hit-box intersection), `TeamChecks` (Hypixel team rules), and
  `PacketDelayQueue` (ordered buffering, replay and world-change discard), and
  `PacketBypass` (replacement sends bypass all module interceptors).

The runtime root contains no Java classes. Tests mirror the production packages:
adapters/coordinators in `runtime/hook`, HUD and 3D lyrics in `runtime/lyric`.

## Module configuration

`ModuleCategory` is the shared category registry: lyric, combat, network, movement, player,
render, world and other. The desktop header filters cards without changing their
enabled states. Only lyric is available without Hidden; turning Hidden off also
returns the selection to lyric. Music features (including world lyrics), Edit HUD
and UnInject remain in lyric. Categories without installed modules show an empty
state; unregistered module IDs default to other.

`src/minecraft-shared/java/tritium/minecraft/protocol/CombatSettings.java` is the
single source for the 42 options, their defaults, ranges, steps and special
labels. `HudConfig.Module.combat` persists values. The desktop builds controls
from this schema. Add matching label keys to all three language catalogs.

The 35 imported OpenB4 modules use `OpenB4Settings` and generated `OpenB4Catalog`.
`HudConfig.Module.openB4` stores their options; the catalog retains all 274 original
entries (257 controls and 17 descriptions). Names, defaults, limits, increments, enumerated mode names,
prefixes/suffixes and special-value labels come directly from the supplied
`assets/clickgui/modules.json`. Original English module/option names are retained.
Slider edits use OpenB4's zero-based rounding, preserving constructor defaults
that do not lie on a grid starting at the minimum. Old saved configurations are
migrated by adding the new modules disabled. Regenerate the catalog and its
source fixture with:

```sh
python3 tools/minecraft/import_openb4_settings.py /path/to/OpenB4/src/main/resources/assets/clickgui/modules.json
```

BridgeAssist's separate `RavenBsCatalog` is generated directly from the supplied
OpenRavenbs-beta `BridgeAssist.java` constructor. It retains all 10 controls and
the Conditions description, including the original names, order, units, defaults
and increments. It shares the persisted `openB4` settings field so existing
profiles and desktop controls use one compatible format. Regenerate it with:

```sh
python3 tools/minecraft/import_ravenbs_settings.py /path/to/OpenRavenbs-beta/src/main/java/keystrokesmod/module/impl/player/BridgeAssist.java
```

AntiBot appears in World and Bridge Assist in Player. Existing profiles add both
disabled. AntiBot's shared classifier is used only at the original modules' bot,
spawn-height and respawn-ignore call sites; renderers that bypass spawn delay
continue to do so. Its player-join tracking, Hypixel/duels detection, Watchdog
notification/sound/red box and hoverable join messages belong to the session and
are cleared on world replacement or unload.

Backtrack/LagRange expose the shared Friends/Enemies names, persisted in
`HudConfig.openB4Friends` and `openB4Enemies`; Nametags uses the same registry.
TargetHUD's Edit position button opens the game HUD editor, with a player preview,
pixel offsets from screen center and a reset to (70, 40). It does not resize or
scale when dragged/scrolled. Its coordinates also have desktop numeric controls.

Radar adds a Tritium-only **Offscreen rendering** option (default off), persisted
as `openB4.offscreenRendering` without changing the imported OpenB4 catalog.
When enabled, the game only builds Radar snapshots and skips the local draw.
Both destinations use the shared Java 8 `RadarPainter`: the original panel,
border, triangle, red markers, tracer lines, squared-distance projection and
clipping are unchanged. The game sends complete `Wire.RADAR` frames containing
coordinates, appearance and exact GUI viewport dimensions through the existing
authenticated loopback connection. A single atomic latest-frame slot is drained
approximately every 16 ms, so a slow desktop cannot build up obsolete Radar frames
or block the game render thread on socket writes.

Tritium draws the Radar above every page of its main window. It uniformly fits
the game's viewport into the desktop viewport (centering the unused margins),
preserving shape and position across window resizing, GUI scale and HiDPI changes.
Minecraft remains the source of player positions, yaw and AntiBot decisions.
Local rendering retains the original HUD visibility rules. Offscreen rendering
continues while a world is loaded, including when switching focus to Tritium opens
Minecraft's pause menu, or when inventory/debug UI hides the local HUD.
Switching back, disabling Radar/Hidden, changing world and UnInject clear the
desktop image; a disconnected or stale feed also disappears. It does not capture
Minecraft screenshots or create a second native window.

`HudConfig.hidden` comes from the settings page's Hidden preference. When false,
hidden cards and key bindings are unavailable and the runtime suspends their
modules while retaining their saved enabled flags/options. Combat modules never
participate in HUD drawing, dragging, resizing or layout reset.

`HudConfig.Module.movement` persists `MovementSettings`: Sprint's backwards guard,
text visibility/rainbow, text content and pixel position; Stasis's Pulse interval
(default 60, 2–85 ticks, 2 disables pulses). Sprint's text command/editor is exposed
as a desktop text field and an action opening the dedicated translucent HUD editor.
Both this action and Edit HUD open a non-pausing GuiScreen directly, release the
mouse, and swallow game/chat input. Escape saves the layout and closes the editor;
screen replacement and UnInject also finish active drags. The screen is generated
against MCP/SRG methods at runtime and detached during cleanup; GuiChat is not hooked.
The text uses Minecraft's native font and color codes; it is draggable, without
resizing or scaling, and has its own position reset. Native rendering preserves
both the actual GL state and GlStateManager's cached state.

## Callback order and cleanup

`CombatTransformer` modifies method bodies without changing class structure:

- Frame start: click scheduling; frame end: delayed-packet expiry.
- Player pre-update: consume queued inventory clicks.
- Tick end: ordinary AimAssist rotation.
- Minecraft's tick raycast: silent target selection (render raycasts stay vanilla).
- Walking-player update: apply silent rotation, let vanilla construct movement
  packets, then restore the visible rotation. The exception path restores it too.
- Mouse events: record physical clicks and filter manual right-click blocking.
- NetworkManager send: observe sword placement and buffer packets while lagging.
- Client velocity handler, after its thread check: knockback callbacks.
- EntityPlayer blocking/use count: force the local player's block animation.

`MovementTransformer` adds input-update and player-update tails, plus incoming
packet interception **after** PacketThreadUtil's game-thread handoff and before
vanilla mutation. Handlers without that handoff are scheduled on the game thread
only while an interested network module is active; their original handler is
checked again before dispatch. Stasis advances at tick start/end, preserves sneak input,
passes velocity for two ticks (or until a 0.5-block fall), and releases stored
motion for one tick per pulse. Flag mode drops frozen C04 and replaces C06 with
C05, bypassing module interceptors. Teleport acknowledgements pass for two ticks.
Relative S08 coordinates are resolved before vanilla without moving the player
early, avoiding double application of the offsets. World/player replacement
never restores the previous player's stored velocity onto the new player.
Sprint compares the visible movement yaw with the effective silent yaw and
applies its backwards guard on the next player update.

`OpenB4Transformer` adds edge movement, jump/movement yaw, attack, player action
state, first-person held-item/equip animation, camera collision/hurt multiplier,
player model/name rendering and entity-join callbacks. The Forge attack hook
respects the cancellation branch. Method schemas are unchanged. Player render
post callbacks also run on exceptions, restoring Chams state.

Rotation priority is JumpReset > Scaffold > AutoLadder > WaterBucket > BridgeAssist > AimAssist.
The session shares last sent rotations, movement correction and model head/body
rotations; nested temporary rotations and WTap sprint changes restore in reverse
order. Stasis tick callbacks run first; its input hook precedes Scaffold,
JumpReset and SafeWalk. ClickAssist runs before ordinary mouse cancellation.
AutoClicker's synthetic events enter that same path exactly once.

Inbound order is Stasis, AntiKnockback, Backtrack, JumpReset, then effect handling.
Replay scopes prevent the owning module from delaying/scaling the same packet
again. Outbound observation/filters precede AutoBlock, then LagRange, FakeLag and
the shared AutoBlock/Blink holder queue. AutoBlock observes sends before lag
filters but does not add a separate serial delay; the shared queue releases only
when its last holder closes. Release from an earlier queue enters the later queues. Packet
queues are serialized because sends and release timers can originate off-thread.
BedESP/Xray preserve the original periodic scanning and use generation tokens to
discard stale scan results; their daemon timers and shader resources are closed
with the session.

Disabling modules or closing Hidden restores owned key/item state and releases
pending packets. World/player replacement discards old-world state. Player/input
modules restore owned keys on application focus loss; the network modules retain
OpenB4's original behavior across Alt-Tab rather than introducing a new flush
condition. UnInject closes the
runtime before clearing the bootstrap callbacks and retransforms the original
classes through the remaining agents' transform chain.

## Reference scope

Algorithms and options follow the user-supplied OpenB4 Raven reference:
`xyz/daikirai/module/combat/{AimAssistModule,AutoBlockModule,AutoClickerModule}.java`
and `assets/clickgui/modules.json`, plus the rotation, target, key and queue
helpers used by these modules. Shared Raven Settings defaults are retained:
weapons are swords, closest aim point is 90%, and sensitivity yaw jitter is 3.
The new modules reference the corresponding `combat/`, `network/`, `player/`
and `render/` OpenB4 sources, their event dispatcher/mixins, packet dispatchers,
rotation and slot managers, player/block/item helpers, Friends and Hypixel state,
and rendering/shader utilities. These are adapted to session-owned reflection
access, without bundling Minecraft, Forge, OpenB4's client entry point or mixins.
Unrequested standalone modules such as KillAura, Freecam, Tower, BedWars
and SkyWars remain disabled. SafeWalk's Scaffold dependency runs its original
default path; it does not install a separate configurable Scaffold card.

BridgeAssist uses the user-supplied OpenRavenbs-beta source, its full
`SimulatedPlayer` prediction dependency, block collision and rotation helpers.
The pre-input hook runs before vanilla's sneak speed scaling; actual right-click
and hotbar-scroll hooks retain the source's cancellation points. Pre place computes
once per tick, uses the original sensitivity quantization and applies its silent
rotation to both tick and render-frame crosshair queries and outgoing movement.
The view entity's current and previous angles are restored on return, exception
and unload. RavenBs' default random yaw factor is zero and its separate MovementFix
module is disabled; BridgeAssist does not inherit OpenB4's default yaw jitter. Physical
sneak ownership is restored when the module/session is disabled or the player
changes. Unrequested RavenBs NoSlow and BedAura use their disabled behavior: the
simulator retains vanilla's 0.2 item-use slowdown and no BedAura crosshair override.

Intentional source behavior retained:

- TargetHUD's normal target is supplied by KillAura, which is not installed by
  this request. It therefore shows no unsolicited combat target; its editor
  previews the local player and implements both Modern and Legacy appearances.
- Xray scans and draws ore ESP in this source; it does not replace terrain rendering.
- Chams `Hide players` stops its polygon-offset effect in the supplied source;
  it does not hide the entire entity model.
- Blink's non-default Auto-disable branch does not increment its counter in the
  supplied source. That behavior is retained, rather than silently fixing it.
- LagRange `Holding ladders` and standalone AutoSwap `Spoof item` retain their
  original dependency/no-op behavior. All original options remain available.
- FakeLag's default Pulse=120 means no pulse; SafeWalk Minimum pitch=19 and
  LagRange packet delay=10 are original special values, not ordinary thresholds.

Movement references remain `movement/{SprintModule,StasisModule}.java`,
`SprintTextEditor`, and the same settings JSON. Stasis identifies itself as the
Simple Utils 2.3.1 implementation with fixed Flag mode and cancel on death off.
Unreachable Flagless code is not exposed as a new option. Sprint now shares WTap
and SafeWalk/Scaffold state in addition to AimAssist's effective yaw.

## Verification

Run `mvn -f minecraft-agent/pom.xml test` for source-catalog parity, module state/packet sequences, input and
MCP/SRG transformation fixtures. To verify real locally supplied 1.8.9 class
files, put the mapped game JAR, corresponding Forge JAR and dependency JAR paths
(one absolute path per line) into a file, then run:

```sh
mvn -f minecraft-agent/pom.xml test -Dtest=CombatMappingsTest \
  -Dminecraft.mappingClasspathFile=/absolute/path/classpath.txt
```

From the repository root, the existing `AgentAttachFixtureTest` checks all new installed callbacks and real
Java 8/current-JDK Attach, repeated UnInject and preservation of another agent.
It needs `JAVA8_HOME` and `-Dminecraft.testAttach=true`. Rendering checks use
`-Dtritium.testRendering=true -Dtest=MinecraftPanelRenderingTest`. The desktop rendering fixture also checks all new category counts/options and
captures expanded SafeWalk, LagRange, BedESP and TargetHUD cards. Fixtures and
class-file checks do not replace a live Forge/Lunar gameplay and OpenGL visual
acceptance test.
