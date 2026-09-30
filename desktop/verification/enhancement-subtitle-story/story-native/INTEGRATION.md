# Story native Canvas input bridge — frozen isolated slice

Only the three files listed in `contract.json` are proposed production/test changes. No shared main, Gradle, dependencies, manifest, Shell, Controller or Host files were edited. Existing initial-failure retry work remains intact. `StoryTopicScreens.kt` baseline raw SHA is `5dfba965d83bbdf2f8235464c13a442b13d6995cef969a7ab32bfbfd8285260f`; the new bridge and JUnit test have no existing target. Validate those boundaries before copying `prepared/`.

## Root hook

`DesktopStoryScreen` adds optional `nativeInput: DesktopStoryNativeInputBinding? = null` immediately before its final `playerContent` parameter. Existing trailing lambdas remain valid.

```kotlin
class DesktopStoryNativeInputBinding(
    val surface: java.awt.Component,
    val sourceVersion: () -> Long,
    val owns: (DesktopStoryOwner) -> Boolean,
    val onPlayerKey: (PlayerKeyAction, expectedSourceVersion: Long) -> Boolean,
)
```

Root should share its existing `when(PlayerKeyAction)` executor with this callback; it must not instantiate another player. Keep the current Shell keyboard conditions for UI actions. Capture the changing handler with `rememberUpdatedState` so the remembered binding never retains an old account, preference, route or native state. Immediately before an action, recheck the current Story route, `!showVideo`, `!activatingUpdate`, held owner/epoch and `player.currentSourceVersion == expectedSourceVersion`. Return `false` when unsupported/stale. The Screen additionally verifies controller epoch, Host ownership and exact source version before forwarding an action.

An integration shape, with `executeExistingPlayerKeyAction` denoting the same existing Shell action body (not a new independent implementation):

```kotlin
val latestStoryKeyHandler by rememberUpdatedState<(PlayerKeyAction, Long) -> Boolean>({ action, version ->
    val initialized = player
    val owner = storyHost.owner.value
    if (section != DesktopSection.STORY || showVideo || activatingUpdate || initialized == null ||
        owner == null || !storyHost.owns(owner) || initialized.currentSourceVersion != version ||
        playback.state.value.details == null) false
    else executeExistingPlayerKeyAction(action)
})
val storyNativeInput = remember(player, storyHost) {
    player?.let { initialized ->
        DesktopStoryNativeInputBinding(initialized.surface, { initialized.currentSourceVersion },
            storyHost::owns, { action, version -> latestStoryKeyHandler(action, version) })
    }
}
DesktopStoryScreen(/* existing parameters */,
    isActive = section == DesktopSection.STORY && !showVideo && !activatingUpdate,
    nativeInput = storyNativeInput,
    playerContent = /* existing owned native player slot */)
```

The actual Shell owns action extraction and integration; no Shell patch has been applied here. The isolated fixture uses only its real existing MpvPlayer's guarded PlayPause action. UP/Down and PageUp/PageDown go through the same `pager.animateScrollToPage`/`resolveCommittedPage` and settled-page queue requests already in Story; they do not call playback.next/previous or construct an alternate queue.

## Transport and lifecycle boundary

AWT global-in-this-JVM mouse observation and KeyEventDispatcher are installed only by the composed Story effect; constructor of the binding allocates neither a player, native hook, thread, coroutine nor window. Active-window, visible native surface and Host/account ownership are required. Native primary-button press arms the exact token; release checks the identical owner/epoch/native version. Dragging commits one adjacent page only on release, after the height-relative threshold; it cannot load intermediate media. A source/account change invalidates the gesture. Future keys may use a new native version belonging to the same legitimately held route.

Clicking the native video clears the old Compose editor focus and requests the clicked native component's AWT focus. Outside clicks and Tab relinquish keyboard intent. Ctrl/Alt/Meta pass through, shifted key behavior uses the original keyboard policy, repeated handled presses are suppressed until release, and consumed shortcut characters cannot leak into an earlier editor. Dispose unregisters both AWT listeners synchronously. No wheel listener is added: real baseline proves SwingPanel already forwards the wheel into the Compose pager; adding a second listener would double navigation.

`PlayerKeyboardPolicy.kt` is already direct registered; `PortraitPagerSwitchPolicy.kt` is already an adapter reference and its existing extracted `resolveCommittedPage` is unchanged. `source-inventory.json` adds zero sources/dependencies. The AWT->Compose event adapter uses the public pinned Compose 1.12.1 `Key(code, location)` and `KeyEvent(...)` builder, preserving original policy modifiers/location/native event. The builder needs the library's `InternalComposeUiApi` opt-in; there is no reflection/internal converter access. Recompile these tests when upgrading Compose.

## Actual evidence and reproduction

`evidence.json` binds the frozen file SHA, two execution identities, immutable `verified-runtime-main.jar`, raw original baseline copies, the native DLL, synthetic media, reports and screenshots. Both final native JVMs exited normally with code 0. No shared Gradle and no real-account/Bili HTTP were run. Main bytecode is a fixed copied snapshot; it is not rebuilt in this slice.

Commands from the repository root (the two `--run` commands intentionally create only a task-owned window and use Robot):

```powershell
python desktop/.local/story-native-input-parity/compile-tests.py
python desktop/.local/story-native-input-parity/compile-proof.py --run
python desktop/.local/story-native-input-parity/compile-proof.py --bridged --run
```

18 actual JUnit methods pass in a headless isolated JVM. The fixture compiles the unchanged real Controller and Host plus the old/new real Story screen, uses fake metadata/stream data and a 120-second generated local AVI, then displays the genuine native libmpv Canvas through the original PlayerPanel/SwingPanel. Before native pointer input the final enhanced proof verifies its own PID/root HWND/foreground, `WindowFromPoint == Native.getComponentPointer(actualCanvas)`, and `SunAwtCanvas`/`MpvPlayer$canvas$1`; pixels are sampled from that real visible native surface. This is distinct from the earlier offscreen Compose pointer fixture.

Baseline: wheel down/up work. Vertical native mouse drag, Space, arrows and PageUp/PageDown do not. Bridge: all nine measured input actions work, each page step submits exactly one settled playback selection, no selection occurs while a drag is held, and Space changes only pause. Further actual checks cover a real Compose editor receiving Space without player mutation; native re-click restoring keyboard ownership without typing into the old editor; epoch 7->8 during held native drag preserving new page 0 and successful real retry after synthetic stream failure; direct foreign native takeover rejecting old Story Space/Down. Initial synthetic metadata failure also recovers via an actual clicked Retry button and first native frame. Final report holds 14 meaningful native checks plus pixel/gesture timing evidence.

## Explicit remaining gaps

This slice is mouse release-to-adjacent-page navigation and focused desktop keyboard support. It does not implement touch/pointer continuous-follow dragging, velocity fling, pinch zoom, multi-touch, double-tap zones, long-press temporary speed, original tap overlay visibility or full Android Story visual overlays. Upstream `PortraitVideoPager.kt:1523` blocks pager scrolling for zoom/comment/UP preview; `:2403` onwards owns single/double/long-press and temporary playback speed. Windows currently has no equivalent complete overlay/zoom state, and this bridge does not fabricate it. Those are distinct subsequent interaction/visual work. Keyboard actions supported by Root remain exactly its existing action subset, not every original shortcut merely because the resolver supports it. Actual GPU/native input proof is not full Android touch or visual parity.
