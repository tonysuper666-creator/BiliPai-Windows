# Blocked UP stage 2: original management and real consumers

This is an isolated, tested production draft. Stage 1 is unchanged and is a prerequisite. This cohort is not installed, deployed, or counted as completed product parity.

The production boundary is one global `DesktopBlockedUpStore`, storing the full original `BlockedUp` model inside the existing global `DesktopPluginContext` backing. It does not reintroduce an account list, a second document cache, a token authority, or a background writer.

## Integrate exact owned files

`owned-files.json` records raw base and new SHA for seven modified files, five new Kotlin/tool/test files, and eight byte-identical original PNGs. `patches/` contains exact modifications with main-relative paths. `DesktopBlockedUpStore.kt` has Root's installed and reviewed Stage 1 SHA `fa504be22b623b53cff12ab1e3cb54f66181eac448807055a6611656d12eaa2e` as its base; the destination-namespace check and restart notice are retained. Its only addition atomically replaces a profile when the captured record still equals the live record. An unblock or newer block while a profile request is running cannot be undone by that response.

Main/current source edits are explicitly compiled as overrides of Root's successful immutable `blocked-up-foundation-product-snapshot` (manifest SHA `74165f272e012c1a13e9171e963bf76127dbd71e7003da9e2576b7af2a25e2e6`). `compile-evidence.json` records every compiled source, three immutable product jars/dependencies and parent contract. No Stage 1 class directory or PluginStore/Runtime/restore-fence source override is in the final compile/runtime classpath. Earlier unaccepted compiler outputs are not included in the frozen artifact manifest; `classes-foundation-product` is the tested output. No opaque mock replaces the Discovery store, Community repository, management UI, or original policy/model classes.

## Root singleton and routes

Create the store once from Root's global plugin context/backing, and inject that same instance:

```kotlin
val blockedUps = DesktopBlockedUpStore(DesktopPluginContext(pluginStore))
val discoveryPreferences = DesktopDiscoveryPreferences(libraryRoot, blockedUps)
val community = DesktopCommunityRepository(repository, blockedUps)
// The Runtime context wraps this same pluginStore; do not create an account-scoped context.
```

`DesktopCommunityRepository` has one new optional trailing constructor parameter `val blockedUps: DesktopBlockedUpStore`; its default facade addresses the same global library root, and its lazy `val blockedUpRepository` reuses the existing `DesktopRepository` HTTP/session authority. Root should prefer explicit shared injection. Initialization/migration only reads local MID files; no remote sync happens on construction or composition.

Settings root detail `SettingsSearchTarget.BLOCKED_LIST` can render:

```kotlin
DesktopBlockedListScreen(community.blockedUpRepository, onLogin)
```

The existing `DesktopSettingsTree` must also add `BLOCKED_LIST` to `nestedPageOwnsScroll` and provide a composable content slot. The original management `LazyColumn` owns scrolling; do not mount it under the Tree's `verticalScroll`, which would give it unbounded height. Root still owns the Tree, navigation/Back/focus, Shell and actual slot integration.

Space, dynamic card and comment action hooks are included in the seven exact patches. Both actual Root Space and the legacy Space dynamics feed retain raw rows/cursors while recomputing visible rows. Comment blocks use original COMMENT `re_src=15`; all profile/Space/dynamic actions use PROFILE `re_src=11`. An existing server `relationStatus=128` is shown as an unblock action, with local removal first. All success/partial remote failure messages are displayed by the shared action helper.

Root owns the related-video cards, so their remaining hook is:

```kotlin
DesktopBlockedUpAction(
    community.blockedUpRepository, card.authorMid, card.author, face = "",
    onLogin = onLogin,
    source = BlockedUpRelationSource.PROFILE,
)
```

Pass a real face URL if the current source exposes it; `VideoCard` lacks that field, so an empty value is honest until user-requested profile refresh. The helper captures the target MID and session epoch on opening confirmation, then invokes only the captured explicit action on Submit. Dialog cancellation/composition never mutate an account. Do not call the older remote-only `social.removeBlacklist` for these local+remote actions.

## Repository API and original semantics

`DesktopBlockedUpRepository(repository, store)` exposes:

- `blockUpWithBilibiliSync(mid, name, face, relationSource=PROFILE, expectedSessionEpoch=current)`.
- `unblockUpWithBilibiliSync(mid, relationSource=PROFILE, expectedSessionEpoch=current)`.
- `importBlockedUps(text)` using original JSON/share/pasted UID parser and import plan, entirely local.
- `importFromBilibili(expectedSessionEpoch=current): Result<BlockedUpImportResult>`: explicit pull/import; never uploads the local table.
- `refreshBlockedUpProfiles(expectedSessionEpoch=current): BlockedUpMetadataRefreshResult`.

Original local-first semantics remain: accepted local block/unblock persists before its authorized remote act 5/6. An absent CSRF/account skips remote with the original message. A response code or transport failure keeps the local change and reports the remote status. Migration/persistence failure prevents the remote write. A same-MID credential generation change is still rejected, and Cancellation propagates.

The original `BilibiliApi` Retrofit declarations/models are reused, including `getRelationBlacks(ps=50,pn=1)`, `getUserCard(mid,photo=true)`, and `modifyRelation(fid,act,csrf,re_src)`. No routes or response DTOs are invented. Page limit 120, page delay 220ms and profile delay 120ms come from extracted original constants. The original stop-on-short-page/known-total behavior and final full-page delay are retained.

Profiles preserve all original fields and behavior: successful card metadata updates; a received non-success/missing-card payload marks suspected deletion; transport failure preserves old metadata. Delayed results cannot resurrect an entry removed or replaced while the request ran. No actual internet profile refresh has been performed.

Production transport retains the shared CookieJar/policies. An expected-epoch guard runs immediately before transport and after response. This module additionally disables redirects/reconnection and wraps POST bodies with `isOneShot=true`, preventing HTTP503/408 Retry-After retransmission. The internal test factory changes only client/base/ensure-session/pacing seams and is not a product CLI or alternative auth source.

## Source registration

Run the new tool after copying it:

```text
python desktop/tools/extract-upstream-blocked-list-ui.py --repo . --output <generated>
python desktop/tools/extract-upstream-blocked-list-ui.py --repo . --inventory
```

It emits five files: original `BlockedListContent` and all original helpers unchanged; original padding/filename functions; original pacing constants with thin accessors; original `UserLevelBadge` with only Android drawable-id/bitmap loading bound to desktop classpath PNGs. There is no fake badge or replacement component body. `source-inventory.json` has six source entries; BlockedUpRepository/SyncRepository are already Stage 1 source identities, so merge their features rather than duplicate them. The other four entries are new policy-extracted source identities. `resource-inventory.json` lists the eight original `drawable-nodpi/lv*.png` raw SHAs and prepared classpath destinations.

Keep the Stage 1 original import/share policy generator; these generators do not duplicate its public declarations. The two private constants of the original repository remain file private.

## Actual verification

Independent cached Kotlin 2.4.0/JDK21 compile with Compose and serialization plugins passed against the immutable product snapshot, with every override and dependency hash recorded. There was no shared Gradle invocation.

15 executable JUnit-compatible Unit methods passed: guest no network; original fields/act/re_src/CSRF and local-first remote failure; persistence fence before POST; real task-owned loopback HTTP503 Retry-After:0 gets one POST; same-MID epoch guard; 50/120 pagination and original pacing; full profile mapping; response vs transport metadata semantics; delayed response against new/removed record; cancellation; original JSON full profile import; retained cursor/scroll owner; exact four original search author mappings and unfiltered photo behavior; corrupt migration prevents manual account requests.

`ui-proof/result.json` binds four actual `ImageComposeScene` cases (Material3/Miuix, light/dark), 28 real offscreen pointer press/releases. Six original management button callbacks, full metadata and original LV6 PNG were observed; then the actual Windows management host unblocked through its real store and synthetic Retrofit POST. Construction/composition sent zero remote requests. Four original-content PNGs are preserved.

Three Python source checks passed: original content/helpers unchanged, original padding/pacing preserved, all eight PNGs byte identical. Every artifact is frozen with extended Win32-path traversal/raw hash verification, including long generated class names.

## Remaining boundaries

Root settings/related-video hooks are not installed by this lane. Native file picker/clipboard actions compile and are connected to explicit buttons but were not opened in the headless fixture, and no real account or internet sync/profile request was sent. Windows sharing copies the original share text to the clipboard; it does not claim Android's ShareChooser integration. JSON format/pasted import metadata and real temporary disk are verified separately.

Filtering follows the original SearchViewModel video-owner, UP, live-room and live-user UID types. It does not expand to article/PGC/photo categories that original code does not filter. Dynamic filtering uses the original block action's author MID; explicitly opened dynamic detail remains available. Other original add menus/live-feed consumers, fuller popup visual parity and all original Space account actions remain separate tracked work. This cohort does not turn a copied profile into a remotely verified account.
