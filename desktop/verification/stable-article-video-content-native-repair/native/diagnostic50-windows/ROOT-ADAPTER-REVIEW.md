# Read-only actual50 Windows review

This packet is diagnostic evidence and a proposed Root adapter change. It does not accept SMTC, normal-window layout, MainShell navigation, hardware input, OS media buttons or a geometry fix. No Candidate file, native source, dependency or Gradle task is changed.

Closed actual50 run03 used 97 immutable product entries and zero production overrides. Four Root sky click pairs reached the original controls. Entry fullscreen, stable fullscreen, exit and exact next task/native frame passed. The actual existing WindowsMediaSession then reported `available=false`, `error=Windows media controls failed`. PiP, queue callbacks and disposal were not reached. Root sky also observed normal-window controls extending beyond Main and a slight fullscreen-border inconsistency. Those visual observations remain failures independently of the accepted control callbacks.

## Exact cached source/bytecode

Actual skiko-awt 0.150.1 has SHA bb610ada28509ebee3a623c0469aff3665689fbec73d118055f3dbf5a37211f5. PlatformOperations line96 simply calls the window's `GraphicsDevice.setFullScreenWindow(value ? window : null)`. FullscreenAdapter lines40–41 sets `isWindowShown=true` and applies its remembered boolean during componentShown. The actual50 readiness patch addresses that prior popup-entry rollback; two clean native runs now retained initial fullscreen.

The exact bundled JDK21.0.12.1 source archive is pinned in inputs.json. Win32GraphicsDevice.setFullScreenWindow lines377–412 exits the old native exclusive window, then calls the superclass. GraphicsDevice lines304–309 reapplies saved windowedModeBounds through `Window.setBounds`. Component.reshape lines2354–2362 immediately returns when its existing Java x/y/width/height already equal the requested rectangle; it therefore skips reshapeNativePeer. WComponentPeer.setBounds lines165–196 normally calls native reshape, with the bounds arguments even for a location change. The bundled archive has no Windows C++ implementation, so the exact native statement that loses the scale is not claimed as proven.

Root's actual geometry observation is decisive evidence of a peer mismatch: before initial fullscreen, Main AWT was (100,100,900,620) and raw native bounds were (150,150)-(1350,930). After exit the same AWT rectangle remained but raw native bounds were (100,100)-(900,620). Closed run02 then recorded anchor AWT886x584 while the native Canvas and popup client were1329x876, against Main native client878x564. Raw API values, AWT values and sky screenshot units remain labeled separately.

## Minimal Root-owned candidate

Keep the existing Root WindowState/fullscreen setter as the only fullscreen authority. After an accepted fullscreen-to-floating change and after Skiko has applied the change, post a single EDT correction owned by that exact Root window/transition revision. Require the window to remain displayable/showing, the requested and actual placement to remain Floating, and the graphics device not to have that Main as its fullscreen window. A new task's original fullscreen request, disposal, monitor change or newer Root transition must reject the late correction.

The public AWT correction candidate is two actual bounds changes followed by the exact desired windowed rectangle, forcing `Component.reshape` to reach its existing peer. A one-logical-unit location change followed immediately by restoration avoids a temporary size-flow change; use a direction that remains on the same current monitor. Both calls go through the same Root's public Window.setBounds, with unchanged desired width/height. This is a candidate, not an accepted fix. Calling setBounds with the unchanged rectangle, validate or repaint alone cannot bypass the demonstrated same-bounds early return. Do not multiply the whole window or dock layout by DPI, replace Skiko/JDK, invoke private peers, dispose the media owner or add a second direct GraphicsDevice setter.

Final acceptance must compare the actual Main/native video/foreground geometry and clip after real exit, with fresh Root sky observations. Mathematical expected scaling or successful clicks cannot stand in for the observed layout. Programmatic route-disposal placement and external F11 synchronization remain separate boundaries.

## SMTC diagnostic boundary

Headless probes reference only actual50 classes: the same existing lazy WinRt API loaded combase; actual RoInitialize(1) returned0; the actual ComDelegate constructor registered its native callbacks. No COM event was injected, no Window/media actor was created, and no second implementation was produced. These probes exclude those three initialization stages but do not establish GetForWindow or publication.

The actual catch in WindowsMediaSession.kt line90 collapses all Throwables without a message to one generic string and discards the cause. A proposed one-hunk diagnostic preserves the full exception class/cause in the existing logging system and still publishes `available=false`. The next actual actor run must retain that failure rather than silently skipping it; no successful system-media capability is inferred from the headless probes.
