# Original storage entry selection — frozen isolated slice

This slice selects the original STORAGE_BACKUP category's two working backup entries. It reuses the single original SettingsDetailGroup supplied by the frozen playback lane and the existing Windows DesktopBackupCoordinator. It creates no parallel backup provider, store, scheduler or schema.

## Owned files and Root hooks

Copy only the three files in `owned-files.json` from `prepared/`, checking the existing BackupSettingsDialog baseline SHA first. `patches/BackupSettingsDialog.kt.patch` contains the same narrow modification; do not overwrite unrelated Root edits.

The source generator is `desktop/tools/extract-upstream-settings-storage-entries.py`:

```text
python desktop/tools/extract-upstream-settings-storage-entries.py --repo . --output desktop/build/generated/storage-entries
python desktop/tools/extract-upstream-settings-storage-entries.py --repo . --inventory
```

Merge its feature into the existing SettingsSections.kt policy-extract inventory row. There are no new upstream source identities, resources or dependencies. Its one generated source defines only the new entry functions:

```kotlin
// com.android.purebilibili.feature.settings; internal to the desktop module
@Composable
internal fun SettingsStorageBackupCategoryEntrySection(
    onSettingsShareClick: () -> Unit,
    onWebDavBackupClick: () -> Unit,
)
```

Root's STORAGE_BACKUP category can use that function directly; send the exact SETTINGS_SHARE / WEBDAV_BACKUP target to its navigator. Alternatively the tiny `DesktopSettingsStorageEntries(onOpenBackup: (SettingsSearchTarget) -> Unit)` adapter preserves those target identities without introducing route state.

Root's `backupContent(target, onDismiss)` maps the exact target through `resolveDesktopBackupEntrySection(target)`. Pass the returned enum to the existing dialog:

```kotlin
BackupSettingsDialog(
    backup = sharedBackupCoordinator,
    onDismiss = onDismiss,
    onExit = existingExitHook,
    initialSection = requireNotNull(resolveDesktopBackupEntrySection(target)),
)
```

The enum is `com.bilipai.desktop.ui.DesktopBackupSettingsSection { ALL, LOCAL_SETTINGS, WEBDAV }`. The new parameter is trailing and defaults to ALL, so the existing sidebar call remains unchanged. SETTINGS_SHARE selects LOCAL_SETTINGS (existing ZIP export/import); WEBDAV_BACKUP selects WEBDAV (existing remote config/test/backup/list/restore). Other settings targets resolve to null and must keep their own Root destinations.

## Original semantics retained

The generator extracts the first two actual DataStorageSection rows, in original order. It preserves original settingsDestinationCopy titles/summaries, rememberSettingsEntryVisual, icon selection, callback names, the intervening divider and sibling tint count 7 / palette offset 2. It does not substitute SettingsDetailEntrySection's default palette. The only platform UI substitutions are painterResource to the existing DesktopSettingsVectors binding and AppPreference's `value` argument to its existing `subtitle` binding.

No duplicate SettingsDetailEntry / SettingsDetailGroup / SettingsDetailEntrySection / SettingsCardGroup / Divider / DataStorageSection declaration is generated. The shared playback helper contract is verified before compile: frozen-handoff.json SHA `c4a9e1b05e87980d870d5598d4ad86767e102700a2c973449d84fd4eee84b641`.

The optional dialog delta changes section visibility/title only. Every existing coordinator call, chooser, confirmation, busy/error handling, restart-required behavior and exclusion statement remains in place. Construction and entry clicks do not configure, schedule, export, import or contact a server. Write operations still require the existing explicit dialog buttons.

## Evidence and boundaries

The isolated compiler compiled the full actual BackupSettingsDialog together with the generated entries and Root adapter. ImageComposeScene then ran MATERIAL3 and MIUIX with four actual pointer presses/releases. It checked original titles/summaries/order, exact target-to-section routing, absence of unsupported controls, unchanged actual coordinator/scheduler state and an untouched task-owned temporary store. `compile-evidence.json`, `proof/result.json`, `runtime.log` and two PNGs contain the evidence. This used no native Dialog/HWND, file chooser, user settings, live account or network requests and no shared Gradle.

Five producer tests cover original palette/order, invalid callback/palette/row-shape drift rejection and absence of duplicate shared FQNs. The dialog itself was compiled but not opened or clicked, so this is entry rendering/selection evidence, not a new native restore or WebDAV transport acceptance run.

The original SettingsShareScreen uses versioned shareable-settings JSON, Android document/system-share APIs, profiles/templates and an import preview. The current Windows dialog uses the existing Windows ZIP archive. Its Windows title/description and ZIP file chooser remain explicit. This slice does not implement Android JSON interoperability, profile selection or system-share parity and must not be counted as those features.

The remaining original DataStorageSection controls are not rendered: download directory, image directory, clear cache, auto cache clear interval and capacity threshold. Their original consumer/storage behavior is outside this slice; no no-op controls or alternate schedules are advertised. Existing WebDAV scheduling remains the already implemented Windows coordinator/scheduler, not a newly ported Android WorkManager implementation.
