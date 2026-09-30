# Final DynamicCard Main review

Reviewed snapshot manifest `b47f4d80fb598ec0d5de05ee41a5bead82de773331e0b50c8d41f237a35a9c01`; Kotlin jar `1bd2dfb959c1f64c7684e576150db938773848df6836848da214f27f6dac7334`. Independently verified all three jar SHA/entry counts, 14 relevant current source pins against its receipt and three generated effective-theme alias pins. This is read-only source/byte-identity review, not another UI/test/native run. Earlier REVIEW and frozen producer evidence remain unchanged.

## Earlier issues now resolved

- **Owner-level likes:** DesktopDynamicCardSession owns original-schema ephemeral `Map<String,Boolean>` StateFlow. Host reads `session.likeOverrides.value[id]` after acquiring the shared original gate and resolves the request through original `resolveDynamicLikeState`. Successful response confirms the owner override; incoming item no longer clears it. Old sibling callbacks and refresh cannot reset the request direction to a duplicate up=1. UI supplies that same owner override to original DynamicCardPresentation.
- **Raw model and cache coverage:** epoch-scoped Shell provider covers Community, independent Space and Topic. Registry updates existing raw models through original reducers, including Topic's actual controller. Only currentAll persists after a confirmed mutation. Every all fetch callback independently verifies currentAll, so a retained revision's delayed fetch cannot write its old rows as disk latest. Presentation filtering is not persisted as raw rows.
- **Late detail:** original DynamicDetailData/Item field versions preserve only confirmed matching-ID like count/status, forward count, fold/visibility or deletion during primary→opus readback, retaining fresh content and untouched server fields. Timeline append/failure/cancel retain current items; UP mirror replacement does not add counts twice.
- **Credential/source ownership:** Shell BrowseMemory is keyed by MID and sessionEpoch. Shared session checks exact repository/epoch and closing predicate; individual card actors, network Operations, UriHandler and assets are independently retired. Registry checks SessionStore's actual owner monitor before mutation and its lifetime before local markNotInterested.
- **Selected theme:** Root LocalDesktopDarkTheme receives resolved palette.dark. Generated original DynamicCard, ActionButton and NativeTabs imports alias their existing `isSystemInDarkTheme` calls to the Root getter. Algorithms, styles and public parameters remain original. This verifies these three exact consumers, not every unrelated shared renderer's dark-mode branch or a native HWND visual result.

## One remaining concrete blocker in these exact reviewed bytes

`DesktopOriginalDynamicCardHost.kt:128` acquires the **session-shared** like gate before `scope.launch`. Card disposal cancels this **card-owned** child scope (`:94`). A DEFAULT coroutine cancelled before its body starts never reaches its body `finally` (`:138`), so that ID remains acquired in the still-live shared session. Sibling cards and a returning card cannot acquire it again until the entire session retires.

This is a Windows task-lifetime difference from using a whole ViewModel lifetime for both task and gate, not an argument to replace the original gate. Minimal adapter fix: launch first; inside the child coroutine check owned and acquire; after successful acquisition immediately enter try/finally, including requestLiked resolution inside try. A cancelled-before-start actor then acquires nothing. Alternatively use one completion-owned release, not both an unguarded finally release and later completion release.

Root must patch and compile/freeze new bytes; the snapshot above must remain a truthful pre-fix receipt. No fix was made by this review. A narrow deterministic queued-dispatcher regression is sufficient: queue a card child, cancel it before starting, then allow a still-live sibling to acquire the same original gate. No broad matrix or actual account/window request is required.

## Scope boundaries retained

Normal replacement refresh still takes incoming server rows under original merge policy. A deleted ID can return through an already-outstanding stale append because it is no longer in existing IDs. These are inherited merge/concurrency boundaries noted in the earlier REVIEW, not falsely covered by the new detail-only readback claim. Full editor/Detail/comments, native LivePhoto/HWND, MotionPhoto synthesis, system sharing and whole-application parity remain outside this completed card-source integration slice.
