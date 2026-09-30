# Stage 1 — original global BlockedUp model/store and real Discovery consumers

This is a partial, isolated stage. It does not implement the complete management page, server black-list import, profile refresh or Community/Search/Space consumers yet. Those remaining slices must use this same global store, not create a second active blacklist.

## Exact integration boundary

Copy the six entries in `owned-files.json` from `prepared/`, checking each existing baseline raw SHA. Three existing files are patched: DesktopDiscoveryPreferences, DesktopDiscoveryRepository and DiscoveryScreens. The new files are DesktopBlockedUpStore, its focused test and the source extractor. The production overrides are explicitly compiled in the isolated evidence; they are not presented as untouched product classes.

```text
python desktop/tools/extract-upstream-blocked-up-platform.py --repo . --output <generated-blocked-up>
python desktop/tools/extract-upstream-blocked-up-platform.py --repo . --inventory
```

The generator produces three files: the original BlockedUp fields, missing original pure import/share policies, and the original remote-list-to-import mapper. BlockedUp changes only Room annotations to the already supported kotlinx.serialization annotation. BlockedUpRepository's existing status/source/write-message declarations continue to come solely from extract-discovery-platform.py; this extractor excludes those duplicate declarations. Merge its feature into that existing policy-extract source row. The entity and BilibiliBlockedListSyncRepository add two original source identities. No dependencies/resources are added.

`DesktopBlockedUpStore(context: DesktopPluginContext, clock: () -> Long = ...)` accepts Root's shared global context. It exposes `records: StateFlow<List<BlockedUp>>`, `mids: StateFlow<Set<Long>>`, `migrationError: StateFlow<String?>`, `upsert`, `remove`, original-policy `import`, and `migrateLegacyDiscoveryMids(): Result<Int>`. It stores the original full record fields under the one global context's `blocked_ups` namespace in the existing atomically replaced plugin-settings.json. This is a Windows persistence binding for the original global Room table, not the original Room database schema or account credential storage.

The new trailing DesktopDiscoveryPreferences parameter is `blockedUps: DesktopBlockedUpStore`; Root should supply the same instance used by the management/Community/Space adapters. Its default binds to the existing global root's shared DesktopPluginStore backing, so old call sites remain compatible. If constructing discovery before PluginRuntime.context, use the same global PluginStore context backing; do not introduce account-scoped UP stores. The actual discovery repository exposes `blockedUps` for the shared settings consumer. Its `blockedCreators(mid)` remains source-compatible but deliberately reads the same global Flow for every account, matching the original global table. The account parameter still selects separate existing negative-feedback preferences; those policies are not merged into this UP blacklist.

DiscoveryPreferences retires its old per-account mids reader and writer. Actual not-interested/block and unblock flows now mutate this global original-model store, then use the existing DesktopDiscoveryRepository epoch/account checks and existing remote sync semantics. Records newly obtained only from the negative-feedback snapshot have a real name but no fetched face/profile; legacy MID records explicitly have UP主MID, empty face and null lastSyncedAt. No profile/network verification is claimed.

## Safe migration and visible failure

The migration reads only guest `discovery/plugin-settings.json` and `accounts/<canonical positive decimal MID>/discovery/plugin-settings.json`. It never opens account/cookie/token files, ignores non-MID directories, and checks every known source through the existing Windows link/junction/path guard before reading. It rejects corrupt JSON, unknown namespace/UID shapes, wrong destination marker versions, oversized files and redirected paths.

All readable sources are staged before publication. Existing full metadata wins; the original buildBlockedUpImportPlan deduplicates, skips invalid IDs and creates fallback names. The complete records list and `legacy_discovery_migration_version=1` are committed in one existing shared-backing atomic update. If any source or disk write fails, no list or marker is published. The generic migration error is visible in actual DiscoveryScreens with a user retry button, without exposing full filesystem or account paths. The first local consumer invokes migration; successful repeated calls are no-ops.

Every local write first requires migration completion, so a pending/corrupt input cannot later resurrect a user's already accepted unblock. A failure stops that local write before its caller proceeds to remote sync. Retired input files are retained unchanged; the marker prevents re-import after a successful unblock and restart. Nothing automatically fetches or modifies the remote blacklist.

The store derives its Flow directly from the shared backing and holds no second persisted document/list cache. Mutations from same-root facades share a lock and atomic publication. Root's existing `pluginRuntime.shutdownForRestore()` already freezes the global backing; no additional writer scope or shutdown hook is introduced. Old facades then reject writes while a newly constructed generation can read the restored file. Ordinary same-path construction does not freeze a newer session.

## Actual evidence

The complete patched DiscoveryPreferences/Repository/Screens plus original generated policies/store/tests were independently compiled against Root's immutable network-proxy-product-snapshot and pinned dependency identities. No live build/classes or shared Gradle were used.

Eight executable focused JUnit methods passed on real task-owned temporary disks: scope whitelist/metadata/atomic marker; fresh-generation no-resurrection; corrupt input and retry; unknown schema rejection; real replacement failure without Flow publication; original JSON/share/import rules; concurrent same-root facades and actual Discovery global account semantics; actual DesktopBackupArchive restore while an old writer was demonstrably Thread.State.BLOCKED on the real shared backing monitor, followed by old-generation rejection and fresh-generation writes. The source actor/store tests create no HWND, read no user settings/accounts and send no HTTP requests. The full management UI and remote API/cancellation/epoch fixtures remain unverified in this stage.

Original BiliPaiFeedFilterPlugin recommendBlockedMids and TodayWatch disliked creators are distinct original feed/personalization policies. This migration does not read, merge, clear or relabel them as the original UP blacklist.
