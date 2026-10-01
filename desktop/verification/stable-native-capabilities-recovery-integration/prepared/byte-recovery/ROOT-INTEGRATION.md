# Same-bound native byte IO failure recovery

This packet is prospective Windows source only. Candidate was not edited and no
Gradle build was run. Compile/native proof uses immutable actual73 (101 entries)
with THREE declared existing source families, ONE new manual source and no new
dependency, HTTP client, persistent Store, player, cache actor or polling actor.
The existing original source registry is unchanged.

## Exact installation

1. Verify `frozen-handoff.json`, `raw-manifest.json` and every retained row. Verify
   the 3 current source families against `baseline-families.json` whole byte or
   whole normalized LF digest. These are whole-file identities. Each hunk's
   `beforeSnippetSha256` / `afterSnippetSha256` is an anchor digest only.
2. Copy only the ONE `install-contract.json.copyWhitelist` source from
   `prepared/manual` to the matching Candidate relative path:
   `desktop/src/main/kotlin/com/bilipai/desktop/player/cache/DesktopNativeByteFailure.kt`.
3. Apply the TWELVE exact, unique `exact-hunks.json` in listed order to Cache,
   NativeOwner, PluginBridge. Do not install the full `prepared/existing` files;
   these are review/narrow-compile inputs. Reversing the hunks reconstructs all
   three exact captured LF baselines (47 source checks, `source-audit.json`).
4. No original-source identity or extraction producer changes. Root runs the
   combined whole build and installed-product proof separately. Do not copy
   fixture sources, classes, Compiler outputs, owned task data or prospective
   class overrides into production.

## Typed contract and source mapping

`DesktopNativeByteReadStamp` fixes the actual lease/frame object, positive native
sourceVersion, exact native publication and captured playback receipt before IO.
`DesktopNativeByteFailure` contains only sequence/stamp/stage; safe `toString`
has no address, Cookie, exception text or stack. Each existing Lease holds ONE
AtomicReference slot, first failure per frame wins. Adoption/retirement clears
the old slot, and an event cannot grant recovery just by being constructed: exact
published object/frame identity is required. An in-flight read belonging to a
superseded publication is rejected; this packet does not claim delivery of such
a rejected old-frame failure to the new publication.

Loopback catches actual IOException during native track metadata resolution as
METADATA; `InputStream.read` catches actual IOException as BODY_READ. Both recheck
caller Job, same lease Job and exact fixed frame before and inside the SAME
admission commit (Store -> entry). No IO/callback/join is performed in that commit.
Prefetch/probe use `origin` directly and cannot publish native-failure events.
Socket errors after cancel are converted by the pre-existing origin catch's
caller/lease `ensureActive` checks. CancellationException is not IOException and
the metadata catch handles it first. Closed reader/done and retired frame are
rejected. Only those two native IO catch sites can call the reporter.

The actual original VM starts its observer after synchronous prepare/accept and
UI Success; it does NOT wait for native first frame. Original post-load plan
schedules START_PLUGIN_CHECK at 1200ms. The first statement of its existing loop
is `environment.plugins.observeInheritedPluginMute()`, before null dispatch,
empty plugins, isPlaying, loading, buffering or paused branches. Source chain
pins/slices are retained in `source-chain.json`. Root's existing sole
`DesktopOriginalVideoOwnerPluginBridge` member now calls NativeOwner's
`observeByteCacheFailure()` followed by the unchanged mute observer. No second
Flow collection/actor/poller is added. Sponsor network waits can delay subsequent
loop cycles; 1200ms is original scheduling, not a strict recovery-latency promise.

NativeOwner takes the actual accepted identity and actual current transport slot,
rechecks event version/publication/receipt under Store -> entry -> native source
snapshot admission, then reads the REAL `PlayerState.positionSeconds` and REAL
desired `paused`. `nativePaused` may be null while buffering; null never implies
autoplay. Finite nonnegative actual position is required. It calls the existing
MPV same-version recoverSource with the original REMOTE semantic source, only
nativeTransport removed. It does not manufacture a PlayerFailure/attempt and
does not require MPV to generate one for HTTP503. The prior
`recoverDirectAfterCacheError(...expectedFailureAttemptId)` and original helper
remain byte-for-byte unchanged. The old capability/event retires in MPV load;
accepted identity transfers and inherited plugin-mute ledger follows it. The
return value proves queue admission only; native proof separately waits real
loadfile ACK/codecs/software frame/native paused/readback. No cache retry loop.

Parent Root factory references this same Bridge/NativeOwner; no factory or VM
source needs a duplicate implementation. The full real Root/VM mount still needs
installed acceptance. This fixture calls the actual Bridge through the actual
Invocation ports, but is not a constructed whole VM or production MainShell.

## Focused evidence and failures retained

All runs pin actual73 manifest
4fe15a56b0a78bda5bf067e52f4e20ceb88244eee943a007db248e6f1bb9e829,
CP f71557c1952176118a9eba16479da2e244cacfb6cc419a65f43dc5501f822d31,
Kotlin 35d74192fc4906068b9dc750d2906a6e423df7ff005011e6a2b052c29971d16e.
MPV DLL and borrowed verified JS worker resource catalog/files are pinned before
and after; source/fixture/class origins declare the 3 families explicitly.

- compile-01: failed only fixture import ambiguity/suspend Job capture.
- compile-02: source compiler PASS; it lacked Compose compiler stability ABI.
- native-01: failed NoSuchFieldError `$stable` before IO tests. Runner corrected
  to use the same Compose compiler plugin; no production business change.
- native-02: real 503->Bridge->direct ACK and 13 checks passed, then a fixture
  assertion incorrectly used `==` for non-data OwnedPlaybackSourceSnapshot.
  Actual source was unchanged; fixture corrected to its real ownsSourceSnapshot API.
- native-03: PASS 3 groups / 19 assertions, actual 503 body IO (native failure
  still null) -> exact bounded event -> actual Bridge/Invocation -> same-version
  direct native ACK/frame/codecs/pause/position; old capability 410, registrations
  0, no repeated recovery/cache retry, new-load/stale-event/closed-owner rejection.
  Native stderr contains the expected original NanoHTTPD logged IOException.

Body IO has actual native proof. Metadata IOException / cancellation / prefetch
exclusion have precise source/admission proof here, not separate runtime cases.
No production MainShell/window/account, external HTTP, hardware input or OS media
button is claimed. No closed actual70/actual73-01/02 cohort was rerun or changed.
Owned task data/classes/rebuildable artifacts are excluded with hash/size/reason.
