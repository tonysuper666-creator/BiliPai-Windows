# Dynamic cache integration review

Read-only review of Main `19850573aa21d8490c82fd4646b25e65339db767`, alpha.9 source, and Root's saved cache WIP. No Main edits, Gradle runs, account requests, or network clients. All recommendations are implementation seams, not verified product behavior.

## Original behavior to preserve

- `DynamicViewModel.kt:171,236,261`: read user preferences, then the normalized not-interested IDs, then cached `all` items, then rebuild followed users. A valid nonempty payload seeds only the `all` page with `isLoading=false`, `error=null`, `isCachePlaceholder=true`. Bad JSON and empty payload do not seed a page.
- `DynamicViewModel.kt:281,844`: successful `all` feed saves the first 100 **raw** page items, plus the current time. Successful other tab/selected-UP pages do not replace this cache. An empty `all` save removes only items/time and preserves not-interested IDs. The timestamp is not read and imposes no TTL.
- `DynamicViewModel.kt:489`: successful author-unfollow removes that author from timeline/selected-UP/sidebar data, rebuilds users, and saves the resulting `all` page. This event consumer is still missing in current Windows Users/Social, so do not count full follow-event parity merely from installing persistent cache.
- `DynamicViewModel.kt:2105` and `DynamicScreenStatePolicy.kt:16`: NotInterested is a local action with no Token/CSRF/login gate. Blank input fails with `无法识别该动态`; successful action says `已标记为不感兴趣`. IDs are trimmed, blank entries discarded, deduplicated, and capped at the last 500 in iteration order. Re-marking an existing ID does not move it to the end.
- `DynamicScreenStatePolicy.kt:36`: not-interested IDs filter the displayed all/video/PGC/article/selected-UP rows. The raw page/cache is not deleted. Followed users are rebuilt from raw `all` items, not from the filtered presentation. In the card host, NotInterested must not reuse Delete's `onRemoved` callback.
- `DynamicScreenStatePolicy.kt:578` and `DynamicIncrementalRefreshPolicy.kt:40`: the first refresh after a cache seed replaces cached items even when incremental refresh is enabled; it clears the placeholder and incremental boundary. A failed refresh keeps the cache and placeholder.
- Original like/repost/unfold/delete callbacks modify UI state but do not directly call `saveDynamicCache`. Do not add unrelated mandatory cache-write side effects for those actions while claiming verbatim cache semantics.

## Minimal seams

One Root-owned `DesktopDynamicCache` using the already shared `DesktopPluginStore` and existing `DesktopSessionStore` guard; no lifetime/writer in `DesktopBrowseMemory`.

```kotlin
// Names are proposals, not compiled APIs.
suspend fun openCurrentSession(): DesktopDynamicCacheSession?
val DesktopDynamicCacheSession.notInterestedIds: StateFlow<Set<String>>
val DesktopDynamicCacheSession.cachedAllItems: List<DynamicItem> // current successful snapshot
fun DesktopDynamicCacheSession.acceptAllTimeline(items: List<DynamicItem>): Boolean
suspend fun DesktopDynamicCacheSession.markNotInterested(id: String)
suspend fun DesktopDynamicCache.shutdownForRestore()
```

`markNotInterested` returns after the owner-checked atomic write and flow publication; it throws for blank input, a retired owner, shutdown, or write failure. It does not authenticate or make a remote request. Root has confirmed that a stable guest owner (MID 0, empty credential) and a login owner use the same authority/monitor with separate tags; neither may restore the other owner's data.

`DesktopDynamicTimelineState` can take constructor-only `initialItems` and `onAllPageAccepted` hooks. Apply the seed only to a new `type=="all"` instance, preserving `initialized=false` so normal startup refresh still runs. The success hook belongs after the existing final ownership check and `page=successPage`; catch storage errors separately so a successful HTTP response does not become a feed failure. Existing browse-memory states are not re-seeded. A new revision/page can seed from the cache session's current successful snapshot instead of a permanently captured initial payload.

`CommunityDynamicFeed` must await opening the owner-bound session before mounting `DesktopDynamicTabsHost`; this host currently activates the primary startup fetch in its first effect. Read/parse on IO. Before showing the result, recheck owner after suspension. Merge `notInterestedIds` into its existing remembered transform with blocked MIDs, preserving raw page data and `users.updateTimeline(allItems)`.

For later follow-event parity, add narrow `Users.applyAuthorUnfollow(mid)` and `Timeline.removeAuthor(mid)` seams; wire them to a single current-account follow-change publisher at the existing Social mutation success boundary. Do not create a second relation repository. The `all` result of unfollow is an original cache-save call point.

## Concurrency and shutdown pitfalls

1. Every read-dependent mutation must nest locks in one order: SessionStore owner monitor, then PluginStore backing monitor (`updateFromSnapshot`). Do not capture store state under its monitor and call the session guard from inside it.
2. On a namespace-tag mismatch, reset all three original keys (items/time/IDs) in the same transaction as the new tag and requested mutation. Writing just the tag would relabel previous-account payload/IDs. An untagged legacy namespace must not be attributed to the currently logged-in account.
3. Persist the stable tag, not the process epoch. Include epoch in in-memory ownership equality. Account A → B → A (same credential) rejects the old A writer while cold restart with the same current credential can restore.
4. Cache serialization can happen outside the SessionStore monitor, but ownership must be revalidated inside the actual disk transaction. No later publication from an obsolete command may overwrite the new session's flow.
5. Coalescing must preserve write order, including an empty save. Older pending nonempty saves cannot revive a cache after an accepted empty save. Do not conflate NotInterested commands away; separate every successful caller acknowledgement from superseded best-effort timeline snapshots.
6. A bounded queue needs an explicit rejection/error signal. An accepted operation cannot disappear silently when full. A write failure must complete that caller exceptionally, retain previous durable/snapshot state, report a bounded error, and let the actor process later commands rather than strand deferred callers.
7. Shutdown first rejects new commands, closes acceptance, drains and joins already accepted operations, then allows `PluginStore.freezeWrites`. Cancelling the actor or marking accepted commands obsolete solely because the service is closing loses valid final writes. Use the existing Root `beforeStoreFreeze` barrier and make shutdown idempotent.
8. The owner-bound actor persists outside page scope; UI cancellation should prevent feedback/paint but must not abandon an already accepted durable NotInterested command. Old owner validation still protects a true account switch.
9. A cache persistence problem must not block the whole client or be misreported as HTTP failure. Malformed payload is soft absence as in original; an invalid namespace/write failure should have a visible bounded local error and usable network feed.

## CookieJar epoch hole: exact minimal fix

`DesktopSessionStore.kt:45` handles nonexpired Set-Cookie by storing a scoped server cookie; `loadForRequest:78` overrides account-cookie names with explicitly authorized `saved.cookies`. Therefore a nonexpired server SESSDATA currently does **not** replace the effective authorized credential, and this review does not recommend widening its authority.

A valid-domain, `path=/`, expired SESSDATA removes it from `saved.cookies` in `saveFromResponse`, but currently leaves `generation` unchanged. The stable owner tag changes while Root's epoch-keyed cache session remains old and can only fail closed. Compare previous and next saved SESSDATA, and bump `generation` once **after** successful `persist(next); saved=next`, under the existing session monitor. Keep metadata/account summary policy unchanged. `removeExpired` only drops scoped server cookies, so it does not need this bump. An old request already rejected by `requestGeneration` must retain zero effects.

## Tests with actual regression value

1. Cold restart, same owner: old timestamp and unknown JSON fields accepted; first 100 raw items restore only `all`, banned IDs apply to all/UP presentation, raw cache/sidebar inputs remain intact.
2. Cached first refresh with incremental enabled replaces old rows, clears placeholder/boundary, and follows fresh pagination; failed refresh retains cache/placeholder and a later explicit retry succeeds. Invalid/empty payload never seeds.
3. Owner A → B and A → B → A before pending writes execute: old writes cannot update disk or active flows; first B transaction resets the entire original cache namespace. Same-credential cold restart succeeds despite reset process epoch.
4. Guest NotInterested writes only guest-tagged state with zero HTTP/credential gate; guest → login and login → guest reset old namespace state without painting it. Blank IDs fail; 501st distinct ID evicts the oldest, re-marking an existing ID preserves original iteration order.
5. Queue/coalescing: a pending large nonempty save followed by empty save finishes with no items/time and retained IDs; parallel NotInterested calls retain both IDs with two successful acknowledgements.
6. Forced atomic-replacement failure: no ID-flow success publication or success return; previous durable cache remains readable, and a subsequent successful command completes rather than hanging behind a dead actor.
7. Shutdown while an accepted write is held at the disk transaction: shutdown waits, final data is durable before freeze, later calls fail immediately; a second shutdown completes; no post-restore old backing writer revives.
8. Cookie expiry: valid expired SESSDATA bumps epoch once, retires old owner, and allows current session reacquisition. Visitor changes and nonexpired server SESSDATA do not bump; a stale request carrying expiry causes no change.

The short highest-value integration proof is one cold-start fixture followed by a first-refresh fixture and all/selected-UP NotInterested pointer checks, on the actual final product classpath. Unit tests alone do not establish that the Root startup barrier and transform are mounted.
