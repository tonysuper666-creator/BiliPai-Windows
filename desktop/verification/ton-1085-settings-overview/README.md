# Settings about overview verification

This checks the existing about-section structure test, not live Compose UI or Gradle.
At baseline `00c3dd3575693edcc0f59ee8aba1bbbacbd3304a`, the about page calls
`AboutProjectOverviewCard` with the current version before the settings rows, but
the test expected the entire invocation on one line with no other arguments.
The revised test accepts formatting and additional arguments while still requiring
the current version binding and the card before the settings group. All existing
developer-list assertions remain.

From the repository root, with cached Kotlin 2.4.0 and JUnit 5.13.4 dependencies:

```
python3 desktop/verification/ton-1085-settings-overview/verify.py ../settings-overview-run2
```

The destination must not already exist. The script exited 0. Each isolated JUnit
invocation exited 1 because four unrelated existing failures remain. The baseline
has 25 tests and five failures; corrected has the same 25 tests, four failures,
zero skipped, and no new failure identities. The only fixed identity is
`SettingsRootCategoryContentStructureTest.aboutSectionShowsProjectOverviewAndStaticDevelopersBeforeRows()`.
Removal of the overview, wrong version binding, or moving it below the settings
group each restores that failure; whitespace variation remains green. `evidence/`
contains the raw JUnit XML and logs. These mutations are source-structure checks,
not runtime rendering or interaction tests.

Gradle registration, full suite and Windows remain pending Root coordination.
