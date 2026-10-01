# Full original Article leaf: prepared source-only handoff

Target remains stable v0.2.3 commit `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`. The full 505-line ArticleDetailScreen, full ArticleDetailSkeleton, nine-field ArticleDetailUiModel, original conversion/URL resolution/view-to-Opus richer-content merge, and original policies are retained. There is no replacement basic Article renderer.

Install only the two copies in install-whitelist.json, then the exact four-hunk protocol-producer.patch to the existing sole producer. Never copy the whole prepared protocol producer over a changed live version. Audit04 verifies its current LF baseline and every unique hunk. Re-generate the existing dynamic-detail-protocol output, retaining its normal strict verifier gates. Existing dynamic detail/fallback and DesktopDynamicDetailOperations.fragment.kt are byte-identical; the old Pair ABI is a projection from the canonical model. There is no Ops hunk or new HTTP/store/model authority.

Union registry-delta.json against the then-current registry, retaining every other entry/mode. Precisely **five new identities: four DIRECT policies and one selected Screen**, plus two existing feature unions (ArticleRepository and ContentLoadingSkeletons). Production Article UI producer emits only Screen and Skeleton (two selected outputs). Sync alone copies the four direct policies. Existing ArticleContentBlockParser/ArticleContentLoadPolicy/ArticleSharedTransitionPolicy are referenced, not produced again. New nine-field model is generated only by the existing protocol producer. Add the task/sourceDir/dependency in gradle-snippet.kts; there is no new library, icon or string resource dependency. Existing original skeleton pulse/block helpers are reused.

## Exact mounted leaf contract

`ArticleDetailScreen(articleId: Long, initialTitle: String, transitionEnabled: Boolean = false, onBack: (Boolean) -> Unit, onUserClick: (Long) -> Unit, bindings: DesktopOriginalArticleBindings)`.

`desktopOriginalArticleBindings(repository: DesktopRepository, gate: DesktopHomeRetainedGate, scope: CoroutineScope, privacyModeEnabled: () -> Boolean): DesktopOriginalArticleBindings` is internal to the same real module. Supply the existing same Repository/gate and the **actual navigation leaf request scope**, parented to that retained Home gate. Closing/popping this leaf must cancel this scope even while Home remains retained beneath another page. The factory uses owner-tagged existing Retrofit services, same CookieJar/WBI cache, and existing original History repository. Privacy is the same live global privacy getter, not a captured false. No new account/cache/API client is created.

Required Root caller shape:

```kotlin
key(capturedEpoch, actualArticleNavigationEntryIdentity) {
    // Remember the request scope/bindings within this actual entry lifetime.
    // Dispose/popped entry cancels its scope; Home's gate remains retained.
    CompositionLocalProvider(
        LocalDesktopDynamicCardBindings provides actualOwnedGalleryPlatform,
        LocalDesktopDetailForeground provides actualEntryForeground,
    ) {
        ArticleDetailScreen(
            articleId = actualKey.articleId,
            initialTitle = actualKey.initialTitle,
            transitionEnabled = actualKey.transitionEnabled,
            onBack = { sharedReturnReady -> actualOriginalArticleBack(sharedReturnReady) },
            onUserClick = actualGuardedUserNavigation,
            bindings = actualBindings,
        )
    }
}
```

This is a contract snippet, not an installed source or a fake callback implementation. Root must bind its actual typed Article nav key and original shared-return/navigation mechanism. The Boolean supplied by the original screen is live derived from the real list's first visible item and must be consumed, not ignored. Existing SharedTransition/AnimatedVisibility scopes come from the real NavDisplay. The original image preview caller retains banner/body image list, initial index, source Rect map and original corner radii; provide the same actual original ImagePreviewDialog platform via `LocalDesktopDynamicCardBindings`, same global Locations and Assets actor. Its suspend save must retain the actual preview caller Job and leaf/epoch ownership; no independent save HTTP/store/chooser. Reuse Root's existing application SingletonImageLoader and window-level theme/image-save lifetime. A preview belongs to this keyed leaf, not to a dynamically selected future owner.

**Why outer key is required:** original produceState keys only `(articleId, retryToken)` and preview remembers its subject. An equal articleId under a renewed session would otherwise retain a successful old model and preview state without re-running the loader. Key the original caller/provider as a unit by immutable epoch+entry identity. Do not key merely on a live getter or only on visible=true, and do not dispose the real retained Home owner when this leaf is covered.

## Evidence and limits

Compile02: eight explicit inputs, 37 classes, only the three declared existing Article protocol family classes overlap immutable actual49. New UI/model/policies have no undeclared product overlap. Proof04: 19 assertions / one focused group using actual original API interfaces/serializers and shared Repository/SessionStore through a terminal application interceptor. No socket fallback exists. Nine fields, WBI request fields, richer Opus blocks, semantic HTML, normalized URLs/fallbacks, best-effort History and privacy, invalid ID, same-MID epoch renewal, leaf scope close and rejected late callbacks passed. CodeSource logs distinguish prepared protocol from actual49 DTO/SessionStore. CookieJar is read within actual request admission; the fixture does not claim a network BridgeInterceptor or TLS/wire test.

Audit04: 49 checks; complete UI inverse byte-equal, production selected2 replay, full original model/conversion/normalization/get/fetch inverse equality, exact existing four tool hunks, four whole DIRECT bodies, and unchanged other protocol outputs. A task-only in-memory prospective registry overlay supplies the required new identities during UI generator replay; fixed Git blobs and source hashes are separately validated. No live registry is changed.

Prepared remains **not mounted Main acceptance**: Root Article typed route and platform/local bindings are pending; no physical window, real HTTP/account, Gallery pointer, EXE or installed package was exercised here. Original retry/error rendering is source-preserved but not represented as a fabricated basic UI proof. The unused Favorites feedback callback in the History-only factory has no mounted action path; no History VM is constructed.

Proof01 syntax/harness failures, Proof02 parser trim expectation, Proof03 pre-Bridge Cookie expectation, Audit01 import re-insertion ordering, Audit02 missing prospective registry and Audit03 inverse network qualifier mistakes are preserved as fixture/audit history. Their final corrections changed no production source. Root's earlier prepare/compile failure directories are also retained. Binaries/classes, private temporary stores and replay duplicates are explicitly excluded from installation/commit.
