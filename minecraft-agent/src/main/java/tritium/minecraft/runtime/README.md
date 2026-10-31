# Inject runtime layout

The runtime is Java 8 and is loaded beneath the game's class loader. It must not
link desktop classes or compile against a bundled copy of Minecraft/Forge.

- `hook/`: game adapters (`GameAccess`, `CombatAccess`, `MovementAccess`), module
  coordinators (`CombatRuntime`, `MovementRuntime`) and the `RuntimeSession`
  entry point. The session routes callbacks, applies configuration, owns teardown
  and reports failures. Keep future `*Access` / `*Runtime` integration classes here.
- `lyric/`: HUD painting, artwork, widgets, input/layout editing, 3D lyric scenes,
  timelines, fonts, glow and GL state helpers.
- `combat/`: `AimAssistModule`, `AutoBlockModule`, `AutoClickerModule`.
- `movement/`: `SprintModule`, `StasisModule`.
- `util/`: `GameReflection` (cached MCP/SRG lookup), `CombatMath` (rotation,
  sensitivity and hit-box intersection), `TeamChecks` (Hypixel team rules), and
  `PacketDelayQueue` (ordered buffering, replay and world-change discard), and
  `PacketBypass` (replacement sends bypass all module interceptors).

The runtime root contains no Java classes. Tests mirror the production packages:
adapters/coordinators in `runtime/hook`, HUD and 3D lyrics in `runtime/lyric`.

## Module configuration

`ModuleCategory` is the shared category registry: lyric, combat, movement, player,
render, world and other. The desktop header filters cards without changing their
enabled states. Only lyric is available without Hidden; turning Hidden off also
returns the selection to lyric. Music features (including world lyrics), Edit HUD
and UnInject remain in lyric. Categories without installed modules show an empty
state; unregistered module IDs default to other.

`src/minecraft-shared/java/tritium/minecraft/protocol/CombatSettings.java` is the
single source for the 42 options, their defaults, ranges, steps and special
labels. `HudConfig.Module.combat` persists values. The desktop builds controls
from this schema. Add matching label keys to all three language catalogs.

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
S08/S12/S27 interception **after** PacketThreadUtil's game-thread handoff and before
vanilla mutation. Stasis advances at tick start/end, preserves sneak input,
passes velocity for two ticks (or until a 0.5-block fall), and releases stored
motion for one tick per pulse. Flag mode drops frozen C04 and replaces C06 with
C05, bypassing module interceptors. Teleport acknowledgements pass for two ticks.
Relative S08 coordinates are resolved before vanilla without moving the player
early, avoiding double application of the offsets. World/player replacement
never restores the previous player's stored velocity onto the new player.
Sprint compares the visible movement yaw with the effective silent yaw and
applies its backwards guard on the next player update.

All gameplay state is advanced on the game thread. The packet queue is serialized
because sends can originate on Netty. Replay bypasses this queue only. Disabling,
losing focus or closing Hidden restores owned key states and flushes pending
packets. World replacement discards the old world's queue. UnInject closes the
runtime before clearing the bootstrap callbacks and retransforms the original
classes through the remaining agents' transform chain.

## Reference scope

Algorithms and options follow the user-supplied OpenB4 Raven reference:
`xyz/daikirai/module/combat/{AimAssistModule,AutoBlockModule,AutoClickerModule}.java`
and `assets/clickgui/modules.json`, plus the rotation, target, key and queue
helpers used by these modules. Shared Raven Settings defaults are retained:
weapons are swords, closest aim point is 90%, and sensitivity yaw jitter is 3.
Other Raven modules (Friends/AntiBot, KillAura, Scaffold, AntiKnockback, Reach,
Freecam, etc.) are not installed by this port; their cross-module state is absent. Future ports should supply that state through
the coordinator rather than loading Raven classes. The ported modules' own
options are all exposed and implemented. Physical mouse state is kept separate
from synthetic key transitions, and lifecycle cleanup also covers focus loss.

Movement references are `xyz/daikirai/module/movement/{SprintModule,StasisModule}.java`,
`SprintTextEditor`, and the same settings JSON. The supplied Stasis identifies
itself as the Simple Utils 2.3.1 implementation with fixed Flag mode and cancel
on death disabled. Unreachable Flagless code is not exposed as a new option.
Sprint's interactions with WTap, Scaffold/Tower, NoSlow, InvMove and KillAura
remain inactive until those modules are ported; their absent state is equivalent
to disabled modules. The current AimAssist silent-yaw interaction is implemented.

## Verification

Run `mvn -f minecraft-agent/pom.xml test` for option/state-machine, input and
MCP/SRG transformation fixtures. To verify real locally supplied 1.8.9 class
files, put the mapped game JAR, corresponding Forge JAR and dependency JAR paths
(one absolute path per line) into a file, then run:

```sh
mvn -f minecraft-agent/pom.xml test -Dtest=CombatMappingsTest \
  -Dminecraft.mappingClasspathFile=/absolute/path/classpath.txt
```

From the repository root, the existing `AgentAttachFixtureTest` checks real
Java 8/current-JDK Attach, repeated UnInject and preservation of another agent.
It needs `JAVA8_HOME` and `-Dminecraft.testAttach=true`. Rendering checks use
`-Dtritium.testRendering=true -Dtest=MinecraftPanelRenderingTest`. Fixtures and
class-file checks do not replace a live Forge/Lunar gameplay acceptance test.
