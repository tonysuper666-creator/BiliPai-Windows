# Listen writer barrier — isolated integration draft

This is an isolated, not integrated slice. No main source, shared Gradle, manifest, dependencies, user settings, real account or HWND is modified. The previously frozen music and GPU evidence is unchanged. This slice creates no player, playlist, writer actor, global path registry or setting/schema.

## Actual failure, reproduced with captured production code

`ListenAudioSession.persist()` launches a delayed `withContext(Dispatchers.IO) { store.save(snapshot) }`. The current `ListenAudioStore.save` is a synchronous JVM monitor operation. `Session.close()` synchronously writes a final snapshot, then cancels its scope. A coroutine which has already entered the IO block and is blocked acquiring the store monitor continues into the normal synchronous method when the monitor becomes available; cancellation does not abort that monitor acquisition. Monitor acquisition has no ordering guarantee that makes the final close snapshot last.

`BaselineListenWriterRace.kt` compiles the captured **unmodified actual Session/Store source**, creates the actual session, and starts a writer in that session's real scope. It verifies the IO thread is actually `BLOCKED` on the monitor, calls actual `session.close()` reentrantly while holding that monitor, restores an actual `DesktopBackupArchive`, and releases the monitor. The cancelled writer then overwrites the restored file. `evidence/baseline-race.json` records the verified bug, not a test claiming baseline success. No fake locking implementation is used.

## Smallest production change

Only `desktop/src/main/kotlin/com/bilipai/desktop/audio/ListenAudioSession.kt` is modified, plus one new focused test file. `owned-files.json` binds raw baseline/prepared SHA; `patches/ListenAudioSession.kt.patch` contains the narrow hunks.

`ListenAudioStore.closeAndSave(finalSnapshot)` shares the same monitor as `save`. It waits for an already accepted write, sets a per-facade terminal flag, and performs the final existing atomic write while retaining that monitor. Every later `save` checks the flag after acquiring the monitor. Consequently a cancelled queued IO call is rejected before touching the file, and an already accepted call has completed before `closeAndSave` returns. The terminal flag remains set even if the final disk write fails. Repeated close does not overwrite a restored/new file. Read remains usable on the retired facade.

`ListenAudioSession.close()` calls this operation with the same queue, selection, recent, favorites and owner-safe checkpoint it previously saved. Existing native ownership, scope cancellation and error display remain. Atomic file replacement and JSON schema are unchanged.

Ordinary close intentionally retires **only this store facade**. A independently constructed same-path store remains usable. This is necessary because Root's epoch `remember` may construct a new session/store before the previous session's `DisposableEffect` runs close. Existing Root is the only production owner creating/writing Listen stores; each old session, its captured store and its queued tasks are retired by this normal close path. No unobserved global path freeze is introduced.

## Exact Root hook

```kotlin
internal suspend fun ListenAudioSession.shutdownForRestore(): Unit
```

This is a member, not an extension. It runs existing `close()` and then joins the existing session scope's Job inside `NonCancellable`. The terminal store barrier already guarantees that old Listen writes cannot overwrite restored state; the await additionally proves the scope and its already running IO/other child tasks have finished before replacement or exit.

In Root's existing `beforeRestore` and registered application shutdown callback, change the existing call inside `withContext(Dispatchers.Main)`:

```kotlin
listen?.close()
// becomes
listen?.shutdownForRestore()
```

Other normal `DisposableEffect`/account-epoch close calls remain synchronous `close()` and do not freeze a new same-path session. Call `shutdownForRestore` from Root outside the session's own scope; joining that parent from one of its children would wait for itself. Keep the existing order: quiesce before archive replacement and do not rebuild/resume a new session until the expected restart/reopen. The join waits for actual existing work; it does not force a false success if a child is still executing.

## Original privacy/history behavior preserved

Original `feature/video/screen/AudioModeMusicPlayer.kt` directly invokes `PlayHistoryStore.record` on BV/CID change and `saveLastSession` every five seconds while playing; those calls and `core/store/PlayHistoryStore.kt` have no privacy-mode guard. This draft keeps existing audio recent/resume behavior, including under privacy mode, rather than replacing it with ordinary video's separate history privacy rule. `origin-evidence.json` records these original source hashes. No additional upstream extraction or manifest entry is needed.

The fixture binds the actual community facade's lazy search preferences to task-owned settings, enables privacy mode there, plays with a fake local preparation through the actual headless Session, and verifies final recent/resume bytes. It does not touch the user's privacy setting or request an account API.

## Actual isolated verification

`verify-isolated.py` snapshots compiled main Kotlin/Java/resources with Windows extended absolute paths, then independently compiles captured baseline and prepared Kotlin using existing cached compiler/Compose/serialization plugins. No shared Gradle. The baseline and patched tests run in separate headless JVMs and both explicitly exit with a verified status.

Ten distinct new JUnit methods exercise actual temporary disk and actual backup archive restore:

1. Cancelled monitor-queued IO cannot overwrite restored bytes.
2. An accepted writer drains before final close and the last snapshot wins.
3. A failed final atomic write still retires that old facade and cleans its own temporary file.
4. Repeated close preserves queue, exact CID, resume, recent and favorites.
5. Same-path newly remembered facade survives ordinary old close.
6. Closing one account file leaves another usable.
7. Actual Session.close cancels and rejects its real scope's queued writer after archive restore.
8. Normal Session.close saves the latest state despite its pending debounce.
9. Actual shutdown hook waits for a controlled uncancellable child to finish, preserving a foreign native source.
10. Original audio recent/resume behavior under privacy mode remains unchanged.

The four existing Listen store tests, four existing Listen lifecycle tests, and two existing music test methods (18 original and 18 Root integration nested cases) are copied unchanged and rerun against the patched Session. `preserved-regression-tests.json` binds their original bytes. Test report distinguishes JUnit methods from nested music checks; source/test counts are not parity progress.

Fixtures create only uniquely named temporary directories with task ownership markers. New disk-proof roots are retained in the report for review. The actual mpv wrapper is headless and unmounted; no decoder, GPU, audio-device or audible AU acceptance is claimed. No real Bili request, account credential, user settings write or production deployment occurs.
