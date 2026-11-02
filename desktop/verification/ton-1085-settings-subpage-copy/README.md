# Settings subpage headings: isolated structure verification

PR61 baseline: `41e9265fe2b0ccd65b6554f2a19ef2caa0fc61d1`. The corrected patch is the one in the commit containing this directory. The existing root-category test still checks all its original groups, subpage headings and order, but follows actual localized title bindings and their Chinese values. Upstream `d98ddf5ab` changed the old display-mode heading to “界面与效果”; `fd46b479d` then moved it and the other headings into string resources. The glass heading checks both advanced and normal resource bindings; the haptic check follows the anchored switch through its title mapping to the resource. The playback order check now requires both headings to exist.

Run from the repository root with a new output directory:

```sh
python3 desktop/verification/ton-1085-settings-subpage-copy/verify.py /path/to/new-output
```

Requires Git, Python 3, `patch`, a JDK, and the exact cached Kotlin/JUnit jars named in the script. The verifier loads production and test sources from the fixed baseline, applies either the baseline patch or the current working-tree patch to isolated fixtures, compiles the test, and runs all 25 JUnit methods. Recorded driver exit: 0. Baseline and corrected JUnit process exit: 1 in both cases because other identities remain red. Failures: 3 → 2, with no added, missing, or skipped identities. The fixed identity is `settingsSubpages_useNagramStyleSectionNames()`; the remaining identities are `aboutProjectOverview_usesRasterLauncherAssetForComposePainter()` and `broadSettingsEntriesOpenAtPageStartWhileSubtopicEntriesUsePreciseFocus()`.

Six isolated negative mutations change the display heading binding, text heading binding, splash heading value, normal glass binding, haptic title mapping, and playback heading existence. Each restores the target failure; a whitespace-only mutation remains at the two unrelated failures. Every JUnit process exits 1 due to those unrelated failures, while the driver verifies exact identities and exits 0. Per-test XML, process logs, applied patches, and driver output are in `evidence/`.

This is source-structure verification, not an Android UI, Gradle, or Windows run. Root retains the four upstream test registrations, PR59/61 merge and full/Windows gates. The separate appearance style-description contract remains undecided.
