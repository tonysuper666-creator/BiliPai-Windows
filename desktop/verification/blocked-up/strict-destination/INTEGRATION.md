# Additional strict cached-namespace delta

Apply this small separate delta **after** the frozen eight-target installer plan `19ca03407d64e4c4fce848aac01c7b2b88eb8f884ae49c9795e031c9b8258125`, and before Root's separate Discovery failure/restore fence patches. That original 137-file review and the producer's 118-file cohort remain unchanged.

`delta-contract.json` pins both raw input and output bytes. The BlockedUp input is the reviewed store payload SHA `188189ca9b0e009dda2fbca44d59fab7ca64ce33e93da87e9c5c68d3d87e90bf`, which already includes the corrected cached-destination restart wording. This delta does not apply directly to the older producer store SHA.

Only two Windows host files change:

1. Root-owned `DesktopPluginStore.kt` gains `internal fun requireObjectNamespace(name: String)` under its **existing backing monitor**, requiring that its **cached** document value be absent or a JsonObject.
2. `DesktopBlockedUpStore.readRecords()` calls that helper before using `preferences()`. The existing migration error classification then identifies this malformed cached destination correctly.

This is needed because the original host's `preferences(name)` returns an empty JsonObject when a namespace is a primitive, array or JsonNull. The uncorrected store consequently considers such a destination absent and can overwrite the malformed data with migration records/marker. The general preferences method retains its prior behavior for other namespaces; this helper narrows strict validation to the consumer that explicitly calls it.

The new helper does not read disk, create another facade, unfreeze a backing, reload the document, alter the original model/import rules, or introduce a new namespace/key. It permits truly absent namespaces. Unknown unrelated fields/namespaces are retained by the existing atomic merge. There are no added upstream identities, dependencies, resources or generator tasks.

Five distinct real temporary-disk cases were run against both baseline and corrected variants: malformed primitive, array and null namespaces; genuinely absent namespace initialization; frozen old versus fresh new-generation writes. The baseline overwrite was asserted as the defect; corrected variants retain all malformed file bytes, reject migration/upsert, publish the restart error, and preserve unrelated fields. The same immutable product preference implementation and explicitly marked Windows Store/BlockedUp overrides were used. No current-main compilation, shared Gradle, HWND, user account/file or HTTP action occurred.

Patch files and full exact payloads are available for Root's sequential integration. Root should check both baseline SHAs before applying. The previously reported guest/active feedback corruption and multi-account restore gaps remain assigned to Root/player's separately owned deltas; this strict destination check does not claim to fix them.
