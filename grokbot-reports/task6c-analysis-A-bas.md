## 1. `DesktopBasOverlayPublicationTest` (4 `Fixture.await` timeouts)

**Classification: (ii) product defect.** Gating retirement of a non-current BAS installation on `canRenderOverlay` is not the v038 cadence contract. v038 only decides when a frame callback may run. `commentCount` and `basInstallation` stay published for a source the player no longer owns, including after `recoverSource` / `load` have cleared the decoded-frame flags that the gate requires. There is no test-only seam that can make that gate true after those calls.

Current tree `b94591ed7897b53617a50048c4846805c3a8001f` does not change `DanmakuOverlay.kt` after `fb4db99599670e19d369a440e8de41b92ccfcc48`. Line numbers below are that tree. The four tests are unchanged since `1b7600295b7b93d3eaea894655e7bcd0db4f7554` (2026-10-05), nine days before the cadence port.

### Evidence

Failures (rerun XML, each ~5.01s in `Fixture.await` at `DesktopBasOverlayPublicationTest.kt:92`):

| Test | Line | Reported count | Predicate that stays false |
|---|---|---|---|
| same-version recovery retires the old full-source BAS publication | 199 | 1 | `installation()==null && commentCount==0` |
| a fresh binding cannot revive a retired document owner | 245 | 1 | same |
| rejected final presentation admission cannot publish a processed BAS result | 274 | 1 | `commentCount==0` |
| blocked old BAS worker cannot publish after a different load wins | 305 | 0 | `installation()==null` (count is already 0) |

`installationCurrent()` at line 198 is already false before the first await. Currency and retirement are different steps. Currency is `basCurrent` (`DanmakuOverlay.kt:532-536`). Retirement is only `clearBasPresentation` plus `updateVodCount`.

Before the port, `7f38cc4ce016c10ae3b087b5e343703bd4bec21d` started `Timer(16) { tick() }` at `DanmakuOverlay.kt:771` and `:773`. `tick` (`:1197-1198`) returned only when `closed`. The non-current clear ran at `:1210-1212`, and the showing/decoded-frame return was later at `:1222-1226`. A hidden player with no codec still retired BAS.

`fb4db99599670e19d369a440e8de41b92ccfcc48` replaced that timer:

- `DanmakuOverlay.kt:779` wires `DesktopOriginalDanmakuFrameRequests(framePlatform, ::canRenderOverlay, ::tick)`.
- `DanmakuOverlay.kt:1300-1301` now returns at `!canRenderOverlay()` before the clear.
- The clear itself is still `1b7600295b` at `DanmakuOverlay.kt:1313-1314`.
- `canRenderOverlay` (`:844-854`) requires a showing, non-zero, non-iconified host and `state.ready && firstVideoFrameReady && videoCodec != null && error == null && !ended && !audioOnly`.
- `requestRenderFrame` (`:868`) only `parkOverlay()` when that predicate is false. `parkOverlay` (`:873-875`) drops paint frames and does not touch `basInstallation` or `commentCount`.

The built scheduler matches the generator. `desktop/tools/v038_danmaku_cadence.py:124-127` (`fb4db9959`) replaces Android `isAttachedToWindow && isShown && windowVisibility == VISIBLE` with `canRender() = visible()`. Upstream `desktop/upstream-slices/v038-danmaku-cadence/DanmakuView.kt:50-57` and `:101` use that predicate only to admit a Choreographer callback whose body is `invalidate()`. Generated `DesktopOriginalDanmakuFrameRequests.kt:21` and `:36` call `render()` (`tick`) only when `canRender()` is already true. That is paint scheduling. It does not define BAS document lifetime.

`recoverSource` (`MpvPlayer.kt:759-774`, `49204e2063834f0ce1e3058262a4b95cf949d74a`) calls `load`, which sets `firstVideoFrameReady = false` (`:670`, `b157aaf330d81b8018932d74b5856075eca88555`) and `videoCodec = null` (`:673`, `49204e2063`). The same call that makes `basCurrent` false (`ownsSourceSnapshot` at `MpvPlayer.kt:428-429`, `78a1d1a405d415d01dbbd00e69e32f5b29e496aa`) also makes the only retirement path unreachable until a later decoded frame. A failed recovery (`error`, audio-only, or no frame) never gets that frame, so the old count stays.

`updateVodCount` (`DanmakuOverlay.kt:538-540`) already adds BAS size only when `basCurrent` is true. Nothing applies that formula for a hidden player. These paths still retire immediately and their tests pass: successful publish `:592-594`, `enterLive` `:961`, `clearIdleCache` `:1169`, `clearOwnedDocument` `:1201`, `close` `:1414`. `commentCount` is a public `StateFlow` (`:100`), not a paint-only field.

The worker test’s `count=0` is the same hole. `loadSource` first calls `installDocument(DanmakuDocument())` (`:1005`). That refresh can admit an empty installation (0 items, count stays 0) before the real document bumps `basFilterTicket` (`:552`). `loadVersioned` then fails `ownsSourceSnapshot` for the captured snapshot, so standalone admission (`:589-590`) does not call `publish`. The empty object stays non-null until `tick`.

### No test-only seam

`canRenderOverlay` is a private method, and line 779 hardcodes it. `DesktopOriginalDanmakuFrameRequestsTest` injects `visible` only because it constructs the scheduler itself. `setEyeProtection` / `setViewportBrightness` (`:897-908`) can satisfy `hasPaint` only. `MpvPlayer.surface` is a real `JPanel` (`MpvPlayer.kt:383`); `isShowing` needs a realized window, and this fixture’s contract (`DesktopBasOverlayPublicationTest.kt:19-21`) is that no display is created. Frame flags are written from `load` / the private native `publishState` path. `recoverSource` and `loadVersioned` clear them in the call under test, so a precondition set earlier is gone before `await`. Reflecting `tick()` hits the same return at line 1301. Forging `PlayerState` or `isShowing` is not an existing API.

### Product patch

Not applied. One EDT helper, called from the hidden invalidation path and from a rejected BAS publish. `tick` keeps the same clear for a paintable overlay. The rest of `tick` (window, `user32`) stays behind `canRenderOverlay`.

```diff
--- a/desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt
+++ b/desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt
@@ -544,6 +544,12 @@ class DanmakuOverlay internal constructor(
         basInstallation=null;basPaintFrame=null;basInput.cancel();basRenderer.clear();mutableBasRejections.value=emptyMap()
         mutableBasItems.value=emptyList()
     }
+
+    /** Admission already failed. Do not wait for a paintable frame to drop the public count. */
+    private fun retireNonCurrentBasPresentation() {
+        if (basInstallation?.let(::basCurrent) == false) {
+            clearBasPresentation(); updateVodCount()
+        }
+    }
 
     /** Refilter raw BAS separately: changing its settings must not replay ordinary plugins or lanes. */
     private fun refreshBasDocument() {
@@ -592,6 +598,7 @@ class DanmakuOverlay internal constructor(
                 if (installed) {
                     basPaintFrame = null; basInput.cancel(); basRenderer.clear()
                     updateVodCount(); requestRenderFrame()
+                } else retireNonCurrentBasPresentation()
                 }
             }
         }
@@ -865,7 +872,10 @@ class DanmakuOverlay internal constructor(
                 observeHost(SwingUtilities.getWindowAncestor(player.surface))
                 validateWebMaskOwnership()
                 frameRequests.updateVisibilityScheduling()
-                if (!canRenderOverlay()) parkOverlay()
+                if (!canRenderOverlay()) {
+                    retireNonCurrentBasPresentation()
+                    parkOverlay()
+                }
             }
         }
     }
@@ -1310,9 +1320,7 @@ class DanmakuOverlay internal constructor(
         }
         val surface = player.surface
         basInput.attach(surface.components.filterIsInstance<java.awt.Canvas>().singleOrNull())
-        if (basInstallation?.let(::basCurrent) == false) {
-            clearBasPresentation(); updateVodCount()
-        }
+        retireNonCurrentBasPresentation()
         if (liveMode) {
             repeat(200) {
                 val pending = pendingLive.poll()
```

How the four tests reach it:

- Recovery tests (`:199`, `:245`): `recoverSource(..., positionSeconds=2.0, paused=true)` changes `PlayerState.positionSeconds`. The collector at `:806-812` calls `requestRenderFrame`. `canRenderOverlay` is false, so the new branch clears. Test 2’s `acquireBasActions` still does not refresh: `needsRefresh` requires `currentOfflineDocumentOwned()` (`:514-518`), and `alive` is already false.
- Rejected admission (`:274`): `applySettings` bumps the ticket (`blockedKeywords` is part of `hasSameBasFilterPolicy`, `DesktopBasPublicationPolicy.kt:5-8`) and the admit lambda returns false without publishing. `drain` flushes that EDT publish, and the `else` branch clears. `after==null` is already allowed at line 271.
- Blocked worker (`:305`): the second `loadVersioned` often does not change the `distinctUntilChanged` projection (`loading` stays true, position stays 0), so it may not schedule a frame. The rejected publish’s `else` branch clears the leftover installation. `drain` (`:81-83`) flushes that `invokeLater` before `await`.

### Risks

- While the overlay cannot paint, a refilter whose ticket has already revoked admission drops `commentCount` to the ordinary-document base immediately, then the successful publish restores BAS. Visible overlays still wait for `tick`, same as today. Passing refilter tests wait on `awaitInstalled`, so a transient dip still settles on the asserted count.
- A rejected publish now clears one frame earlier on a visible overlay too. `clearBasPresentation` runs on the EDT in both new call sites, same thread as `tick` and `clearIdleCache`.
- A `stillOwned` callback that flips false with no player-state change, no settings change, and no BAS publish is still noticed only from `tick`, which still requires `canRenderOverlay`. Test 2 also calls `recoverSource`, which does change state. Restoring a 16ms poll would undo `fb4db9959` (the frame platform is one-shot and keeps no periodic timer).
- `clearIdleCache`, `close`, and a successful publish still clear or recount on their existing paths. `retireNonCurrentBasPresentation` is a no-op when `basInstallation` is null or `basCurrent` is true, so a newer in-flight install is not dropped.