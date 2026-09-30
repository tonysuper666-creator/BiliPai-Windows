# Restore commit / exit barrier — isolated, not installed

Root integration changes exactly four targets listed in `restore-contract.json`. Do not copy `generated/` into the product; rerun the installed `desktop/tools/extract-upstream-settings.py` through the existing source preparation task. No shared build/manifest/Shell change is required.

## Proven defect

The immutable foundation manifest is `../blocked-up-foundation-product-snapshot/manifest.json`, raw SHA-256 `74165f272e012c1a13e9171e963bf76127dbd71e7003da9e2576b7af2a25e2e6`. The baseline runtime overrides **zero** production classes. Both real local ZIP and real original WebDAV protocol restored two files after `beforeRestore` cancelled the initiating page's Job; its later UI-dispatched exit callback never executed. The two baseline JUnit cases intentionally assert this defective result, and passed.

Current Shell source establishes the matching lifecycle: `DesktopDiscoveryStorageBoundary` contains `DesktopReadyApp`; `closeDiscoveryStorage` calls `startupGuard.close()`, while ReadyApp and its backup dialog have composition-owned coroutine scopes. Backup `beforeRestore` calls this closure before quiescence; `afterRestore` dispatches `onExit` to Main. The controlled test cancellation models this disposal without claiming a Compose/window test. Shell source identity is captured separately in the contract; the old foundation jar does not claim to contain Root's latest guard.

## Production delta

- `DesktopBackupArchive.restore(bytes, onSuccessfulRestore = {})` calls the observer synchronously **only after every replacement succeeded**, outside the rollback catch, before returning. It does not signal validation, quiescence, or replacement/rollback failure. `@JvmOverloads` also preserves the existing one-argument JVM entry point. The coordinator's observer only sets its private per-operation flag.
- `DesktopBackupCoordinator.restoreOperation` creates a per-attempt archive/flag, checks the initiating context before work and immediately before quiescence, and runs only a committed operation's `afterRestore` in `NonCancellable` from `finally`. This executes while the existing operation/file lock is still owned. A cancelled initiating coroutine remains cancelled when the outer operation returns; it is not converted to a fake successful `Result`.
- The existing generated Windows WebDAV service constructor gains a default trailing `onSuccessfulRestore` callback and `@JvmOverloads`. Only its Windows `restoreFromBackupArchive` thin wrapper forwards the callback. Every original HTTP protocol method remains unchanged apart from the two existing Windows same-origin/download-size adapters. This is necessary because original `withContext(IO)` can discard the service's successful return during cancellation; a flag set by the coordinator after `getOrThrow` would be too late.
- The coordinator's ordinary reusable archive/service still handles export/upload; restoration uses its own callback-bound instance. No flags persist across failed/retried/concurrent calls.

Root's existing `beforeRestore` and `afterRestore` signatures and call sites remain intact. Do not put the entire restore/download in `NonCancellable`; do not keep disposed Compose scopes alive to avoid this defect.

## Actual isolated verification

`python desktop/.local/backup-restore-exit-barrier/compile-fixtures.py` invokes cached JDK21/Kotlin2.4 directly and actual JUnit Platform/Jupiter discovery, using three immutable foundation jars and pinned dependency identities. It ran 2 baseline cases, then **28 successful patched cases: 13 new + 9 existing Archive + 6 existing WebDAV**, zero skipped. `python .../test_extraction.py` passed two original-method/success-marker checks.

The new cases exercise two-file real disk commit; local and loopback WebDAV page disposal; delayed/suspending UI callback; the original service's lost `withContext` return; invalid ZIP; real second-target filesystem failure and first-target rollback; failed quiescence; cancellation before launch; cancellation while a real HTTP GET is blocked; HTTP503; retry flag isolation; and rejected concurrent restore. Every fixture temp directory, page Job, UI executor, server and server executor is task owned and stopped/deleted. No user directory, account API, credentials, shared Gradle or HWND was used.

## Boundaries

- These are real disk/protocol/JUnit tests with controlled scope cancellation, not an actual Compose ReadyShell disposal or application-exit test. Root must integrate and run its shared focused tests.
- Download retains the original synchronous OkHttp behavior and 20-second read timeout. This patch does not claim immediate interruption of a blocked socket. The in-flight GET fixture returns after cancellation; the active-context guard then prevents quiescence/replacement/exit. No network operation is made NonCancellable.
- `beforeRestore` may deliberately cancel the page; once quiescence starts, the existing synchronous validated replacement/rollback transaction is allowed to complete. `afterRestore` must still throw normally if its own work fails; NonCancellable protects it from the initiating page cancellation, not callback faults.
- No upstream source inventory additions, account writes, remote fixture destinations beyond `127.0.0.1`, or backup format changes are introduced.
