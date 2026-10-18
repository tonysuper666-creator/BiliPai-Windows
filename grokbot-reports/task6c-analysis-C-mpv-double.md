I'll re-verify the prior analysis against the current code for those two desktop tests, starting with the notes and the commit that added `define-section` / `enable-section`.The prior notes treat both failures as product defects. I'll check commit `7cb07b2fc` and the current load path so the classification follows the double, not that write-up.Line numbers have moved since the notes. I'll read the current load, failure, and ACK path, and every test that uses this memory double.Both failures are stale-test-double failures. `7cb07b2fc` intentionally sends `define-section` / `enable-section` to real mpv after a successful `loadfile`. `PremiumRecoveryNative` still rejects every `mpv_command` except `sub-add`, so the memory actor throws on that success path. One test-only change to that double covers both classes. The prior note called these product defects; that does not match the current code.

HEAD is `56ff5a459`. The `define-section` lines are still the `7cb07b2fc` text (only the line numbers moved).

## What the commands are for

`7cb07b2fc` ("Windows: relay native child input through owned MPV events…") adds mpv's own input-section / client-message path, not an OS hook.

- `MpvNativeWindowInput.kt:11-18` builds one opaque binding per load: `MBTN_LEFT` → `reveal`, `MOUSE_MOVE` → `motion`, `SPACE` → `toggle`, each `nonrepeatable script-message <token> <action>`.
- `MpvPlayer.kt:2001-2002` turns `input-vo-keyboard` and `input-cursor` on when `softwareTarget == null`, so the child window can produce those events.
- `MpvPlayer.kt:2391-2400`, after `loadfile` returns (`2384`) and `playlist/0/id` parses, and only when `softwareTarget == null`: `define-section bilipai-window <bindings> force`, then `enable-section bilipai-window`, then store the lease, then `onLoadCommandAccepted()`.
- `MpvPlayer.kt:2886-2893` handles event 16 (`MPV_EVENT_CLIENT_MESSAGE`) and emits reveal / motion / toggle for that lease.

The memory double always reports `playlist/0/id = "11"` (`DesktopPremiumAudioRecoveryTest.kt:82`), and both fixtures use `MpvPlayer()` so `softwareTarget` is null. Every drained `Load` takes this branch.

## Error-handling note (not the classification)

A real negative return is a separate coupling, and it is not what these tests hit.

`checkResult` (`MpvPlayer.kt:3206-3207`) throws `MpvCallException` when the code is `< 0`. That call sits before `onLoadCommandAccepted()` (`2400`). `perform`'s catch (`2612`, `2674-2675`, policy from `49204e2063`) turns every `Action.Load` exception into `publishFailure` (`3022-3026`). The file is already loaded in mpv, the ACK hook does not run, and `DesktopOriginalVideoInitialPublication.consumed` stays false (`DesktopOriginalVideoNativeOwner.kt:171`). A recovery port that already set `failureConsumed` (`511`) then fails `checkCurrent` (`448-452`) because `failure != null`.

The double does not return a negative code. `check()` at `DesktopPremiumAudioRecoveryTest.kt:109` throws `IllegalStateException` before any return. A libmpv that returns 0 never enters `publishFailure`. `START_FILE` / `FILE_LOADED` (`2934-2949`) also clear `failure`, which is why other `drain(); loaded()` users of this double do not see the intermediate `UNKNOWN` failure. Softening that catch would be a different product policy and would hide a stub that rejects a command the player means to succeed. No product patch.

## 1. `DesktopOriginalNativeRecoveryAdmissionTest` — (i)

Failing test: `sameActualAttemptPublishesOneAacReplacementPreservingSourceControlsAndReceipt`.

Evidence:

- Intended commands: `7cb07b2fc` at `MpvPlayer.kt:2396-2400`.
- Stale double: `8eb7cd2b6` still owns `DesktopPremiumAudioRecoveryTest.kt:109` (`check(first == "sub-add")`). The test's last behavioral commit is `75522724c` (2026-10-14), the day before `7cb07b2fc`.
- Symptom chain: `accept` sets `failureConsumed` (`DesktopOriginalVideoNativeOwner.kt:511`) and `recoverSource` → `load` clears `failure` synchronously (`MpvPlayer.kt:672`, `759-775`). `drain()` at `DesktopOriginalNativeRecoveryAdmissionTest.kt:103` records `loadfile` (`2384`), then `mpv_command` throws, so line 104 (`loads.last()` AAC url) still passes. `perform` catches the throw and `publishFailure`s. This test does not call `loaded()`, so the failure stays. Line 107 `prepareLegacyDash` hits `checkCurrent` (`DesktopOriginalVideoNativeOwner.kt:452`, `472-473`) and throws `CancellationException: Accepted ordinary source/presenter retired`.

If the double returned 0, `failure` would stay null and line 107 would proceed to the existing CDN / retirement assertions.

## 2. `DesktopWindowsVideoStatisticsLeaseRegressionTest` — (i)

Failing test: `acknowledgedSourceCopiesExactBvOnEdtOutsideActualAdmissionAfterRequestJobRetires`.

Evidence:

- Same intended commands and same stale `mpv_command` as above. This test's only commit is `104fcdc68` (2026-10-11), four days before `7cb07b2fc`.
- Fixture init `drain(); loaded()` (`DesktopWindowsVideoStatisticsLeaseRegressionTest.kt:139-142`) still passes: `loadfile` was recorded, and `FILE_LOADED` sets `loading = false`. `isCurrent` uses `owns()` (`DesktopOriginalVideoNativeOwner.kt:204-207`), which does not read `consumed`.
- `publish` installs `DesktopOriginalVideoInitialPublication` (`358-360`). `current()` is `ownsAccepted() && (consumed || (!requestJob.isCancelled && isRequestCurrent()))` (`157-158`). The hook that sets `consumed` (`171`) is `onLoadCommandAccepted` at `MpvPlayer.kt:2400`, which does not run. Line 209 cancels `initialJob`. Line 210 `admit {}` returns false (`160-168`).

If the double returned 0, the ACK would set `consumed` during that init `drain()`, and cancelling the job would not revoke the source. The later clipboard assertions are unchanged.

## Shared test edit

File: `desktop/src/test/kotlin/com/bilipai/desktop/player/DesktopPremiumAudioRecoveryTest.kt` only. No assertion in either failing test is edited. No `Thread.sleep`. No product API or visibility change. `seek` and every other non-`sub-add` command still fail the existing `check`.

No new assertion on the recorded list. `lease.bindings` contains a per-load UUID (`MpvNativeWindowInputLease.kt:16-18`), so a literal equality would be unstable, and the two tests already assert the behavior that matters (AAC url, single post-cancel admit).

```diff
--- a/desktop/src/test/kotlin/com/bilipai/desktop/player/DesktopPremiumAudioRecoveryTest.kt
+++ b/desktop/src/test/kotlin/com/bilipai/desktop/player/DesktopPremiumAudioRecoveryTest.kt
@@ -77,6 +77,7 @@ private fun event(id: Int, data: Pointer?) = Memory(24).use { event ->
 }
 internal class PremiumRecoveryNative : MpvNative, AutoCloseable {
     val loads = mutableListOf<List<Any>>()
+    val commands = mutableListOf<List<String>>()
     val properties = mutableListOf<Pair<String, String>>()
     private val buffers = mutableListOf<Memory>()
@@ -106,7 +107,15 @@ internal class PremiumRecoveryNative : MpvNative, AutoCloseable {
     override fun mpv_command_node(handle: Pointer, args: Pointer, result: Pointer?): Int {
         val command = node(args) as List<Any>; check(command.first() == "loadfile"); loads.add(command); return 0
     }
-    override fun mpv_command(handle: Pointer, args: StringArray): Int { check(args.getStringArray(0).first() == "sub-add"); return 0 }
+    override fun mpv_command(handle: Pointer, args: StringArray): Int {
+        val argv = args.getStringArray(0).toList()
+        val name = argv.first()
+        if (name == "define-section" || name == "enable-section") {
+            commands.add(argv)
+            return 0
+        }
+        check(name == "sub-add")
+        return 0
+    }
     override fun mpv_error_string(error: Int) = if(error == -14) "audio output initialization failed" else "fixture terminal error"
```

## Other `PremiumRecoveryNative` users

Returning 0 only removes the intermediate `UNKNOWN` `publishFailure` and lets `onLoadCommandAccepted` run. Existing assertion results stay:

- `loadfile` is still recorded at `MpvPlayer.kt:2384` before these commands, so `loads.size` / `loads.last()` checks stay.
- Anything other than `define-section`, `enable-section`, and `sub-add` still throws. `MpvPlaybackIntentSeekAbaAdmissionTest.kt:65-67` still must not drain seeks.
- `sub-add` is still `check` + `return 0`, and it is not added to `commands`.
- Callers that `drain(); loaded()` or `fail(-14)` already observe a cleared failure, because `START_FILE` / `FILE_LOADED` clear it. `applyDesktopPremiumAudioRecovery`'s second rejection (`DesktopPremiumAudioRecovery.kt:74`) is `attemptId` mismatch after `load()` set `failure = null` (`MpvPlayer.kt:672`); it does not need the spurious failure.
- The default `onLoadCommandAccepted` is empty (`DesktopPlaybackPublication.kt:28`). `consumed` matters only when the request job is cancelled. The only such assertion is the statistics test above. Other `initialJob.cancel()` sites are teardown.
- These two commands do not write `properties`.

Overrides do not use this method, so this edit does not change them:

- `NvidiaWithdrawalNative` (`PlayerNvidiaActorWithdrawalTest.kt:166`) still allows only `vf add` / `vf remove`.
- `SoftwareScreenshotNative` (`PlayerSoftwareScreenshotDiagnosticTest.kt:81`) still allows only `screenshot-to-file`. Its actor does not `perform` non-screenshot queue items.
- `PresentationNodeNative` (`MpvVeyraPresentationTest.kt:15`) delegates `mpv_command` but never calls it.

The copy under `desktop/verification/.../DesktopPremiumAudioRecoveryTest.kt` is not the class these tests compile.
