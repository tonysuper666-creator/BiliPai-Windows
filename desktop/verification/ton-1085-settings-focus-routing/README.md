# Settings playback focus routing: isolated structure verification

Baseline: PR 61 `46adfcfabdf6d82ccf9f2c60c14005c21a39182c`. The candidate is the commit containing this directory. This checks the existing Kotlin/JUnit source-structure suite against fixtures populated from that baseline, applying the baseline and candidate test patches respectively; it does not execute Android UI or Gradle.

The upstream playback category now opens `PlaybackSettings` directly rather than using the deleted intermediate decoder entry. The test retains the broad entry's default start and decoder-first section checks and checks destination, focus ID and callback for the remaining navigation, animation, quality, comments, fullscreen and diagnostics entries. Playback page-start checks assume no pending focus request; stale-request behavior is not established here. About-project icon loading is a separate unresolved failure: dynamic `AsyncImage` receives a selected launcher resource, but adaptive-icon compatibility has not been independently verified here.

From the repository root, with the cached Kotlin/JUnit artifacts listed in `verify.py` and an unused output directory:

```sh
python3 desktop/verification/ton-1085-settings-focus-routing/verify.py ../settings-focus-run3
```

Driver exit: 0. Every JUnit invocation exit: 1 because the about-project failure remains. Baseline: 25 tests, 2 failures; candidate and restored fixture: 25 tests, 1 failure; zero skipped or missing and zero new failure identities. Removed failure: `broadSettingsEntriesOpenAtPageStartWhileSubtopicEntriesUsePreciseFocus()`. Remaining: `aboutProjectOverview_usesRasterLauncherAssetForComposePainter()`.

Ten independent mutants alter the broad route, absence-of-focus handling, first section, quality destination/focus, comments/fullscreen/diagnostics/bottom-bar focus and animation callback. Each restores the target failure while keeping the unrelated failure; the unmodified candidate is run again after all mutants. `evidence/` contains the JUnit XML and invocation logs for each run, plus the driver output. These are source-structure counterexamples, not evidence of actual UI behavior. Gradle, full suite, Windows, and UI remain unverified. Root owns test registration and integration of PRs 59 and 61.
