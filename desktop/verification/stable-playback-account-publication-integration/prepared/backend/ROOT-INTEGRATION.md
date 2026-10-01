# Playback-account authorization candidate

Prepared against stable target `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589` and actual39 immutable 97-entry runtime. `compile-04` and `fixture-03` are prospective platform-family proofs, not installed product acceptance. No account files outside this lane were read, no socket or native/UI action occurred.

## Exact installation

Copy the one source under `prepared/desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopPlaybackAuthorization.kt`. Apply `local-hunks.json` sequentially to its five named EXISTING platform files after checking each full LF base SHA. Do not install the proof-only whole-file copies. They preserve the historical compile inputs. `audit.py` inverses every hunk and verifies original full-file equality. Add the one manual source to the source-only installation cohort; no new original API/DTO FQN or client/cache/store is produced. Keep all existing source identity modes; this is an added selected private CookieJar body in a platform source, not a duplicate ApiClient interface producer.

The existing encrypted session file gains only `@SerialName("playback_mid") playbackAccountMid: Long? = null`. Missing key is the original main-account fallback. Other schema/DPAPI/atomic-file semantics remain existing. Logout preserves the selected account; removal clears selection only if its MID is removed. Selecting a valid saved SESSDATA never activates that MID or changes primary generation. Same selected MID credential/token/VIP changes increment an independent same-Store revision. Name/avatar/last-used metadata do not. Every successful selection setter invalidates the revision even for the same selection, matching original `clearPlaybackAccountClient`.

## Profile required ports

Use the retained Profile nav-entry's captured primary epoch, `isCurrent` and the actual coroutine Job for every suspended getter/setter. Root account data remains primary.

```kotlin
repository.storedAccountSessions(): List<StoredAccountSession>
repository.activeAccountMid(): Long?
repository.getPlaybackAccountMid(): Long?
repository.getPlaybackAccount(): StoredAccountSession?
repository.setPlaybackAccountMid(mid: Long?, expectedEpoch: Long, stillOwned: () -> Boolean): Boolean
```

The last callback must combine the SAME Profile page gate with the captured caller Job. It must not choose the current epoch at execution. Existing activate/remove/login operations remain the existing owner-bound Root implementations. `removeSavedAccount` reaches the patched same Store `removeAccount`; direct root removal must still perform the original primary reset as required by that existing API. No new account catalog or account StateFlow is introduced.

## Transport, cache and final source publication

The original `PlaybackAccountCookieJar` full body is selected with declaration name/visibility adaptation only. Its mutable response-cookie state lives only in the existing Store and is used through a request tag/TLS delegation by the SAME Repository OkHttp client. It never saves selected response cookies into primary persisted cookies. The original NetworkModule same-active-MID branch uses main cookies. Missing selection uses main. A denied selected request does not switch to the main account. Video WBI/main nav signer remains the existing primary owner; only playback authorization APIs use selected cookies. The existing PGC/PUGV/legacy order, full raw payload decoder, quality policies and fields remain intact. Original API/model classes are loaded from actual39.

`DesktopPlaybackCache.Key` gains `authorizationRevision`; no new cache actor. Returned platform `PlaybackSource.authorizationReceipt` is nonsecret `(accountEpoch, revision)`. Actual HTTP request creation/execution, CookieJar admission, post-response read, catch/fallback and cache admission reject a retired receipt. Caller Job cancellation rethrows cancellation and never tries legacy/PUGV/main fallback. The complete original VideoRepository app/TV quality logic is not newly claimed by this account-only slice: its optional access-token selectors and additional branches remain their existing separate source closure.

**Root must bind the final admission before calling native/download/export consumers. Backend HTTP validation alone is insufficient.**

```kotlin
val requestJob = currentCoroutineContext()[Job]
val owned = { requestJob?.isActive != false && rootEntry.isCurrent() /* immutable route request */ }
repository.withPlaybackSourceAdmission(resolvedSource, owned) {
    // Atomic URI/command publication only. Do not wait/join/close native sessions here.
    player.loadVersioned(resolvedSource.toNative(...))
}
```

Use the same gate for `recoverSource`, queued download creation, cast publication and audio-from-video load. Preserve receipts through `copy`/plugin rewrite/codec-CDN fallback. Never reconstruct a resolved source with a new or missing receipt. Check the original request generation/sourceVersion as well as this authorization in the same publication callback. The guard is Store-first; do not acquire Store while holding native/controller/entry shutdown locks. Reject the old gate first, release monitors, then cancel/join/close. `isPlaybackSourceCurrent(source)` is a pure read for existing recovery eligibility; it does not replace the atomic final publication gate. Already-running authorized media is not automatically stopped by the selection setter; new publications/recovery require a current receipt. User selection UI does not retire the Home planner or primary account epoch.

Current exact consumer anchors in the sibling platform sources (not edited by this lane):

- `DesktopPlaybackController.kt`: `load` publishes through `loadVersioned`; `refreshSource`, failure recovery and `switchForWatchdog` use `recoverSource`; premium-audio recovery also transfers a retained resolved source. All must preserve/check the receipt. Synthetic injected `DesktopPlaybackDataSource` tests need a separately explicit local-source admission contract rather than bypassing real Repository authorization.
- `ui/MediaScreens.kt`: Bangumi `playEpisode` awaits `bangumiPlaybackInfo` then publishes `info.source`; live paths use a different primary/live authorization and are outside this playback-account family. Gate only the actual PGC/PUGV source publication.
- `audio/DesktopAudioRepository.kt`: video-audio `prepare` currently reconstructs a native source from resolved media. Carry the resolved authorization as metadata to `ListenAudioSession.playAt` and gate `loadVersioned`; AU/audio-song primary authorization remains its original scope.
- `DesktopShell.kt`: video download and cast helpers must admit the resolved source before queue/URL publication. The download queue must capture the corresponding current authorization rather than asking the current account at execution. Do not stop other owners' native sources.

These Root consumer hunks are required assembly, not executed or claimed by the present transport fixture.

## Proof scope

`fixture-03`: 4 groups / 47 assertions. Actual candidate same encrypted Store, actual DPAPI synthetic file restoration, selected response-cookie isolation, actual Retrofit API/WBI params with a task-only terminating APPLICATION interceptor, original PGC/PUGV order, single existing cache, queued tag retirement, request-only cancellation and a final publication counter guard. The terminating interceptor explicitly calls the actual Store CookieJar under the actual interceptor TLS; it does not claim OkHttp's real network Bridge path. All nine loaded class CodeSources and bytes are checked. Five existing platform families are prospective proof overrides; original `StoredAccountSession`, `BilibiliApi`, `BangumiApi` are actual39. No Main runtime, real HTTP, native URL playback, Profile UI click or EXE acceptance is claimed.

Historical failures stay intact: compile-01 lacked an explicit Job import; fixture-01 found the proof compiler omitted the Compose plugin required for existing `$stable` ABI; fixture-02 incorrectly expected legacy PGC path instead of original `/pgc/player/web/v2/playurl`. Compile-04 and fixture-03 retain their exact accepted bytes.
