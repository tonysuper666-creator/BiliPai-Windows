## Settings resource localization slice

Base PR #61 `2a498fa414228e102e6f9651d15f9f09cecec6d0`, itself based on PR #59 `2789bb2713877a68252387f74ca8e4b9edbcc6bc`. Three structural assertions had become stale when upstream localized display text. They now check the actual UI binding, resource mapping and exact Chinese copy. The category check verifies all eight canonical category-to-resource mappings and their text. The blur and import checks preserve the prior toggle/import assertions and check the specific widget or dialog resource binding. No product implementation was changed.

From the repository root, with Java, javac, `patch` and the Kotlin/JUnit versions already in the local Gradle cache (listed in the script):

```sh
python3 desktop/verification/ton-1085-settings-resource/verify.py /path/to/new-evidence-directory
```

Exit 0 means all exact expected failure identities and six negative mutations were observed. The script compiles the actual original and corrected tests and runs the JUnit Jupiter engine on isolated copies of the actual source files; the individual JUnit runs exit 1 because unrelated tests remain red. It does not invoke Gradle and does not establish Windows or full-suite results. The raw XML and logs from this run are in `evidence/`.

| Test class | Original | Corrected |
|---|---:|---:|
| SettingsRootCategoryContentStructureTest | 25 tests, 9 failures | 25 tests, 8 failures |
| SettingsMiuixSimplificationStructureTest | 5 tests, 5 failures | 5 tests, 3 failures |

No tests were skipped; no failure identity was added. Newly green: `tabletSettingsCategoryDrawer_showsSubtitle`, `animation settings keeps tuning but no independent glass toggles`, and `animation liquid glass section keeps tuning without independent switches`. All other original failures are retained. The separate tablet shell test also contains an obsolete subtitle assertion, but its next assertion covers an independent navigation/search behavior; it is deliberately unchanged and remains red.

Six counterexamples independently break the category UI binding, category mapping, transition-blur label, transition-blur binding, import-dialog label and import-dialog binding. Each restores the relevant fixed failure without changing the remaining failure set. The tests use textual structure assertions and these counterexamples are not substitutes for UI or integration tests.

`registration.diff` proposes all four pending PR #61 test corrections for Root to register together, including the prior SettingsCore and Animation corrections. It is not applied here. Until registration, Gradle uses the original tests. PR #59/#61 merge, full Gradle `:test`, Windows results, remaining app-core failures, and overall issue completion are not claimed.
