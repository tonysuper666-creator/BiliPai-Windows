# Actual69 request composition and retained-pause delta

This is the next source-only packet after VM363/Holder337. Those packages and
actual69 remain immutable. It does not construct a whole video owner or retire
the currently mounted Controller. No new source identity, Store, client, native
player, cache, account projection or dependency is registered.

## Install order and exact whitelist

1. Install the already frozen captured-metadata Binding16 first. Its manifest is
   `73b2cb6a872a561c14f67eb5d72288d8e26e6f07ec8a64abdabd5da4165639f2`.
   Four Binding hunks plus the Repository readonly visitor query are its own
   payload; this packet neither copies nor supersedes that package.
2. Copy only `prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerRequestRepository.kt`
   to `desktop/src/main/kotlin/com/bilipai/desktop/ui/`.
3. Apply `exact-hunks.json` in order: Binding's primary scalar reads and short
   admission; global Assets overload. Require the stated LF base SHA and each
   unique anchor, then verify desired SHA. These full prepared files are review
   references, not a full-file replacement instruction.
4. Apply the two producer hunks in `retained-pause-producer-hunk.json`. Existing
   original spans, 137 inverse substitutions, recipes and output receipts are
   still checked before the new explicit Windows post-replay delta. Only the
   original skip-player-prepare Success branch gains `shouldAutoPlay &&` before
   `p.play()`. No cache, quality, seek, recovery or other autoplay rule changes.
   Root must compile the resulting sole VM output in the next whole build.

No generated candidate, compiled class/JAR, test source, fixture temp data or
copied whole existing-family file is installed. Registry change is empty; the
existing original identities and sole producer remain authoritative.

## Concrete request factory

Create this in the existing `DesktopOriginalVideoPlaybackInvocationPorts.capture`
callback, inside the actual launched request Job, after capturing the real Binding:

```kotlin
val requestRepository = createDesktopOriginalVideoOwnerRequestRepository(
    repository = sameRootRepository,
    binding = capturedBinding,
    subtitleAssets = sameRootGlobalSubtitleAssets,
    privacy = sameCommunity.searchPreferences,
    visitorInitialized = { receipt, owned ->
        sameRootRepository.ownedHomeVisitorInitialized(receipt.accountEpoch, owned)
    },
    onPrimaryVipReceiptRetired = capturedVipRetirementEvent::enqueue,
)
DesktopOriginalVideoPlaybackInvocation(
    requestRepository,
    capturedInitialMedia,
    capturedBinding::assertCurrent,
)
```

`capturedInitialMedia` is the required real NativeOwner publication/media view,
with the actual request Job, request identity and future accepted lease. It is
not supplied by this packet. The same captured byte-cache Bound must prepare
native transport and prefetch; it must never be reconstructed from latest source.

The page's existing `DesktopOriginalVideoOwnerRepositoryView(invocations)` and new
`DesktopOriginalVideoOwnerNotesView(invocations)` forward to that exact operation.
There is no completed Binding retained as a page-wide protocol authority.
The factory reuses original metadata9, creator-card, BGM/WBI, Notes and the raw
load protocol. Original heartbeat fields come from the existing
`reportDesktopPlaybackHeartbeat` function, using the captured primary API, primary
MID and CSRF, original privacy policy and original HistoryRefreshBus notification.
It does not call the old epoch-only Community reporter or build another Retrofit.

Runtime's new-request mutation callback must capture before waiting:

```kotlin
val capturedAdmission = invocations.captureCurrentRequestAdmission()
// same Runtime Mutex waits outside all Store/entry/native admission
// capturedAdmission { shortMemoryMutation() } after the wait
```

The callback retains this request's Binding and Job. Completed/cancelled request,
entry/epoch/auth-revision retirement return false without running the action.
No IO, coroutine wait, join, cancellation, native cleanup or lock-order inversion
is permitted in the short action. Missing invocation is an explicit wiring error.

## Three actual VIP callers and fresh-capture boundary

The installed original VM has three write contexts, recorded exactly in
`vip-caller-inventory.json`:

- Initial `loadVideo` preferred-quality resolution calls metadata refresh.
- `preloadRelatedPlayUrls` calls the same refresh while prefetching another item.
- `refreshDeferredPlaybackSignals` writes `environment.account.updatePrimaryVip`
  after an original successful nav check. Use new `DesktopOriginalVideoOwnerAccountView`
  here so its write forwards through the same captured request. Its three scalar
  readers are required real Root Store→entry reads; no global fake account is made.

The existing Store includes VIP in playback authorization identity. A changed
VIP can retire the receipt without changing MID or epoch. Both metadata and
deferred account paths now enqueue the exact OLD receipt only after the Store
write returns. Root's required callback must be a short Channel/event enqueue,
never a synchronous load, native call, Job cancel or join while that callback runs.

Root must capture immutable intent before the write/request: BVID, AID, CID,
force/autoPlay, ignoreSavedProgress, audioLang, codec override and explicit
fallbackResumePositionMs. Its consumer rejects an obsolete request/entry/event,
then starts a new original `loadVideo` capture. Do not silently retag a Binding.
The original PlaybackRequest does not retain fallbackResumePositionMs, so this
value cannot be guessed from a newly current source. Do not call `reloadVideo()`
to preserve pause: that original method deliberately uses autoPlay=true.

Related-prefetch retirement must not open the recommended subject. End that
prefetch. If the same revision also retires the accepted current source, Root
must separately capture/reload its already accepted SAME subject and intent.
`retry()` captures actual position/play intent/audioLang for its current subject;
it is not a substitute for a not-yet-accepted pending subject's explicit intent.
The Channel consumer and precise source/request intent wiring remain Root work;
this packet proves event publication and rejection, not successful fresh playback.

## Assets and pause scope

`DesktopSubtitleAssets.import(track, capturedCalls)` keeps the actual application
owned URL-digest file/document/cues cache, trusted URL/headers, 8 MiB cap and
cancellable HTTP body handling. Public import delegates to the original client.
The new request method checks coroutine and Binding before/after import, and
CancellationException remains cancellation. Global Assets is closed only by Root
after native player teardown. It is not closed when this request/VM retires.
Local cache publication retains original global file lifetime; it is not a new
account-state final-write admission. If that separate policy is required, a later
short commit must avoid the existing files-monitor→Store reverse-lock hazard.

An actual native adoption preserves MPV state, but a new VM has no complete
Success model. Seed only a genuinely retained complete original Success or use
the original metadata/load path. Do not fabricate a zero-field Success from the
handoff's raw ViewInfo/cachedDash. The narrow pause delta honors explicit false
only when the original same-player/same-subject branch skips native preparation.
No native/pause acceptance is claimed by this source packet.

## Evidence and remaining owner work

Actual69 fixed 101 CP plus three explicit candidate inputs compiled; final run04
also compiles one pure terminal fixture. Only Binding and Assets are declared
existing-family overrides. Original Invocation, Notes, metadata, raw Core, VM and
Holder come from immutable actual69. Five groups/15 assertions pass: captured
Notes with real Dispatcher switch, original primary heartbeat, fixed admission
after request completion/epoch change, preferred-quality and deferred VIP
revision retirement, one supplied Call followed by global Assets cache reuse.
No socket, native HWND, account request, whole owner or mounted page runs.

Run01 is the previous adapter-only compiler receipt. Run02 failed solely because
the fixture imported two PlaybackSource types and omitted OkHttp Call tag methods.
Run03 passed before adding the deferred-account forwarding path. Run04 is final;
none of those historical files is rewritten. `retained-pause-replay-proof.json`
is source generation only. Root whole compiler/native pause proof is pending.

The existing Domain26 factory is separate and required. Lift one original common
CommentRoot request owner into the retained entry, and give its exact four VMs to
Holder. Do not let the old comment host construct another Comments VM. Domain26
manifest `5c571ec3907e6037707b5430b4c92042b920a828f21affd8f28774df2ed70c12`.

The real whole factory still needs the actual four domain instances, original
use-case/progress/settings, initial and accepted native media, Runtime adapter,
bound byte-cache ingress, real queue/Mini/Audio/history/external playlist ports,
same window/background/foreground and callback owners. Existing source-only
required ports are not empty implementations. All old Controller clients must
switch state and commands atomically to the same new facade after successful
drain/adoption; refer to the already frozen transition inventory32 rather than
adding a second Controller. No old Controller retirement occurs in this packet.
