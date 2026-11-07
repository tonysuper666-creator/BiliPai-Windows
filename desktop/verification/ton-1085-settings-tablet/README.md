# Settings tablet category rail: isolated structure verification

Baseline PR61 commit: `496928b3727c39744cbec1dda0300c88ac25e95b`. The corrected patch is the one in the commit containing this directory (`git rev-parse HEAD`). The test fix preserves the category subtitle contract after resource localization and follows the current category-or-search detail policy. The original category subtitle resource mappings and text remain covered by `tabletSettingsCategoryDrawer_showsSubtitle`.

Run from the repository root with a new output directory:

```sh
python3 desktop/verification/ton-1085-settings-tablet/verify.py /path/to/new-output
```

Requires a JDK, `patch`, Git, Python 3, and the exact Gradle cached Kotlin/JUnit jars listed in the script. The verifier does not run Gradle, Android, Windows, or a UI. It reads production/test sources from the fixed baseline, applies the baseline or working-tree test patch in temporary fixtures, compiles the test, then executes all 25 JUnit methods. Baseline and corrected JUnit processes exit 1 because other identities still fail; the driver exits 0 only after checking exact identity sets, counts, no skips and all mutants. Captured result: 4 -> 3 failures, no added/missing/skipped tests.

The five negative fixtures change one condition apiece: subtitle binding, category policy, search policy, detail/empty routing, or search input binding. All make the target identity fail; the subtitle mutant also fails the pre-existing separate subtitle test. The positive whitespace fixture preserves the target result. Raw per-test XML, process logs and the exact applied patches are in `evidence/`.

The first driver attempt exited 1 because its expected set omitted the existing subtitle test, which correctly detected that same subtitle mutant; `evidence/initial-driver-failed.log` records it. The verifier was fixed to require both failures and rerun successfully with the final patch.

This is a source-structure fixture, not UI execution or full Gradle validation. Root still owns four upstream test registrations, PR59/61 merge and full/Windows gates; the appearance style-description contract remains undecided.
