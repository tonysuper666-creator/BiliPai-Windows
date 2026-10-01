# Native media byte-cache source packet

Only `install-contract.json` payloads and sequential `exact-hunks.json` snippets are installable. `baseline/`, `review-only/`, `runs/`, fixture sources, classes and synthetic media are evidence, never product replacements.

## Production closure

Four manual sources under `com.bilipai.desktop.player.cache` implement one Root cache actor, one persistent interval index, one native-only carrier/typed header schema and one concrete same-Repository admission. A single producer emits three complete original String/pure helpers from fixed `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589` PlaybackMediaCache: maximum 128MiB, HTTP eligibility and explicit-key-first/query-free cache key. The original Media3 SimpleCache/CacheWriter platform is mapped to the Windows actor; it is not an Android SDK shim. No additional client, Store, dependency, player or Cast singleton.

The registry recipe merges the existing PlaybackMediaCache identity, keeps `stable-video-player-full-controls`, adds `stable-native-media-byte-cache`, changes reference-only to policy-extract. Original CdnOptimizationPolicy remains sole existing DIRECT. Generated helper bodies are byte equal to original selections; producer verifies the stable Git blob as well as LF SHA.

## Required real Root bindings

Construct once on Root IO, with the actual application scope and actual Repository:

```kotlin
DesktopMediaByteCache(actualCacheDirectory.resolve("playback_media_cache"), applicationScope, repository)
```

The actor observes the same `sessionEpochFlow` and `playbackAuthorizationRevision`. An event checks actual Store admission rather than trusting an intermediate combined epoch/revision pair. Old capabilities are retired and real calls cancelled. Source replacement/stop/close mark retirement synchronously; cancellation and file cleanup run outside Store/entry/native locks.

Capture the original resolver authorization, real operation Job, real entry gate and all authorized tracks. Use `repository.capturePlaybackCachePartition(receipt, stillOwned)` from the exact Store/Repository hunks: it hashes the effective selected playback credentials within the existing Store, not active-MID dynamic-cache identity or process-local counters. No extra credential serialization. Cache files contain opaque digests, interval metadata and bytes only.

```kotlin
val admission = DesktopMediaByteRepositoryAdmission(
    repository, capturedAuthorization, capturedNamespace, actualOperationJob,
    actualStillOwned, actualEntryGate::commit,
)
val bound = soleByteCache.bind(admission, capturedTracks)
```

`actualEntryGate::commit` has the existing `((()->Unit)->Boolean)` form. `assertCurrent` and `commit` take Store then the real entry gate, whose predicate may read the actual native snapshot afterwards. Never call native then Store. No network, file operation, cancellation teardown or join belongs in an admission callback.

`capturedPlaybackMediaHeaders(source)` retains explicit empty User-Agent/Referer/Cookie. `capturedDesktopMediaByteTrack(url, mirrors, explicitOriginalKey, originalRepresentationIdentity, headers)` uses the exact original cache key policy. Representation/key/header sets must be captured once from the original complete DASH/progressive metadata; aliases only group the same authorized original track. Do not invent a mutable latest-source map or account. Unsupported transport headers/internal FORCE_COOKIE_HEADER are rejected, never silently reinterpreted.

## Actual VM / Portrait consumers required

The same `bound` must serve the original `DesktopOriginalCdnRangeCache.prefetchRange` and `prepareNativeTransport`. A second bound calculated from latest values would not prove warmed spans were consumed.

```kotlin
override suspend fun prefetchRange(url:String,key:String,position:Long,length:Long,headers:Map<String,String>) =
    capturedBound.prefetchRange(url,key,position,length,headers)
```

This method returns only after the full actual requested interval is committed and still covered. Errors/cancellation propagate; the original CdnPrefetcher may not mark `cachedSegments` on failure. The core fills only interval holes and bounds disk plus staging/header reservations to 128MiB. LRU excludes leased readers, and startup verifies complete persistent span length/header before indexing. Strong ETag/Last-Modified plus length identify persistent content; resources lacking a validator do not survive restart.

Original CdnPrefetcher's independent internal scope cannot implicitly inherit Invocation ThreadContextElement. Parent's sole Invocation/VM/Portrait producer must explicitly capture bound plus real request/source generation and CallerJob at seed/start. This packet intentionally does not overwrite that family. Portrait uses `prefetchHeadRange(..., upperLimit=1536*1024, ...)` / audio `256*1024`: head limits are cut to actual resource length. SIDX exact ranges use `prefetchRange`, never silently truncate and call success. WiFi/preferences remain original caller policy.

The complete adaptive MPD goes to `bound.prepareNativeTransport(remoteSource, fullOriginalMpd)`. All BaseURLs are rewritten, with every representation/adaptation/range/attribute preserved, and no Cast two-track selector. Legacy separate video/audio uses both local routes. Progressive uses all original segments and durations in the native EDL. The remote source fields are untouched:

```kotlin
val nativeSource = remoteSource.copy(nativeTransport = capturedBound.prepareNativeTransport(remoteSource, fullMpd))
```

All XML and source preparation happens outside Store/entry/native admission. Native `immutableSnapshot` validates semantic fingerprint. Carrier is the SAME immutable object through copy/snapshot/drain/adopt, so complete native-source equality remains stable; only its private admission Frame changes. A real new load requires a fresh unattached carrier. Recovery with changed URLs/headers/receipt requires a fresh carrier; same-source recovery/adoption rebinds the existing carrier.

## Native accepted-source lifetime

NativeOwner constructor gains a REQUIRED `(accepted,stillOwned)->DesktopMediaByteAdmission` factory. It must produce actual same-Repository admission with the accepted source receipt/namespace, actual entry gate and a Root accepted-source lifetime Job. This Job survives resolver normal completion and old Controller producer drain; it must not be a request Job or already retired Controller scope. Initial byte reads use the SAME InitialPublication transient request/generation checks until the real native Load ACK. No second ACK/producer.

Attach happens in the same Store→entry transaction as native version publication. Adoption changes the exact version+publication Frame after the real MPV barrier/publication transfer, preserving native clock, tracks and token. Old owner close passes its exact publication, so it cannot retire a read lease already adopted by a new owner. Failed rebind clears accepted identity and stops only that actual source version; it does not report success. Root must transfer before retiring the old source admission/Job; external IO never keeps old request credentials after transfer. Root joins producer/IO retirement outside admission.

Mpv delta is only three lifecycle snippets plus fresh-carrier validation. It does not overwrite Native67 short dispatch/barrier/mute algorithms. Stop/replacement/close retire exact current version/publication and defer IO cancellation. A separate one-line Controller drain comparison excludes the new native-only field when comparing the resolved original remote source; the earlier complete native snapshot equality continues checking the same carrier object.

The same Repository final network interceptor applies `DesktopMediaOriginHeaders` after CookieJar/guest strips. It preserves explicit empty fields, removes sensitive fields on unapproved redirects and forces identity transfer encoding. Native loopback options clear origin Cookie/UA/Referer/audio extras, so no credentials enter local capability URLs or the local server. Existing PlayerDiagnostics already redacts HTTP URL paths; carrier/track/admission logging contains no URL/token/credentials.

## Compatibility and acceptance limits

The cache requires honored 206 identity ranges and exact Content-Range/validator/byte counts. 200/invalid/truncated/changed-resource responses cannot report successful prefetch. Preserve original direct upstream recovery when cache is not applicable or has a real IO failure (`FLAG_IGNORE_CACHE_ON_ERROR` equivalent); do not make every recovery recreate the same failing cached ingress. Remote source remains available for this path. This packet does not claim that Root's direct fallback/Invocation/Portrait consumers are already mounted.

`proof-09`: actual66/101 immutable product + five new prospective sources, no existing class overrides, 5 groups/34 assertions. It uses actual in-memory SessionStore and actual same Repository; actual libmpv CPU renderer consumes warmed bytes, same native publication adoption and old Job cancellation pass; persisted spans, gap-only prefetch, cancellation cleanup, bounded LRU, clear and actual revision rejection pass. Empty final headers are tested at the typed final-header seam; actual66 Repository has not installed that seam yet. Namespace input is fixture-owned; new Store getter awaits actual product integration proof.

`mpd-03`: separate full-manifest native cohort, actual66/101, all three original synthetic MPD representations/range tables preserved, actual same MPV video+audio decoding with no additional origin body range, completion/retirement pass. The synthetic MPD uses standards-complete SegmentList; SegmentBase retention is also tested structurally. Neither cohort overrides production PlaybackSource to pretend its carrier field is installed: it explicitly projects the prepared native URI into the unchanged actual66 MPV input. Root must run actual installed carrier/final-header/namespace/consumer acceptance after whole compilation. No real account, external HTTP, Windows UI, hardware input or Root mount acceptance is claimed.

Expected NanoHTTPD socket-close warnings occur when actual MPV stops/retires a stream. The XXE rejection emits the JDK parser diagnostic; raw failures are retained. They are not hidden as successful network responses.
