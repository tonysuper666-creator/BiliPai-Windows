# Original Core synchronous media intent

This necessary source-only delta follows the full VM/Holder source installation.
Original Exo's source assignment does not start decoding before prepare; MPV's
NativeOwner.accept immediately queues Load. Actual initial position and pause
therefore must be fixed before that native enqueue, from the original call's
`seekTo` and `playWhenReady`, never current player state or settings defaults.

## Install

Copy only `prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoMediaIntent.kt`
to the matching main source directory. Apply four existing-family entries in
`exact-hunks.json`: MediaPort/UseCaseEnvironment, Invocation, NativeOwner's
acceptedMedia wrapper, then the sole state-core producer. Require each exact
anchor and target LF SHA. The prepared whole existing files are review copies,
not replacement instructions. The NativeOwner hunk is on Root's cache70 source
and preserves its eight required ctor arguments, nativeTransport/ACK, same-bound
adoption, mute and complete publication guards.

The compile-only `compile-reference-actual69/` NativeOwner comes from committed
9fbfe509 (actual69), then only the same intent hunk. It is never installed. The
Root70 variant is not validated as a whole override against the older 69 graph.
Root must compile the exact current variant in whole71 after merge.

No Gradle/task/sourceDirs/registry/resource/dependency changes are required.
The producer remains the unique existing Core source identity owner. Three
lexical wrappers inverse exactly to the prior complete generated Core. The
original adaptive choice/fallback, media preparation, then playWhenReady/seek/
prepare/applyPlaybackIntentAfterSourceChange expressions and order are intact.

`DesktopOriginalVideoMediaPort` now has required
`withPlaybackIntent(Long,Boolean,()->Unit)`. Existing production forwarding is
patched here; new/future real media implementations must provide it. Old frozen
synthetic fixture implementations require this method when compiled under the
new ABI; their original evidence is not retroactively changed. No default empty
implementation is installed.

## Concrete media view and actual subject

Construct one `DesktopOriginalVideoMediaIntentView` for each real captured initial
request, or each independent accepted recovery lease. Its four required delegates
are same-root legacy/adaptive/progressive CPU preparation and actual publication.
They preserve original URLs, all headers, authorization and sole byte-cache Bound.
The adaptive delegate receives the entire original AdaptiveDashPlaybackSource
manifest and tracks; no implicit highest/first representation selection is added.

The view uses a short synchronous ThreadLocal span with finally restore, not a
mutable latest request or coroutine authority. It only stamps startPositionSeconds
and startPaused from exact original arguments. It neither takes a Store/native
gate nor performs IO. Invocation forwarding pins one active media object for the
span; outside a request, rebuilding acceptedMedia between prepare/accept would
lose intent and can accidentally use another lease, so it is explicitly prevented.

In initial `publish(source,intent)`, at synchronous accept after the original VM
success block has assigned currentCid, capture the real original SessionState:

```kotlin
val state = playbackVm.resumePlaybackSuggestion.value
val resolved = captureDesktopOriginalResolvedMediaRequest(
    state, capturedOriginalRequest, capturedOriginalLoadRequestToken,
)
nativeOwner.publish(resolved, source, capturedNativeBaselineVersion,
    actualRequestJob, isRequestCurrent = {
        // entry/request current AND actual token/subject still matches the
        // immutable resolved request; final native publication rechecks gate
        capturedRequestStillCurrent(resolved, capturedOriginalLoadRequestToken)
    })
```

These capture/request guard callbacks must use the same actual original VM and
entry; the snippet names describe required Root variables, not alternate state.
Initial requested CID=0 is not a resolved publication. Preserve the original
request's AID/force/autoPlay/ignoreSavedProgress/audioLang/videoCodecOverride.
Use the actual request Job for initial publication and a distinct accepted lease
for ACK/replay/recovery and byte ingress. Root's whole factory supplies this
NativeOwner path; this packet supplies the concrete lexical view and canonical
session-to-resolved-request helper, not a second publisher or source algorithm.

The helper checks original load token, Request value and BVID, then positive
actual currentCid (and explicit requested CID, if present). Request referential
identity is deliberately not used: original MutableStateFlow equality can retain
the previous equal-valued request object when a later token begins a same-params
load. That is not authority retirement; the original token is the generation.

For accepted recovery, use `nativeOwner.acceptedMedia { expected -> ... }` with
an exact prepared delegate over the same accepted receipt/lease/Bound. No completed
initial Binding is retained for recovery. The existing accepted wrapper now
checks the accepted lease before entering the intent span and each action.

## Evidence

Run02 compiles six inputs against strict immutable actual69/101: new media view,
three explicit existing family sources (with a compile-only actual69 NativeOwner),
the complete changed Core, and one pure fixture. Three groups/13 CPU assertions
pass: exact paused/resume arguments; nested and failed spans restore/remove;
actual original SessionStore CID=0→resolved701 and same-token/value/explicit-CID
guards; acceptedMedia factory invoked once through original Invocation forwarding;
initial context survives real Dispatcher switch. CodeSource shows original
SessionStore from immutable Main69; the declared prepared families are explicit
overrides. No HTTP, HWND/native player, mounted VM or full Root playback ran.

Run01 compiler passed but the test failed on an overstrict request-reference
expectation. Inspection established original StateFlow equality retention; the
new helper was corrected to original value/token semantics and run01 is retained.
This is a candidate-source correction, not a Main product repair or native proof.

The complete Core inverse audit and generation receipt are source-only evidence.
Actual whole71 compilation and native initial-start/pause/resolved-subject proof
remain required before calling the full mounted MPV behavior accepted. Request73,
VM363, Holder337 and all historical source/runtime receipts remain unchanged.
