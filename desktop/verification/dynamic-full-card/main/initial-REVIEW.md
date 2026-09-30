# Dynamic confirmed-action integration: read-only review

Base HEAD: `263d55f01d61b9c2366816c68d1b1094cf78b4e0`. This review distinguishes that committed baseline from Root's subsequent uncommitted installation. No Main file, frozen producer, shared Gradle, account, socket or HWND was changed or run by this review. `review-identities.json` pins a point-in-time read of Root's worktree; Root continued applying narrow fixes afterwards, so those worktree pins are not a final-product claim.

## Exact consumer patch admission

The frozen `consumer.patch` was checked again with `git apply --check --no-index` against task-owned copies of the two exact `git show 263d55f:<path>` blobs. Exit 0, empty diagnostics. This is a real match of the intended baseline, not an idempotent reapplication to Root's already installed worktree. Its CommunityDynamicScreens baseline byte/LF SHA is `33083d21524f291d1d1b818d46727e1b2c16074b3e0afa900e2f7846d22dd20e`; CommunityScreens byte SHA is `47bc95fdb72be7625a5c563156ef24fcfd465073948bd77ded4d05c1859e0636` and LF SHA is `ee03a6a3d9636b99b0c8172290e648b1be0cb40b5678a57ae01a9c16a4a135b3`.

The page-local session hunk is an integration reference. Root's epoch-scoped Shell provider supersedes it and covers independent Space/Topic paths. Do not mount both owners, replace whole Community files, or overwrite Root CacheContent/Ready work.

## Original reducer authority and platform seams

All item types below are the actual `com.android.purebilibili.data.model.response.DynamicItem`.

| Action | Exact original function | Original ViewModel use |
|---|---|---|
| Like/unlike | `feature.dynamic.applyDynamicLikeCountChange(items: List<DynamicItem>, dynamicId: String, toLiked: Boolean): List<DynamicItem>` | DynamicViewModel 1802–1815, each timeline then userItems |
| Repost | `feature.dynamic.applyDynamicForwardCountIncrement(items: List<DynamicItem>, dynamicId: String): List<DynamicItem>` | 1945–1952, each timeline then userItems |
| Unfold | `feature.dynamic.components.unfoldRelatedDynamicItems(items: List<DynamicItem>, anchorId: String): List<DynamicItem>` | 2081–2091, original caller trims/ignores blank ID |
| Real delete | `items.filterNot { it.id_str == dynamicId }` | 2095–2100, each timeline then userItems |

Original `mapDynamicTimelineItems(currentState: DynamicUiState, transform: (List<DynamicItem>) -> List<DynamicItem>): DynamicUiState` (DynamicScreenStatePolicy 534) updates every retained request-type page, not only selected rows. Desktop's thin model interface preserves that coverage without duplicating the complete Android ViewModel schema.

Root's current interfaces are appropriate:

```kotlin
interface DesktopDynamicCardItemsOwner {
    fun mutateDynamicItems(transform: (List<DynamicItem>) -> List<DynamicItem>)
}
// Existing raw models, not filtered display lists:
DesktopDynamicTimelineState.mutateDynamicItems(transform) // page.copy(items = ...)
DesktopDynamicUsersState.mutateDynamicItems(transform)    // userItems AND dynamics
CommunityFeedState<T,C>.mutateDynamicItems(transform)     // only entirely DynamicItem rows
DesktopTopicController.mutateDynamicItems(transform)     // actual StateFlow items
```

Registration locations: each retained timeline at CommunityDynamicFeedReady.timeline(type); current Users model alongside that feed; generic CommunityFeed's actual page; a remembered Topic owner adapter beside DesktopTopicController in DesktopTopicDetailScreen. Shell supplies one repository/session/registry per credential epoch across Community, independent Space and Topic.

Users `dynamics` and timeline `page.items` are independent immutable values. Applying the reducer once to each, then TabsHost `users.updateTimeline(allItems)` replacing the mirror, does **not** increment twice. Do not add a second reducer invocation in that synchronization effect. Users.close rejects mutations and stops its owned jobs; retained timelines continue accepting same-owner actions after page navigation, which is intentional.

## Cache semantics

Use actual `DesktopDynamicCacheSession.saveTimeline(items: List<DynamicItem>): Unit` only for raw current `all`; it is asynchronous/coalesced admission and exposes failures through writeFailure. Preserve paging metadata, offsets, hasMore, boundary key/count, placeholder and load/error fields. Do not persist the blocked/not-interested filtered visible list. Empty is a genuine latest snapshot.

`suspend DesktopDynamicCacheSession.markNotInterested(dynamicId: String): Unit` returns only after successful disk persistence. Guest is allowed; it changes the 500-ID presentation filter, never raw timeline/UP/cache rows. `removed` is exclusively actual successful remote Delete.

Root's newest `currentAll` weak authority is necessary because BrowseMemory strongly retains multiple revision timelines. On a registry mutation, update all current raw models but persist only currentAll once. All fetch-success callbacks must also check `registry.isCurrentAll(model)`; Root has now added this check in the timeline constructor callback. Weak references alone do not retire an old revision. Original ViewModel saves cache on successful all fetch and author-unfollow; its like/repost reducers do not themselves call save. Root's explicit confirmed-action cache commit is a platform consistency binding, not a claim about an original extra save call.

## Late responses and actual original fields

Root's current Timeline success uses live `page`; failure/cancel use `snapshot.copy(items = page.items)`. This preserves confirmed changes during append/failure/cancel. Original FeedMergeUtils prepend and append both retain the existing duplicate-ID instance, so incremental prepend does not overwrite existing count/status. Normal replacement refresh replaces incoming rows; deleted IDs absent from existing can also return through late append/prepend. UP append reads current userItems after await, while UP refresh replaces it.

Detail's current primary-then-opus request can expose a card while full content is outstanding. Root's new per-field versions + `mergeDesktopDynamicDetailReadback` correctly target this same-epoch window. Preserve only touched fields on matching ID:

- Like: `modules.module_stat.like.count` and `.status`; preserve incoming forbidden and unrelated comment/forward data.
- Repost: `modules.module_stat.forward.count`; preserve incoming forbidden/status and unrelated data.
- Unfold: `modules.module_fold` plus item.visible; retain incoming author/content/basic/orig.
- Delete: keep item null even if the outstanding response returns that ID; retain valid fallback envelope.

Actual model: DynamicDetailData(item: DynamicItem?, fallback: DynamicOpusFallback?); DynamicItem(id_str,type,visible,modules,orig,basic); DynamicModules.module_stat/module_fold; DynamicStatModule(comment,forward,like) each StatItem(count:Int,forbidden:Boolean,status:Boolean). Never replace another page with a stale whole DynamicItem or reapply `+1` to a fresh server count.

Remaining original interaction-state gap: DynamicViewModel 1004–1007 owns likedDynamics/likeOverrides and resolves `_likeOverrides[id]` before a new request (1780), then writes it on success (1795–1800). Original Screen 1516–1517 and Detail 414–415 pass that owner override to every card. Host currently holds a local override and clears it on incoming item. A normal refresh can reset confirmed liked status; two retained old callbacks for the same ID can also both send up=1 after the first gate release, whereas the original second request resolves to unlike up=2. Preserve original ephemeral owner authority or explicitly retain this remaining gap; no second persistent store is needed.

## Small integration test set worth implementing

These are recommendations, not executed assertions or a new count of passed tests.

1. **Every raw owner, distinct baselines:** register all/video/pgc/article, UP userItems+dynamics, generic Search/Space and actual Topic owner. Give the same ID different counts and unrelated author/comment fields. Confirm like, unlike, repost and unfold; assert each baseline changes exactly once, all unrelated fields and paging metadata remain, mirror synchronization never adds twice. Real delete removes everywhere; NotInterested preserves raw rows and disk cache.
2. **Authoritative cache with retained revisions:** old all is strongly held, current all has a different/empty list. Mutate an ID only present in old all, flush the real temporary cache, assert old rows never become disk latest. Repeat with a delayed old-all fetch after currentAll changes; release it and assert its save is denied. Confirm current all mutation saves its own raw list once.
3. **Outstanding append success/failure/cancel:** gate actual fake transport entry, confirm a current row's like/repost/unfold, then release/throw/cancel. Existing rows retain values and pagination metadata resolves by original policy. Check deletion plus stale incoming separately: a deleted ID must not silently resurrect if this slice promises confirmed-delete retention.
4. **Late opus richness plus minimal local fields:** publish primary, gate full opus, confirm like/unlike/repost/unfold, release full with richer content and different comment/forbidden values. Assert richer content and untouched server fields arrive while only confirmed fields persist. Wrong-ID full must never copy the current ID's fields. Delete before readback must stay null. Same-MID epoch change rejects result and retires old actions.
5. **Old captured click after first success:** hold two actual card callbacks for the same ID. Invoke one, await confirmation/gate release without updating the second callback, invoke it. Original behavior requires first up=1 then up=2 with net zero count change; this catches missing owner override that a simple click/recompose test misses.
6. **Registry lifetime:** duplicate registration is identity-deduplicated; collected weak owners disappear; closed Users/Topic reject; registry/session close retires all callbacks; same-MID credential epoch creates a new BrowseMemory/page/request generation. Old generic response cannot publish into the new model, even with unchanged MID.

Separate existing issue, not caused by the new registry: generated repository pagination can advance its cursor before a UI continuation accepts the returned batch. A cancellation during that boundary can preserve UI items but skip the remote batch on next append. Full cursor rollback requires repository transaction/snapshot handling; UI item preservation alone is not proof of cursor rollback.

No native/GPU, real account, remote action or final-product zero-override UI claim follows from this source review. Full original Detail/comment/editor/MotionPhoto/system share and other frozen residuals remain unchanged.
