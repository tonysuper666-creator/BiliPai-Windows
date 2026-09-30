# Original dynamic visibility/order and UP selection — prepared integration

This slice is a draft over the immutable **dynamic-crash-main-product-snapshot** (`c01330bf9345f8f5d1be2fa70bce6d7c1c980179448ee22f2ad0f467292a6345`). That snapshot already contains the real two-field dynamic timeline consumer. There are no old 530 source overrides in the final compilation. Root's later metadata-only reorder does not change this snapshot's source or classes.

The prepared files have not been installed into the main product. The frozen handoff and `target-baselines.json` identify their exact bytes and current consumer bases. Keep the old 530 and all earlier native/FSR evidence immutable.

## Install seams

1. Add `prepared/desktop/tools/extract-upstream-dynamic-tabs.py` and its focused Python test. Generate into a separate `build/generated/dynamic-tabs`. The task depends on the original timeline/component/appearance/category preparation tasks. Add this directory to `main.kotlin` and the generator task to `compileKotlin` prerequisites. No dependency or resource additions are needed.
2. Copy the **four new** platform files: `DesktopDynamicTabsPreferences.kt`, `DesktopDynamicUsersState.kt`, `DesktopDynamicTabsHost.kt`, `DesktopDynamicUserPlatform.kt`, and the new focused test `DesktopDynamicTabsTest.kt`.
3. Apply `consumer.patch` only after checking current bases. It changes three files: `CommunityDynamicScreens.kt`, `DesktopCommunityRepository.kt`, `DesktopDynamicTimelineSettings.kt`. Root may manually merge these small consumer seams if its bases have moved. Do not overwrite Shell/build/whole source manifest.
4. Merge `source-registration-delta.json`. Existing identities receive the additional feature tag, preserving their other tags, mode and order. New sources receive the recorded original LF SHA. The common `AppSegmentOption` and `resolvePiliPlusScrollableUnderlineMinWidth` declarations remain emitted only by their existing owners; this generator removes those two declarations from its native component closure.

**No new Root ambient is needed.** All new preferences derive from the already mounted `LocalDesktopDynamicTimelinePreferences.current.context`, hence the exact same global `DesktopPluginStore` and backing generation. The original three settings use the existing `settings` namespace with original defaults:

| Original key | Original stored/default representation | Actual consumer |
|---|---|---|
| `dynamic_tab_visible_tabs` | CSV, `all,video,pgc,article,up` | Visible native tabs; minimum one; selected logical fallback; hidden UP opens profile |
| `dynamic_tab_order` | CSV, `all,video,pgc,article,up` | Native tab geometry/order; logical request identity retained |
| `dynamic_all_tab_horizontal_user_list_visible` | Boolean, `false` | Horizontal following rail on ALL; UP rail remains visible independently |

The original `dynamic_user_prefs` namespace supplies `dynamic_selected_tab`, `dynamic_pinned_users`, `dynamic_hidden_users`. Its exact key constants are generated from the original ViewModel. Windows binds SharedPreferences StringSet to JSON arrays of the same decimal MID strings, rather than inventing a second setting key or account store. Original visibility/order reads and all first-frame user preferences are taken from the already loaded flow; no disk/network is performed during their synchronous map. An already hidden selected tab cannot briefly issue a request for the wrong type.

## Accurate contracts

`DesktopDynamicTabsPreferences(context: DesktopPluginContext)` is internal; it exposes original visibility/order/ALL-rail flows and original persisted selected logical tab. The existing dynamic settings wrapper creates this facade over `preferences.context` and mounts original `FeedDynamicTabVisibilityItem` plus the original ALL-rail switch.

`DesktopDynamicUsersState(scope, preferences, selfUid, followingPage, liveRooms, unreadUsers, requestPage, stillOwned, selfFace = "")` is internal. Callback models remain original: `FollowingsData`, `List<LiveRoom>`, `UplistData?`, and `(Map<String,String>) -> DynamicFeedResponse`. The still-owned predicate is an immutable captured **MID + session epoch**, with an additional closed flag and selected UID/request token. The source cancellation boundary runs before and after transport and before publication. `close()` retires the UP request; the containing Compose effects cancel hydration children.

The three prepared repository reads are `dynamicSelectedUserPage(params)`, `dynamicFollowedLiveUsers()`, `dynamicUnreadUsers()`. They use the existing original `DynamicApi`/`BilibiliApi`, the same authorized repository client and CookieJar, existing API/login proxy graph, and captured epoch/MID checks. Unread/live reads are best effort; cancellation propagates. No send/mutate action or separate account authority is added. Followings are loaded through the existing `followings(mid,pn)` interface with its original `ps=50` and original completion rule. The live row mapping/filter comes from the original `LiveRepository` body, preserving UID and `live_status==1`.

`DesktopDynamicTabsHost` renders original M3/Miuix native sibling tabs, the original `HorizontalUserList`, and the original selected-UP header/filter. It maps logical 0/1/2/3 to all/video/pgc/article, independently of visible index. Logical 4 uses the original MID-cursor space fetch loop/retry and original content predicates; it never relabels the mixed feed as a complete UP feed. Local rows are provisional and cannot suppress the authoritative space request. Original remote append behavior is preserved, and original visible local/remote deduplication retains the local object. Pagination may fetch empty advancing pages just like the original. Original cursor updates are not transactionally rolled back after a later multi-page error/cancellation; no stronger guarantee is claimed.

The ordinary timeline still uses the previously integrated original incremental/full-refresh code, actual blocked-UP transform, original grid geometry and current real `CommunityDynamicCard` callbacks. Composer/navigation callbacks are retained. The native tab renderer closure is the original source graph; only Android `LocalConfiguration.screenWidthDp` binds to the actual Compose window width. The original liquid `DynamicAdaptiveSegmentedControl` branch remains a missing feature, and this host uses its exact original native branch.

## Verified scope

- Final raw Kotlin compiler cohort: current c013 product's three immutable jars + 231 pinned existing runtime jars; explicit new slice sources and three consumer class-family overrides only. No shared Gradle or source mutation.
- 10 meaningful focused JUnit cases: real same-backing disk keys/defaults/selection, hidden-tab initialization without wrong-source/hidden-user flash, reorder without reload, hidden-UP profile callback, original empty-page cursor/params, UID cancellation, same-MID epoch late rejection, full following pagination/whitelist/live/unread/pin/hidden consumers, original item filters/local dedup, owned close and frozen/broken disk refusal.
- Two fresh JVM theme runs: actual M3/Miuix original settings switches and order-arrow pointer press/release; actual native tab geometry and request-type switching; selected-avatar MID request; hidden selected-tab and UP fallback; 10 PNGs. The fixture row is text so these PNGs prove controls/host behavior, not complete original dynamic card parity. The prepared real product row is still `CommunityDynamicCard`.
- Actual current `DesktopRepository` and original Retrofit APIs, with a task-owned additional application interceptor that never calls `proceed`: all four feed types; original space MID/offset/features/timezone/web_location; followings/live/unread endpoints; exact shared CookieJar; same-MID credential epoch change rejects a deliberately delayed response. Only the newly extended CommunityRepository is an explicit prepared override. No socket, real credential, request header dump or remote account write.
- Four Python source checks: every source header/SHA, deterministic output, complete original store/visibility control bodies, exact native renderer bodies and original UID fetch/transport-only adaptation.

Read-only Windows reduced-motion binding uses SPI_GETCLIENTAREAANIMATION; the offscreen fixture deliberately renders the original static badge. Real Windows OS accessibility readback/polling is **not validated** in this cohort. No HWND/native window/device playback or complete packaged main-product acceptance is claimed.

## Remaining original differences

Previous two keys + these three keys make five effective original dynamic settings. The other five of the ten audited global keys remain absent: `dynamic_image_preview_text_visible` (true), `dynamic_detail_image_layout` (0), `dynamic_top_bar_collapse_on_scroll` (false), `dynamic_top_actions_collapsed` (false), and `dynamic_page_layout_direction` (LEFT/0). Exact first-frame caches/migration/default expressions remain recorded in the immutable 530 `field-audit.json`.

The original `dynamic_display_mode` (default SIDEBAR) and five Android hosts—SIDEBAR, SIDEBAR_RIGHT, HORIZONTAL, DRAWER_LEFT, DRAWER_RIGHT—are not implemented by this single Windows horizontal host. Full sidebar/drawer needs original Haze/blur/backdrop, haptic/motion and layout/gesture graph. Original liquid/glass tabs, swipe pager/reselect-to-top, automatic near-end scrolling/pagination, dynamic disk-cache hydration, not-interested-ID consumer and the full original dynamic card/detail/image/interaction graph remain separate work. No disabled or fake controls for those fields are added, and there is no claim of full dynamic/Sidebar parity.

The inherited full HomeSettings audit and the backend agent's Home card layout slice remain independent. This generator does not extract HomeSettings or home feed card functions.
