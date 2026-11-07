TON-1349 implements the upstream physical left/right partition rail preference in the existing Windows HOME_FEED settings page.

Provenance: integration base `235eec58b195b1a16047031a95fbaa9581694f21`; upstream reference `6f0696eb1389728b5a090125d32c1aae470f72f1`; unchanged partition source SHA-256 `04ba7fe22888802cb1da9e4d91ef97df318c49b2a9aea37672a00f4b10504010`. See `upstream-reference.txt` for the fixed key, default and original setting labels. Canonical source bytes, pins, Root and build/test gates are unchanged.

The real HOME_FEED mount is `desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsHomeSettings.kt`, reached by `DesktopOriginalHomeSettingsRootHost`. Both that page and the existing generated PartitionContent observe the same `settings.partition_side_rail_on_right` projection. Writes join the mounted page's original DataStore transaction/admission. The producer changes only physical placement, horizontal insets and push direction. Its two children keep their composition identity, selection and lazy-list state.

Reproduction (materialize the repository's fixed `.legacy-root` first):

```sh
git diff --check <base> <head>
python3 desktop/tools/extract-upstream-home-partition.py --repo .legacy-root --output <personal-output>
python3 -m unittest desktop/tools/tests/test_partition_side_rail.py -v
<current-shared-heavy-FIFO> ./gradlew -p desktop :test --tests com.bilipai.desktop.ui.DesktopPartitionSideRailTest --tests com.bilipai.desktop.settings.DesktopPartitionPreferencesTest --console=plain
```

`DesktopPartitionSideRailTest` uses the actual HOME_FEED RootHost and original generated PartitionContent in offscreen Compose scenes. It changes the real control, checks measured rail/list bounds at 360 and 1200 dp, scrolls both real lazy lists, retains selected region and scroll offsets through left→right→left, and restores right from a separately copied disk backing. Its source has no dependency on the new preference bridge and can be copied unchanged to the fixed base to demonstrate the missing behavior. New preference unit tests also cover shared updates, default, independent cold backing, unrelated-key preservation, retired/frozen writers and failed disk replacement. The Python test executes the real producer CLI and independently reverses the entire receipt, including the existing refresh bindings, to the pinned original bytes.

Raw command records, logs, XML and a complete `task|class|name` failure/skip identity comparison are delivered with the issue evidence attachment. The normal Gradle finalizers remain enabled; the actual whole-command exit must be read separately from the target test results. These are offscreen behavior tests; no Windows, OS GUI, native input or performance measurement is claimed.

Observed results: the identical Host test source (SHA-256 `33f931a5ccc1acb10b9ac46352e0ef6d768476228cb38d3304c320ff7f3843a5`) fails all three cases on the unchanged base because the real HOME_FEED control is absent. The candidate passes all three Host cases plus all three preference tests. Both whole Gradle commands exit 1: they share the same 46 upstream-app-core failures and one sponsor initialization failure. Complete XML comparison finds zero new failure identities and zero new skips. See `results-summary.json`; complete identities and raw XML are in the attached evidence. This is not a full-gate pass.

The candidate tests ran against the working tree on the fixed base; `tested-source-manifest.json` records the exact six source hashes. The delivery verifies those bytes against the committed candidate without rewriting the original command revision or exit. Earlier fixture failures (missing PGC API and Android context) and the cancelled obsolete baseline run are retained in the attachment as attempts, not acceptance results. The final head/base harnesses are byte-identical.
