# Original ordinary-video owner assembly

This is a source-only installation packet. It constructs the installed complete original VideoPlaybackViewModel, original UseCase environment, the same MPV Section control and four original domain owners. It has not constructed or mounted a whole Root owner at runtime. The old Controller is still the installed ordinary authority until Root performs the complete handoff. No EXE/UI/native/HTTP acceptance is claimed here.

## Install order and whitelist

Apply the frozen byte-cache-consumer packet `b0f87d87632549bf9e5b28ae0f4447f999947253e8cf0bc8d66fde0466a07628` first. Then copy exactly FOUR new manual sources in install-whitelist.json. The Assembly source comes from prepared/cache-compatible, not the older static-range prepared/manual variant. The other three sources come from prepared/manual. This removes the old required static range-cache port and binds the actual canonical accepted-source capture directly to the same NativeOwner. Null means that actual direct source has no carrier or has retired; it is not a fake successful cache.

Apply only the THREE existing-family deltas in exact-hunks.json (five strict-one snippet replacements):

1. Sole extract-upstream-video-full-owner.py: selected original Action five methods + isWatchLaterAid output; append that one output to its existing generate; add readonly captureDesktopLoadState in its existing pause compatibility postprocessor.
2. DesktopOriginalVideoRepositoryBinding: primarySessData/primaryAccessToken read through that immutable request's existing Store/receipt admission.
3. DesktopRepository: project the existing appApiCooldownUntilMs under existing withPrimaryPlaybackAdmission -> entry -> protocol monitor. No second cooldown/cache.

prepared/existing files are REVIEW/COMPILE copies only. Never overwrite any whole producer, Binding or Repository. Their captured whole-file LF hashes differ from each snippet hash and from later cache merges. Cache adds captureMediaBytes at its separate anchor; preserve it. The readonly VM projection is composed after the existing original/pause transformations; preserve the sibling's CDN capture/worker transformations. Strict reverse checks in source-only-audit.json recover every recorded pre-delta whole-file LF hash.

Feature-union the TWO existing source identities in registry-merge.json; preserve their existing mode and SHA. There are ZERO new original identities/resources/dependencies/tasks. ActionRepository must retain/add the `stable-original-video-full-owner` feature so the existing Gradle task watches the newly selected original source. The existing full-owner task/sourceDir emits the extra ActionStatus output. No compiled JAR/class is installation payload.

## Concrete composition

Retain ONE DesktopOriginalVideoOwnerAssembly in Root's actual ordinary-media owner slot, outside conditional Video/comment/Audio/PiP Compose bodies. Supply a Root-owned child entry scope, captured epoch, actual live entry predicate, and an ENTRY-ONLY short commit callback. NativeOwner and raw Binding already take Store -> entry admission around that callback; it must never reacquire Store in the reverse direction. Assembly's original VM/domain memory commit adds the same Repository primary admission BEFORE entry. No callback here may perform IO/join/native cleanup.

Use actual same-global DesktopPluginContext and DesktopOriginalPlayerSettingsContext (`settings.pluginContext.store === context.store` is required), same Repository/Login/MPV/Publication, global subtitleAssets and `community.searchPreferences`. `DesktopOriginalVideoEntryReadbacks(repo,epoch,entryScope,entryOwns,entryCommit)` supplies status, hasPrimarySession, hasPrimaryAccessToken, primaryMid and cooldownRemainingMs. It reads actual selected playback account and original selected!=null semantics even when selected MID equals primary MID. It is not a retained completed Binding or credential cache.

Construct DesktopOriginalVideoOwnerRequestFactory with same Repository/Login/epoch/entry scope and gate, actual current PlayerPreferences getter, actual hardware capabilities, actual same-global auto1080p/directed-traffic getters, the actual Windows mobile-profile getter, global Assets/privacy, and Root's short primary-VIP-receipt event channel. Its capture(state,native) captures the ACTUAL invocation Job, original state, one authorization and native baseline. It uses existing token-refresh and visitor actors. The two required objects are then used directly:

```kotlin
captureInvocation = requestFactory::capture
status = readbacks
readHasPrimarySession = readbacks::hasPrimarySession
readHasPrimaryAccessToken = readbacks::hasPrimaryAccessToken
readPrimaryMid = readbacks::primaryMid
readOwnedCooldown = readbacks::cooldownRemainingMs
```

The required prepareRequestMedia receives `(rawRequestRepository, immutableStateAtCapture, nativeBaseline, actualRequestJob, sameNativeOwner)`. Supply the sibling's sole CachedMediaFactory/Intent path using `raw.binding.captureMediaBytes(sameAppByteCache)`. CPU origins must preserve final real headers/Cookie (including deliberately empty fields), title, remote URLs/mirrors/segments, receipt and all MPD representations. The actual native publish callback must synchronously resolve CID/token from the CURRENT original SessionState using the installed captureDesktopOriginalResolvedMediaRequest. Never stamp requestedCID=0 or infer start intent from current player state.

The native publisher recipe is:

```kotlin
val requested = checkNotNull(stateAtCapture.currentRequest)
val token = stateAtCapture.currentLoadRequestToken
// CPU origins/cache preparation retain this captured request. At real accept:
val resolved = captureDesktopOriginalResolvedMediaRequest(
    assembly.captureLoadState(), requested, token)
val authorized = raw.binding.authorized(source)
native.publish(resolved, authorized, nativeBaseline, actualRequestJob) {
    runCatching {
        captureDesktopOriginalResolvedMediaRequest(
            assembly.captureLoadState(), requested, token) == resolved
    }.getOrDefault(false)
}
```

`source` was already stamped by the SAME lexical IntentView's actual original start milliseconds/playWhenReady. Initial publication retains cancellation/request identity only until real MPV load ACK. Successful resolver completion must not cancel the independent accepted-source Job. New metadata-only invocations may have currentRequest=null and are valid; they cannot publish media. Accepted media/recovery captures use the Root accepted-source lifetime, not a fresh short resolver or old Accepted identity. Direct cache failure recovery consumes actual admitted position/pause/failure.attemptId; do not reuse initial start values.

Create the installed DesktopOriginalVideoOwnerPluginBridge exactly once via createPlugins(native,invocations), using SAME Runtime, actual inherited/onVideoLoad generation, invocations::captureCurrentRequestAdmission and same-Repo acceptedPlaybackCalls. The generation callback must be actual Runtime return/handoff identity. New real publication must await actual Runtime load outside Store/entry/native monitors before plugin capture becomes available. Adoption retains generation and must not repeat Sponsor load/onVideoEnd. Never increment a local counter or asynchronously pretend a mutation succeeded.

Supply actual existing progress/history, capability and volume/presentation effects. Root effects10 provides concrete network/analytics/crash helpers; it explicitly declares Firebase unavailable. All remaining required effects are real views: full playlist rows/cursor/mode/external source, actual Mini same Section/source info, actual download manager/task conversion, same raw Danmaku mutation protocol, existing protocol cache, actual app background flow. Empty/index0/noop/default flags are invalid implementations. There is no production Playlist/Mini adapter in this packet; unified facade work is separately owned and must close this mount boundary.

The original comment root MUST be retained with this entry (same Operations/catalog/requests/epoch) before Assembly construction. It is not the lifetime of a conditional Comment UI. Assembly constructs four domains once and uses original BGM emote/search/subject requests; no duplicate reply owner/catalog/client is created. Normal video adds comments as original subject type 1; existing root/parent/pictures/sync fields are preserved.

## Handoff and shutdown

Drain the OLD Controller from Root's external scope, outside Store/entry/native locks. A failed drain is terminal for that Controller; awaitRetiredOrdinaryProducers must return true before any new ordinary producer. Reporter/subtitle downloader must really join, native barrier must complete, and handoff must carry real raw details/selected CID/queue opaque owner+shuffle/runtime generation/mute interval. Global Assets/current native subtitle tracks remain Root-owned.

Then create Assembly, call adopt(handoff), and require a real accepted result. Transfer the actual Mini BV/CID/current Section projection and SAME queue ownership. Call loadInheritedDetails to fetch original complete metadata through the original new-VM/same-native branch. It does not fabricate Success from old flattened state and does not restart a paused source. No Controller.close/ordinary open is permitted on the adopted source.

Atomically switch ALL 37 Controller command families/state observers in controller-facade-inventory to this same ordinary authority (Favorites/Story/queue/Root SMTC/PiP/comment/download). Listen's existing separate audio actor/lease is retained. Old drained Controller state is cleared and ownsQueue rejects; keeping it behind a compatibility name is a bug. Preserve CID and milliseconds vs seconds, opaque queue owner, append/no-reload semantics, shuffle and one-shot reveal. Do not open an extra queue/player.

Close the entry gate, then closeAndJoin(timeoutMs) from Root's external shutdown scope. Cleanup attempts VM/invocations/domains/native independently and cancels the entry Job even if original cleanup fails. False/timeout/cleanup error blocks a second producer. It does not close global MPV/Assets/Window/Listen/Store/cache. Join is NonCancellable and bounded outside all locks. App freeze/restore closes ordinary owner before global native/image/diagnostic resources and Store freeze.

Primary VIP revision changes require a fresh original request with preserved full call arguments (including fallbackResumePositionMs); the old receipt is never retagged. The provided event callback must be a short Channel.trySend, consumed outside admission. Root consumer/reload/cancellation and actual TV credential replacement ownership remain product-mount gates; no asynchronous account write is hidden in this factory.

## Evidence limits

Final base narrow compile06: eight explicit source inputs, 278 classes, actual71 immutable101 runtime, declared Binding/Repository(+same-file DesktopSessionEpoch)/whole-VM readonly-projection overlays only. Final cache-compatible compile07 composes the frozen sibling candidate reference and exact combined Binding/VM; this is prospective source compilation, not actual73 acceptance.

ActionStatus proof03: 3 groups/33 assertions, actual Store/Binding/Invocation + Java Proxy actual BilibiliApi terminal (no socket), five original status fields/defaults/membership, same MID epoch change, real coroutine cancellation and original selected-account/cooldown readbacks. CodeSource and exact input/JAR/runtime byte pins are explicit. It does not construct whole Assembly or play native media. Its Action/Readback/protocol source bytes are unchanged by later Assembly-only lock clarification; final class comparison is recorded separately.

History remains: compile01 misidentified resume-suggestion flow as full loadstate; compile02 wrong FollowStateChange import; compile04 nonexistent Store method name; compile05 compiler success followed by omitted same-file DesktopSessionEpoch overlap allowance; proof01 fake fixture MediaPort signature/import errors; proof02 expected the wrong exception type for actual Store retirement. Corrected proof03 preserves Store's BiliApiException behavior. These failures are retained with their source/args/log pins. No source-count, class-count, standalone protocol test or prepared layout is whole Root/native/UI/EXE acceptance.
