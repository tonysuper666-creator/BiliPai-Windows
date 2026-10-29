# Python gates between M0 and M5

Run from a clean checkout with full Git history and Python 3.12:

```sh
python3.12 -B desktop/tools/run-tool-tests.py --stage ux
python3.12 -B desktop/tools/run-tool-tests.py --stage generated
python3.12 -B desktop/tools/run-tool-tests.py --stage policies
```

Every stage returns nonzero for **any** failure, including pre-existing failures.
No behavior test is filtered, skipped or retired. `generated` keeps its existing
meaning: the complete `desktop/tools/tests` suite, including output contracts.
Keep the normal generator/build prerequisites when checking generated output.

The runner creates a disposable linked Git worktree named `.legacy-root`.
Upstream originals, including plugin examples outside the canonical source roots,
are restored from the same `desktop/upstream-core/legacy-root.commit` used by Gradle. Current
desktop sources, producer scripts, tests, catalogs and `.github` workflows are
copied from the caller's checkout (including uncommitted edits). Gradle caches and generated build outputs are excluded from copying; an existing
live `desktop/build` is linked just as in Gradle so generated contracts inspect
the actual output. Tracked native build recipes remain present. The caller's checkout and Gradle's `.legacy-root`
are never modified. Git history queries see the real root of the disposable
worktree and all objects in the original clone; the seven archive-only Git
failures are addressed by this setup rather than by changing assertions.
Missing history fails before discovery; fetch full history and retry.

The updater inventory now pins the merged source tree. Its complete
`test_sync_upstream` suite runs against that live tree before the retained
generator suites run at the legacy read point. Every test still runs once,
and either suite failing makes the command fail.

The current Gradle model records source ownership for migrated modules and the
transition-patch task. The read point keeps the current module inputs separately
under `.merged-root`, while generators still read pinned originals. The model
materializes the current transition patches with the same strict, exactly-once
unified-hunk semantics as the Gradle task, and respects the current generated-file
exclusions when collecting compiled sources. It does not replace the live build
recipe, add producer skips or run a JVM. The full producer and symbol-universe
checks remain active; JVM/build validation is still separate.

This is a compatibility gate for retained slices, not v0.6.7 product coverage.
Migrated modules must still pass their current JVM/product tests. M5 removes
the slice mechanism and these compatibility gates together (TON-1008).

## Windows publication policy stage

Both Windows workflows use the same runner. The temporary read point changes
only original upstream fixtures, while every policy implementation remains live:

| Tests | Input / reason |
| --- | --- |
| `test_sync_upstream`: canonical, adapted inventory, version metadata and API risk fixtures | Merged originals and current manifests, with exact current hashes, successor paths and source commits. Retained slice inputs remain separately pinned. |
| Other `test_sync_upstream` fixtures | Current sync script and current manifests/workflows; their isolated fixture repositories are unchanged. |
| `test_publish_release` | Current publication script, workflow and isolated release fixtures. |
| `test_provision_native_diagnostic_msvc`, `test_provision_native_diagnostic_sdk` | Current provisioning scripts and isolated SDK fixtures. |
| `test_verify_updater_baseline`, `test_fetch_updater_baseline` | Current package validation/download scripts and isolated byte fixtures. |
| `test_same_workspace_candidate`, `test_same_workspace_terminal_admission` | Current admission scripts and isolated source/runtime evidence fixtures. |

The detector checkout now fetches full history as required by the pinned read
point; the Windows build and candidate checkouts already do so.

## Desktop wiring checks

The two Main.kt / DesktopShell.kt whole-file hash assertions locked unrelated
source text after reversing UX edits. They falsely reject valid M0 imports and
initialization. They now check the retained behavior: persisted bounds feed the
window/fullscreen state; shortcuts use the navigation owner and existing shell
fullscreen action. Existing model, dispatch, shortcut precedence, focus and
window persistence tests remain. These source checks do not prove GUI behavior.

## macOS verification inputs

Use TON-987 `verifiers.zip`'s unchanged `sitecustomize.py` on `PYTHONPATH` for
legacy producers that unconditionally emit Windows long-path prefixes. Include
the current `desktop/tools` on `PYTHONPATH`. Set `BILIPAI_UX_JAR_DIRS` and
`BILIPAI_UX_JDK` to its verified JAR and JDK-signature fixture directories.
Set `TMPDIR` to an existing physical directory, outside macOS's symlinked `/var`
temporary tree, so path-safety tests exercise their intended inputs. Apply the
same environment to baseline and candidate. This adapter changes path syntax,
not source bytes, pins, discovery or assertions.
