# T8a: BAS danmaku publication fix (isolated branch grokbot/fix-bas-publication)

- Base: `origin/grokbot/r1-test-currency` @ `3d0deb5cf`. Not merged anywhere.
- Commits:
  - `44155b273` product fix.
  - The following commit adds the 3 scenario tests.
  - The report commit holds this file.
- Code by Grok Build. I reviewed scope: one product file `desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt` and one test file, both additions only. No existing assertion changed. No protected files. LF.

## Fix
`retireNonCurrentBasPresentation()` (`DanmakuOverlay.kt:548-553`) wraps the existing check `if (basInstallation?.let(::basCurrent) == false) { clearBasPresentation(); updateVodCount() }`. It is called:
- (a) when a BAS publish is rejected in `refreshBasDocument` (`:602`);
- (b) in `requestRenderFrame` when `!canRenderOverlay()`, before `parkOverlay()` (`:875-877`);
- (c) in `tick()` (`:1323`, same predicate as before).

Paint and frame cadence stay behind `canRenderOverlay()`.

## Upstream comparison (step 2)
- Pinned slice `desktop/upstream-slices/v038-danmaku-cadence/` (fixedUpstreamCommit `b69ce4c2`). Frame scheduling only:
  - `DanmakuView.kt:50-59`: Choreographer callback gated by `canRender() && needsFrame`.
  - `:67-86`: `requestRenderFrame`, early-out at `:73` `if (!canRender()) return`.
  - `:101`: `canRender() = isAttachedToWindow && isShown && windowVisibility == VISIBLE`.
  - `:103-107`: `enqueueFrame`.
  - `:133-135`: `updateVisibilityScheduling`.
  - `RenderFrameCadence.kt:7-21`.
- The generator `desktop/tools/v038_danmaku_cadence.py:124-128` maps `canRender()` to `visible()`. Desktop wires it as `DesktopOriginalDanmakuFrameRequests(framePlatform, ::canRenderOverlay, ::tick)` (`DanmakuOverlay.kt:786`).
- **Upstream has no BAS installation, no `basCurrent`, no public comment count, and no retirement.** The only "BAS" hit in the slice is the licence word "BASIS". `v029-bas-renderer/BasDanmakuOverlay.kt` paints a caller-supplied list.
- BAS retirement timing is therefore a **desktop-only concern**. It is not a deviation from any upstream cadence line, and the fix leaves every upstream-derived scheduling line unchanged.
- History:
  - `1b7600295` introduced BAS retirement inside `tick()`, driven by an always-running `Timer(16)` (`DanmakuOverlay.kt:701-703`, retire at `:1050-1051`).
  - `fb4db9959` replaced the timer with the v038 port. `tick()` now returns at `:1301` when it cannot render, so a hidden or headless overlay never retires stale BAS.
  - Task 6c cited `7f38cc4ce` here. That was a mistake: it is unrelated (a native upload script).

## Scenario tests (step 3, existing headless fixture, no sleeps)
| Scenario | Test | Without fix | With fix |
|---|---|---|---|
| Hidden window: retire once, stale snapshot cannot revive | `hidden overlay retires non-current BAS once and rejects a stale snapshot republish` | FAIL | pass |
| Restore display, modelled as a fresh load of the same video: publish once, extra render requests keep the installation and count | `fresh load after hidden retirement publishes once and an extra render request keeps that installation` | FAIL | pass |
| Rapid switching A→B→C: only the final document is installed, count = 1 document | `rapid source switches install only the final current BAS document` | pass (guard) | pass |

Limits of the existing doubles:
- `canRenderOverlay()==true` cannot be reached headless, so "restore display" is a reload, not a real window restore.
- The fixture serves one XML for every cid, so S3 tags items per worker through the plugin filter.

## Results (Linux JVM)
- DesktopBasOverlayPublicationTest: 16/16 (13 original + 3 new), 3 consecutive runs.
- The original 4 failures pass.
- Whole `com.bilipai.desktop.danmaku.*` package: 26 classes, 196/196.
- Other DanmakuOverlay users outside the package (5 files): 13/13.
- Without the fix: 6/16 fail (the original 4 + S1 + S2).

## Risks
- While the overlay is hidden, a refilter whose ticket has already revoked admission drops `commentCount` to the ordinary base at once; the next successful publish restores it. Visible overlays behave as before.
- A rejected publish now clears one frame earlier on a visible overlay too. Same EDT thread as `tick`.
- A `stillOwned` flip with no player-state change, settings change or publish is still noticed only by `tick` (paintable overlays only). Restoring a periodic timer would undo `fb4db9959`.
- Windows behaviour (real window hide/restore, iconify) is not verified.

## Evidence classes
- **Linux compile:** main and test compile.
- **JVM:** as listed above (Linux, headless).
- **Python:** n/a (no generator touched).
- **Unverified:** Windows cloud, real window show/hide, real device, UI.
