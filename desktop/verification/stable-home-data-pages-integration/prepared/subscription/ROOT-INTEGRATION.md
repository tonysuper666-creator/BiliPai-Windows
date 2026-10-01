# Prepared complete v0.2.3 Subscription page

This is a source-only candidate against immutable actual33. The final `compile-03` compiles seven Kotlin files, including the three whole **proof copies** of existing source families. Its 38 class intersections are declared prospective overrides; it is not an actual-product runtime or UI acceptance result. No feed/article HTTP, window, account, chooser, or image-save operation ran.

The pinned original `SubscriptionFeedPage.kt` has LF SHA `c3430358427744da0b3627f73561bfbe3d6519c86b96fcc49734035db5672707` at commit `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`. The complete 13 declarations and all eight article block branches remain. Every executable edit has a reversible before/after record. Production, standalone, and prepared output bytes match. The page's data-loading/persistence statements now delegate to the existing runtime repository; the original parser, HTTP, JSON cache, limits, and reading store remain their current sole implementations.

## Installation

1. Install `prepared/desktop/tools/extract-upstream-subscription-page.py` as the sole Subscription page producer. It exposes `generate(repo, output, standalone=False)` and produces one Kotlin file, `com/android/purebilibili/feature/home/subscription/SubscriptionFeedPage.kt`. Register one generated source directory with the existing build's normal producer pattern. Do not copy its output into handwritten sources as a second renderer.
2. Install the three new manual files from `prepared/desktop/src/main/kotlin`: `DesktopSubscriptionWriteAdmission.kt`, `DesktopSubscriptionPageBindings.kt`, and `DesktopSubscriptionPredictiveBack.kt`.
3. Apply only the exact hunks in `hunks/DesktopSubscriptionRepository.json`, `hunks/DesktopPluginServices.json`, and `hunks/DesktopPluginStore.json`, with current baseline/one-occurrence checks. `proof-existing` is a preserved compilation input, **not** an installation whitelist. Do not replace those whole files or install the proof JAR.
4. Merge `registry-recipe.json` into the current registry. Its page identity is one extracted original source; all feed/settings dependencies are references to existing identities. Preserve current unrelated features/modes and sole producer ownership.
5. Rebuild the complete product before any runtime claim. The appended existing-state field changes the model constructor descriptor, so the proof overlay is not a replacement for whole-product recompilation.

## Required Root constructor and caller

`DesktopSubscriptionPageBindings` is internal and requires:

```kotlin
DesktopSubscriptionPageBindings(
    runtime: DesktopPluginRuntime,
    gallery: DesktopDynamicCardPlatform,
    stillOwned: () -> Boolean,
    commitIfCurrent: ((() -> Unit) -> Boolean),
    clipboard: (String) -> Unit,
    feedback: (String) -> Unit,
    openExternal: (String) -> Unit,
)
```

Use the retained Home's actual `DesktopHomeRetainedGate.owns` and `gate.commit`, with the same captured epoch/MID and actual SessionStore owner. The parent prepared retained gate uses Store → entry lock order. The runtime must be Root's existing `PluginRuntime`; the gallery must use the same global plugin context, actual session emotes, current Operations, current image Assets/Locations, same Home epoch owner, Root clipboard/feedback/browser, and existing text-share actor if offered by the preview. Do not construct another PluginRuntime, ReadingStore, source catalog, HTTP client, or CommentVM for this page.

Root's `DesktopTextClipboard` adapter must signal failure accurately, e.g. throw if its `copyText` returns false. `feedback` must be Shell's real feedback consumer. `openExternal` must be the existing owned URI/browser consumer. `onOpenPluginSettings` must open the current Root plugin settings backed by this same runtime/context.

The existing fixed `DesktopHomeEmbeddedPages.SubscriptionFeedPage` implementation wraps this caller, without changing its parameter signature:

```kotlin
DesktopSubscriptionPageHost(binding) {
    com.android.purebilibili.feature.home.subscription.SubscriptionFeedPage(
        contentPadding = contentPadding,
        articleContentPadding = articleContentPadding,
        scrollToTopRequestId = scrollToTopRequestId,
        listState = listState,
        gridColumns = gridColumns,
        pinchEnabled = pinchEnabled,
        pinchBounds = pinchBounds,
        onColumnsChange = onColumnsChange,
        onPinchEnd = onPinchEnd,
        onArticleOpenChanged = onArticleOpenChanged,
        onOpenPluginSettings = onOpenPluginSettings,
    )
}
```

Create/retain `binding` once with the Home owner, not on every page redraw. Hiding Home or drawing video/favorites/audio does not call `binding.close()` and does not retire the Home gate. Epoch change, restore, or application close first retires the actual gate, then closes this binding/gallery and cancels/joins jobs **outside** Store/entry monitors. Closing this binding alone is not a second linearizable account authority.

## IO admission and locks

Each binding operation captures the calling coroutine context/Job. A stateless ThreadLocal context element propagates the Root admission through original `withContext(IO)` and progress callbacks. Original plugin operations without this context execute their existing behavior. The unchanged ReadingStore/ConditionalStore still own serialization and JSON rules.

The existing AtomicFile flush/sync/stage close occurs first. Its final replace is admitted by the same Root `gate.commit`, checking captured Job and owner again after any monitor wait. Refresh additionally joins its generation/builtin-enable/JS-execution-revision predicate inside this final gate. Rejection uses the original `failWrite` cleanup; there is no new atomic-file/store implementation. Neither Root retirement nor shutdown may hold Store/entry while waiting for repository/file mutexes or native joins.

Font preference writes first hold Root admission and then enter the original plugin backing monitor. The one added check immediately before the original settings-file move is a pure current-Job/owner check; it does not acquire Root again. The exact original key is `subscription_article_font_scale`, getter default `1`, setter range `0..2`, namespace `settings`. No MID or popup binds this global key.

Busy cleanup ignores only the cancelled request's Job, while still requiring the same Root owner and request generation. The generation is rechecked inside admission, so an old finally cannot clear a replacement. Progress item updates keep the latest sole state `readKeys/fullBodies`. Reading publications copy the current sole state inside admission. The article's original longer-plain-text comparison is retained; full-body persistence runs in the **same article fetch Job**, not a detached parent-page launch.

## Platform boundaries

The original SharedTransition/SeekableTransitionState/back-progress and cancelled-gesture recovery bodies remain. The thin NavigationEvent adapter consumes actual event progress. The reused existing desktop navigation input handles Escape only while an article is open; Escape closes a flow without fabricated progress samples. The grid leaves Escape to the Root. No native Windows edge gesture or physical dialog is claimed.

The original ImagePreviewDialog, source-hidden alpha/bounds/geometry, image list/index/corner, and Root overlay are reused. `key(binding)` retires a previous owner even for identical URLs. Actual preview/save/pointer proof remains pending; this compilation does not prove Root gallery/Locations integration.

Android embedded WebView is explicitly mapped to the Root Windows external browser. Full article parsing and inline link branches remain. Android clipboard/Toast become Root clipboard plus feedback. These are declared platform substitutions, not Android window/UI equivalence claims.

## Evidence and limits

`runs/compile-01` preserves a post-compile class-allowlist failure (Kotlin compilation succeeded; the audit omitted existing `DesktopPreference*` classes). `compile-02` is a passing intermediate source generation. `compile-03` is the final passing source generation after article-only Escape and operation-cancellation corrections. None of these is a mounted UI test. `source-checks.json` records ten original source pins, inverse/source generation checks, 109 compiled classes, the 38 declared proof overrides, and zero new class/top-level method conflicts across actual33's 97 dependency JARs. No Main/shared source, registry, Gradle, or prior frozen lane was edited.
