# Stage 1 BlockedUp store — reviewed integration against ae57dc6

This is an isolated installer draft. The current product baseline is `ae57dc66884da3243a7f1d5c24bb734674069483`; no product file was written, shared Gradle was not invoked, no HWND was created, and no user settings/account or external HTTP were used.

## Exact inputs and eight targets

The producer's `store-contract.json` SHA is `5dac0250ccf68fd1e51993327d03c97c23f1773fc79994b2b63ae126069f5f36`. Its `store-artifact-manifest.json` SHA is `8793d8cb7860406ac02891dc7d2c3253407e297043f25f12984709544b6fb039`. Every one of its 118 raw artifact bytes/sizes, all 234 classpath entries, all three original source hashes, all compiled-source identities and the three current Discovery file baselines were verified. Frozen producer files remain unchanged.

`install-plan.json` is the authoritative raw byte plan. `install-reviewed.py` defaults to read-only verification. Root may later explicitly apply it:

```powershell
python desktop/.local/blocked-up-store-main-review/install-reviewed.py
python desktop/.local/blocked-up-store-main-review/install-reviewed.py --apply
```

The installer performs all checks before writing, rechecks each target immediately before its atomic replacement, refuses stale/foreign bytes, rejects traversal and redirected target parents, and rolls back exact previous bytes if a later replacement fails. Identical installed payloads are idempotent. Nine meaningful real temporary-directory failure tests passed; no installer application was made against the product.

The eight targets are:

1. `desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopBlockedUpStore.kt`: new original-model Windows store, plus the separately tested cached-error wording delta described below.
2. `desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDiscoveryPreferences.kt`: producer-verbatim stage1 migration/consumer adapter.
3. `desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDiscoveryRepository.kt`: producer-verbatim `blockedUps` getter.
4. `desktop/src/main/kotlin/com/bilipai/desktop/ui/DiscoveryScreens.kt`: producer-verbatim migration error/retry strip.
5. `desktop/src/test/kotlin/com/bilipai/desktop/data/DesktopBlockedUpStoreTest.kt`: producer-verbatim eight actual `@Test ... : Unit` methods.
6. `desktop/tools/extract-upstream-blocked-up-platform.py`: producer-verbatim source-preserving generator.
7. `desktop/build.gradle.kts`: one unique `extractUpstreamBlockedUp` producer, `generated/blocked-up` source root and compile dependency, with discovery/sync helper inputs declared.
8. `desktop/upstream-sources.json`: **425 -> 427 unique sources; 201 -> 201 resources; no new dependencies or resources**. No release version or feature-completion claim is changed.

The original BlockedUpRepository row retains its `policy-extract` mode and `discovery` feature and gains only `settings-blocked-up`. The entity BlockedUp and BilibiliBlockedListSyncRepository add two `policy-extract` identities. BlockedUp's Room annotations alone become kotlinx.serialization annotations; all original record fields remain. Existing discovery generation remains the only provider of BilibiliBlockedListRemoteStatus, BlockedUpRelationSource, BlockedUpWriteResult, resolveBlockedUpRelationReSrc and buildBlockedUpWriteMessage. The new generator excludes those five declarations. It produces exactly three files in its own output; fresh regeneration matched all three frozen SHA identities. All 628 final source/resource LF hashes were verified. Extracted files are not also copied by the direct-source Sync task.

## Required Root order, without overwriting Shell

The existing normal-GUI Main already creates the global application PluginStore and initializes NetworkProxyStore before DesktopRepository. Current Shell constructs Discovery before its local `pluginStore` variable, but Discovery's default global facade resolves the same normalized root/backing. This is compatible with the current product; it is not a second persisted blacklist.

For explicit single-instance injection, Root may move its existing unmodified `pluginStore = remember(applicationPluginStore) { ... }` block above Discovery construction, preserving proxy initialization, and apply the small `ROOT-SHELL-SNIPPET.kt.txt`. The shared store object passed to Runtime and blockedUps must be the same object. Runtime creates its own platform context around that store; no second document/cache is created. Management, Community and Space must later use **`discovery.blockedUps`**, not construct a second active table. Do not substitute an account-scoped recommendationContext for the global store. This snippet is documentation, not an automatic installer target; all current Tree/search/restore/sidebar changes remain Root-owned.

After these eight files are installed, Root's separately owned Discovery peer/restore fence and player-owned corruption-failure adapter must be applied to the newly installed producer baselines. They are deliberately not included here. A preceding mutation of the relevant baseline/platform files causes installer verification to fail instead of overwriting that work.

## Persistence and failure review

Full records and `legacy_discovery_migration_version=1` are published in one actual shared backing atomic update. There is no second list/document cache. All readable legacy sources are staged before this publication; any parse/path/write failure prevents that publication. Source files are retained byte-for-byte. Every black-store mutation first completes migration, so a pending source cannot later resurrect an accepted unblock. A marker also validates destination records rather than masking corruption. Existing full metadata wins during original-policy migration/import; normal upsert follows original Room replace behavior.

Input scope is precisely guest `discovery/plugin-settings.json` and `accounts/<canonical positive decimal MID>/discovery/plugin-settings.json`. Other directories and account/cookie/token files are not read. Known paths go through the existing no-link/reparse guard and source-size checks. Valid unrelated JsonObject namespaces are allowed and preserved; malformed top-level namespace shapes and invalid known UID encoding are rejected. The source reader does not promise rejection of every future unknown field. The destination preserves unrelated namespace/field data through the existing merge transaction.

The producer's own runner directly invoked eight executable @Test methods. This review additionally used the actual JUnit engine: **8 found, 8 started, 8 succeeded, 0 failed** against the explicitly marked reviewed store/producer Discovery overrides and immutable product snapshot. This is isolated compilation/test evidence, not a current-main compiled-product result. The archive/queued writer test still passed with the reviewed wording delta.

Four distinct new real-disk review boundaries were exercised against both original and reviewed store variants:

| Boundary | Actual result |
| --- | --- |
| Corrupt cached global `records`, repaired file on disk | Same-context retry and same-root facade remain on the cached failed generation. They do not reload. Frozen old writers stay rejected; a fresh generation reads the repaired disk. |
| Corrupt legacy file repaired on disk | Same-context migration retry succeeds, input bytes remain unchanged, and unrelated global namespaces survive. |
| Corrupt guest or active-account Discovery document | Current producer Discovery preferences/feedback context creation can throw before the migration strip is rendered. Root/player own the separate failure adapter. This is not covered by the store's visible-error guarantee. |
| Blocking request with pending legacy failure | The account's independent TodayWatch feedback may already be persisted before blockedUps fails. The blacklist/marker are not written and Repository does not continue to remote sync. This is not a two-file transaction. |

The initial failed review run exposed the guest consumer-construction exception and is retained as diagnostic evidence; it was not counted as a pass. Subsequent fixtures assert that boundary explicitly.

## One reviewed store delta

`patches/cached-destination-error.patch` is the only difference from the frozen producer store. `readableRecords()` now says that repairing destination data requires restarting the application. Migration tracks only whether failure occurred while reading/validating the cached destination; those errors use the same restart wording. Legacy disk read/publication failures keep the real retry wording. No disk hot-reload, new same-path facade workaround, unfreeze, schema, import policy, field, lock or persistence algorithm was added.

The corrected source is included in the eight-target installer and is explicitly identified by both producer and reviewed payload SHAs in the plan. Its compile output and original-versus-reviewed real-disk evidence are independently pinned.

## Restore and scope limits

The original stage1 queued archive test proves that the old **global blacklist backing** is fenced and a new generation can read restored records. Current Runtime originally froze only that global store and current recommendationContext. It does not by itself fence every cached inactive-account/guest feedback backing or stop a cached Preferences facade creating another account scope. Root accepted and owns the additional peer-registry/drain/freeze delta this round. This review does not claim that inherited gap is fixed by the stage1 payload.

Stage1 does not implement the full original blacklist management screen, server-list import, profile refresh, Community/Search/Space consumers, real remote cancellation/account acceptance, or native window UI. All those slices must retain this one full-model global store. FeedFilterPlugin recommendBlockedMids and TodayWatch disliked creators stay separate original policies and are neither imported nor reclassified as this blacklist.

Root should run its own shared product compile and the actual DesktopBlockedUpStoreTest target after the installer plus its separate failure/fence deltas are combined. This cohort supplies no main compile or packaged verification claim.
